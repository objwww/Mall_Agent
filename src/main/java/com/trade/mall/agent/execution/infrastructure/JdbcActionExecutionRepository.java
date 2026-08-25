package com.trade.mall.agent.execution.infrastructure;

import com.trade.mall.agent.execution.application.ActionExecutionRepository;
import com.trade.mall.agent.execution.application.DuplicateTransitionException;
import com.trade.mall.agent.execution.application.OptimisticLockException;
import com.trade.mall.agent.execution.domain.ActionAttempt;
import com.trade.mall.agent.execution.domain.ActionExecution;
import com.trade.mall.agent.execution.domain.AttemptOutcome;
import com.trade.mall.agent.execution.domain.ExecutionState;
import com.trade.mall.agent.execution.domain.OperationId;
import com.trade.mall.agent.ledger.DomainEvent;
import com.trade.mall.agent.ledger.infrastructure.JdbcEventLedger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * JDBC/MySQL（数据库）版动作执行仓储。
 * ActionExecution（动作执行）与 ActionAttempt（动作尝试）及其领域事件在同一本地事务提交。
 */
public final class JdbcActionExecutionRepository implements ActionExecutionRepository {

    private final DataSource dataSource;
    private final JdbcEventLedger ledger;
    private final LongSupplier clock;

    public JdbcActionExecutionRepository(DataSource dataSource) {
        this(dataSource, System::currentTimeMillis);
    }

    public JdbcActionExecutionRepository(DataSource dataSource, LongSupplier clock) {
        this.dataSource = dataSource;
        this.ledger = new JdbcEventLedger(dataSource);
        this.clock = clock;
    }

    @Override
    public Optional<ActionExecution> load(OperationId id) {
        String sql = "SELECT state, version FROM agent_action_execution WHERE operation_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.value());
            ExecutionState state;
            long version;
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                state = ExecutionState.valueOf(rs.getString(1));
                version = rs.getLong(2);
            }
            // 先关闭 execution ResultSet，再读 Attempt 子表，避免依赖 JDBC driver 是否允许
            // 同一连接上同时持有两个活动 ResultSet。
            return Optional.of(ActionExecution.rehydrate(id, state, version, loadAttempts(connection, id.value())));
        } catch (SQLException e) {
            throw new IllegalStateException("cannot load execution: " + id, e);
        }
    }

    @Override
    public void create(ActionExecution execution) {
        String sql = "INSERT INTO agent_action_execution "
            + "(operation_id, state, version, reconcile_count, next_reconcile_at, first_unknown_at, recovery_claim_until, updated_at) "
            + "VALUES (?, ?, ?, 0, NULL, NULL, NULL, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, execution.id().value());
            ps.setString(2, execution.state().name());
            ps.setLong(3, execution.version());
            ps.setLong(4, clock.getAsLong());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create execution: " + execution.id(), e);
        }
    }

    @Override
    public void save(ActionExecution execution) {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long storedVersion = lockVersion(connection, execution.id());
                if (storedVersion != execution.version()) {
                    throw new OptimisticLockException(
                        "concurrent transition on " + execution.id() + " expected v" + execution.version()
                            + " but was v" + storedVersion);
                }
                for (DomainEvent event : execution.pendingEvents()) {
                    if (ledger.exists(connection, event.eventId())) {
                        throw new DuplicateTransitionException(execution.id().value(), event.eventId());
                    }
                }

                String update = "UPDATE agent_action_execution SET state=?, version=version+1, updated_at=?, recovery_claim_until=NULL "
                    + "WHERE operation_id=? AND version=?";
                try (PreparedStatement ps = connection.prepareStatement(update)) {
                    ps.setString(1, execution.state().name());
                    ps.setLong(2, clock.getAsLong());
                    ps.setString(3, execution.id().value());
                    ps.setLong(4, execution.version());
                    if (ps.executeUpdate() != 1) {
                        throw new OptimisticLockException("concurrent transition on " + execution.id());
                    }
                }

                replaceAttempts(connection, execution.id().value(), execution.attempts());
                for (DomainEvent event : execution.pendingEvents()) {
                    if (!ledger.append(connection, event)) {
                        throw new DuplicateTransitionException(execution.id().value(), event.eventId());
                    }
                }
                connection.commit();
                execution.clearPendingEvents();
            } catch (RuntimeException | SQLException e) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { connection.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot save execution: " + execution.id(), e);
        }
    }

    private static long lockVersion(Connection connection, OperationId id) throws SQLException {
        String sql = "SELECT version FROM agent_action_execution WHERE operation_id = ? FOR UPDATE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new OptimisticLockException("vanished: " + id);
                return rs.getLong(1);
            }
        }
    }

    private static List<ActionAttempt> loadAttempts(Connection connection, String operationId) throws SQLException {
        String sql = "SELECT seq_no, outcome FROM agent_action_attempt WHERE operation_id = ? ORDER BY seq_no";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ActionAttempt> attempts = new ArrayList<>();
                while (rs.next()) {
                    attempts.add(new ActionAttempt(rs.getInt(1), AttemptOutcome.valueOf(rs.getString(2))));
                }
                return attempts;
            }
        }
    }

    private static void replaceAttempts(Connection connection, String operationId, List<ActionAttempt> attempts)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM agent_action_attempt WHERE operation_id = ?")) {
            delete.setString(1, operationId);
            delete.executeUpdate();
        }
        if (attempts.isEmpty()) return;
        String insert = "INSERT INTO agent_action_attempt (operation_id, seq_no, outcome) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(insert)) {
            for (ActionAttempt attempt : attempts) {
                ps.setString(1, operationId);
                ps.setInt(2, attempt.seqNo());
                ps.setString(3, attempt.outcome().name());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}

