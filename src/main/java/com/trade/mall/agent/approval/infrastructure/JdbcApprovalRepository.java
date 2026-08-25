package com.trade.mall.agent.approval.infrastructure;

import com.trade.mall.agent.approval.Approval;
import com.trade.mall.agent.approval.ApprovalDuplicateTransitionException;
import com.trade.mall.agent.approval.ApprovalId;
import com.trade.mall.agent.approval.ApprovalOptimisticLockException;
import com.trade.mall.agent.approval.ApprovalRepository;
import com.trade.mall.agent.approval.ApprovalState;
import com.trade.mall.agent.ledger.DomainEvent;
import com.trade.mall.agent.ledger.infrastructure.JdbcEventLedger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * JDBC/MySQL（数据库）版 ApprovalRepository（审批仓储）。
 * save() 在一条连接、一个事务里完成：版本检查 → 事件幂等预检 → 状态更新 → 事件追加。
 */
public final class JdbcApprovalRepository implements ApprovalRepository {

    private final DataSource dataSource;
    private final JdbcEventLedger ledger;
    private final LongSupplier clock;

    public JdbcApprovalRepository(DataSource dataSource) {
        this(dataSource, System::currentTimeMillis);
    }

    public JdbcApprovalRepository(DataSource dataSource, LongSupplier clock) {
        this.dataSource = dataSource;
        this.ledger = new JdbcEventLedger(dataSource);
        this.clock = clock;
    }

    @Override
    public Optional<Approval> load(ApprovalId id) {
        String sql = "SELECT operation_id, action_type, action_version, params_hash, expires_at, state, approver_id, version "
            + "FROM agent_approval WHERE approval_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(id, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot load approval: " + id, e);
        }
    }

    @Override
    public Optional<Approval> findByOperationId(String operationId) {
        String sql = "SELECT approval_id, operation_id, action_type, action_version, params_hash, expires_at, state, approver_id, version "
            + "FROM agent_approval WHERE operation_id = ? ORDER BY updated_at DESC, approval_id DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                ApprovalId id = ApprovalId.of(rs.getString(1));
                return Optional.of(Approval.rehydrate(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6),
                    ApprovalState.valueOf(rs.getString(7)), rs.getString(8), rs.getLong(9)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot find approval for operation: " + operationId, e);
        }
    }

    @Override
    public void create(Approval approval) {
        String sql = "INSERT INTO agent_approval "
            + "(approval_id, operation_id, action_type, action_version, params_hash, expires_at, state, approver_id, version, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection()) {
            boolean ac=connection.getAutoCommit(); connection.setAutoCommit(false);
            try {
                long now=clock.getAsLong();
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, approval.id().value()); ps.setString(2, approval.operationId()); ps.setString(3, approval.actionType());
                    ps.setString(4, approval.actionVersion()); ps.setString(5, approval.paramsHash()); ps.setLong(6, approval.expiresAt());
                    ps.setString(7, approval.state().name()); ps.setString(8, approval.approverId()); ps.setLong(9, approval.version());
                    ps.setLong(10, now); ps.setLong(11, now); ps.executeUpdate();
                }
                for (DomainEvent event : approval.pendingEvents()) {
                    if (!ledger.append(connection, event)) throw new ApprovalDuplicateTransitionException(approval.id().value(), event.eventId());
                }
                connection.commit(); approval.clearPendingEvents();
            } catch (RuntimeException | SQLException e) { try { connection.rollback(); } catch(SQLException ignored){} throw e; }
            finally { try { connection.setAutoCommit(ac); } catch(SQLException ignored){} }
        } catch (SQLException e) { throw new IllegalStateException("cannot create approval: " + approval.id(), e); }
    }

    @Override
    public void save(Approval approval) {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long storedVersion = lockVersion(connection, approval.id());
                if (storedVersion != approval.version()) {
                    throw new ApprovalOptimisticLockException(
                        "concurrent transition on " + approval.id() + " expected v" + approval.version()
                            + " but was v" + storedVersion);
                }
                for (DomainEvent event : approval.pendingEvents()) {
                    if (ledger.exists(connection, event.eventId())) {
                        throw new ApprovalDuplicateTransitionException(approval.id().value(), event.eventId());
                    }
                }

                String update = "UPDATE agent_approval SET state=?, approver_id=?, version=version+1, updated_at=? "
                    + "WHERE approval_id=? AND version=?";
                try (PreparedStatement ps = connection.prepareStatement(update)) {
                    ps.setString(1, approval.state().name());
                    ps.setString(2, approval.approverId());
                    ps.setLong(3, clock.getAsLong());
                    ps.setString(4, approval.id().value());
                    ps.setLong(5, approval.version());
                    if (ps.executeUpdate() != 1) {
                        throw new ApprovalOptimisticLockException("concurrent transition on " + approval.id());
                    }
                }

                for (DomainEvent event : approval.pendingEvents()) {
                    if (!ledger.append(connection, event)) {
                        throw new ApprovalDuplicateTransitionException(approval.id().value(), event.eventId());
                    }
                }
                connection.commit();
                approval.clearPendingEvents();
            } catch (RuntimeException | SQLException e) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { connection.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot save approval: " + approval.id(), e);
        }
    }

    @Override
    public java.util.List<Approval> findDueToExpire(long now, int limit) {
        if (limit <= 0) return java.util.List.of();
        String sql = "SELECT approval_id, operation_id, action_type, action_version, params_hash, expires_at, state, approver_id, version "
            + "FROM agent_approval WHERE state IN ('PENDING','GRANTED') AND expires_at <= ? ORDER BY expires_at, approval_id LIMIT ?";
        try (Connection c=dataSource.getConnection(); PreparedStatement ps=c.prepareStatement(sql)) {
            ps.setLong(1,now); ps.setInt(2,limit);
            try(ResultSet rs=ps.executeQuery()) {
                java.util.List<Approval> out=new java.util.ArrayList<>();
                while(rs.next()) out.add(Approval.rehydrate(ApprovalId.of(rs.getString(1)),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6),ApprovalState.valueOf(rs.getString(7)),rs.getString(8),rs.getLong(9)));
                return java.util.List.copyOf(out);
            }
        } catch(SQLException e){ throw new IllegalStateException("cannot scan expired approvals",e); }
    }

    private static long lockVersion(Connection connection, ApprovalId id) throws SQLException {
        String sql = "SELECT version FROM agent_approval WHERE approval_id = ? FOR UPDATE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApprovalOptimisticLockException("vanished: " + id);
                return rs.getLong(1);
            }
        }
    }

    private static Approval read(ApprovalId id, ResultSet rs) throws SQLException {
        return Approval.rehydrate(id, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
            ApprovalState.valueOf(rs.getString(6)), rs.getString(7), rs.getLong(8));
    }
}

