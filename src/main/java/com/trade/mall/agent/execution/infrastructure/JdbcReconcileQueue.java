package com.trade.mall.agent.execution.infrastructure;

import com.trade.mall.agent.execution.reconcile.ReconcileQueue;
import com.trade.mall.agent.execution.reconcile.ReconcileQueueEntry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC/MySQL（数据库）版对账队列；不另建队列表，直接复用 agent_action_execution
 * 上的 reconcile_count / next_reconcile_at / first_unknown_at 调度字段。
 *
 * <p>V9：{@link #due(long, int)} 不再只是“看一眼谁到期”，而是在同一事务里用
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}（加锁并跳过其他实例已锁记录）认领，并把
 * {@code next_reconcile_at} 临时推进 30 秒作为短租约。这样多个 Agent 实例不会同时 query
 * 同一个 UNKNOWN（结果未知）操作；处理成功会 remove（移除调度），仍未知会 reschedule
 * （重新安排），若进程在认领后崩溃，30 秒后租约自然到期并可被再次发现，不需要新队列表。</p>
 */
public final class JdbcReconcileQueue implements ReconcileQueue {

    private static final long CLAIM_LEASE_MILLIS = 30_000L;
    private final DataSource dataSource;

    public JdbcReconcileQueue(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void enqueueFirstIfAbsent(String operationId, long now) {
        String sql = "UPDATE agent_action_execution SET "
            + "reconcile_count=CASE WHEN next_reconcile_at IS NULL THEN 0 ELSE reconcile_count END, "
            + "first_unknown_at=COALESCE(first_unknown_at, ?), "
            + "next_reconcile_at=COALESCE(next_reconcile_at, ?) "
            + "WHERE operation_id=? AND state='UNKNOWN'";
        executeUpdate(sql, ps -> {
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setString(3, operationId);
        });
    }

    @Override
    public void reschedule(String operationId, int newReconcileCount, long nextAt) {
        String sql = "UPDATE agent_action_execution SET reconcile_count=?, next_reconcile_at=? "
            + "WHERE operation_id=? AND state='UNKNOWN' AND next_reconcile_at IS NOT NULL";
        executeUpdate(sql, ps -> {
            ps.setInt(1, newReconcileCount);
            ps.setLong(2, nextAt);
            ps.setString(3, operationId);
        });
    }

    @Override
    public void remove(String operationId) {
        String sql = "UPDATE agent_action_execution SET reconcile_count=0, next_reconcile_at=NULL, first_unknown_at=NULL "
            + "WHERE operation_id=?";
        executeUpdate(sql, ps -> ps.setString(1, operationId));
    }

    @Override
    public Optional<ReconcileQueueEntry> entry(String operationId) {
        String sql = "SELECT reconcile_count, next_reconcile_at, first_unknown_at "
            + "FROM agent_action_execution WHERE operation_id=? AND state='UNKNOWN' AND next_reconcile_at IS NOT NULL";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new ReconcileQueueEntry(operationId, rs.getInt(1), rs.getLong(2), rs.getLong(3)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot query reconcile queue: " + operationId, e);
        }
    }

    @Override
    public List<ReconcileQueueEntry> due(long now, int limit) {
        if (limit <= 0) return List.of();
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<ReconcileQueueEntry> entries = lockDue(connection, now, limit);
                if (!entries.isEmpty()) {
                    long leaseUntil = now + CLAIM_LEASE_MILLIS;
                    try (PreparedStatement claim = connection.prepareStatement(
                            "UPDATE agent_action_execution SET next_reconcile_at=? "
                                + "WHERE operation_id=? AND state='UNKNOWN' AND next_reconcile_at<=?")) {
                        for (ReconcileQueueEntry entry : entries) {
                            claim.setLong(1, leaseUntil);
                            claim.setString(2, entry.operationId());
                            claim.setLong(3, now);
                            claim.addBatch();
                        }
                        claim.executeBatch();
                    }
                }
                connection.commit();
                return entries;
            } catch (RuntimeException | SQLException e) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { connection.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot claim due reconcile entries", e);
        }
    }

    private static List<ReconcileQueueEntry> lockDue(Connection connection, long now, int limit) throws SQLException {
        String sql = "SELECT operation_id, reconcile_count, next_reconcile_at, first_unknown_at "
            + "FROM agent_action_execution WHERE state='UNKNOWN' AND next_reconcile_at IS NOT NULL "
            + "AND next_reconcile_at <= ? ORDER BY next_reconcile_at, operation_id LIMIT ? FOR UPDATE SKIP LOCKED";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<ReconcileQueueEntry> entries = new ArrayList<>();
                while (rs.next()) {
                    entries.add(new ReconcileQueueEntry(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4)));
                }
                return entries;
            }
        }
    }

    private void executeUpdate(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot update reconcile queue", e);
        }
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}

