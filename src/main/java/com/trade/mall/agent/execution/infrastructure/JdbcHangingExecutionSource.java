package com.trade.mall.agent.execution.infrastructure;

import com.trade.mall.agent.execution.domain.ExecutionState;
import com.trade.mall.agent.execution.recovery.HangingExecution;
import com.trade.mall.agent.execution.recovery.HangingExecutionSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * JDBC/MySQL（数据库）版悬挂执行来源。
 *
 * <p>候选只有两类：DISPATCHED（已记录发送、未落结局）以及 UNKNOWN（结果未知）但对账队列
 * 丢失的记录。认领时写一个短租约 recovery_claim_until，失败后租约到期即可再次扫描，避免
 * 内存版“认领一次永久消失”的问题；真正状态推进仍由 CrashRecoveryScanner（崩溃恢复扫描器）完成。</p>
 */
public final class JdbcHangingExecutionSource implements HangingExecutionSource {

    private static final long CLAIM_LEASE_MILLIS = 30_000L;

    private final DataSource dataSource;
    private final LongSupplier clock;

    public JdbcHangingExecutionSource(DataSource dataSource, LongSupplier clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Override
    public List<HangingExecution> claimHanging(int limit) {
        long now = clock.getAsLong();
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<Candidate> candidates = lockCandidates(connection, now, limit);
                if (candidates.isEmpty()) {
                    connection.commit();
                    return List.of();
                }
                long claimUntil = now + CLAIM_LEASE_MILLIS;
                List<HangingExecution> out = new ArrayList<>(candidates.size());
                for (Candidate candidate : candidates) {
                    claim(connection, candidate.operationId(), claimUntil);
                    out.add(new HangingExecution(candidate.operationId(), candidate.state(),
                        latestAttemptSeq(connection, candidate.operationId())));
                }
                connection.commit();
                return out;
            } catch (RuntimeException | SQLException e) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { connection.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot claim hanging executions", e);
        }
    }

    private static List<Candidate> lockCandidates(Connection connection, long now, int limit) throws SQLException {
        String sql = "SELECT operation_id, state FROM agent_action_execution "
            + "WHERE ((state='DISPATCHED') OR (state='UNKNOWN' AND next_reconcile_at IS NULL)) "
            + "AND (recovery_claim_until IS NULL OR recovery_claim_until <= ?) "
            + "ORDER BY updated_at, operation_id LIMIT ? FOR UPDATE SKIP LOCKED";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Candidate> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Candidate(rs.getString(1), ExecutionState.valueOf(rs.getString(2))));
                }
                return out;
            }
        }
    }

    private static void claim(Connection connection, String operationId, long claimUntil) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE agent_action_execution SET recovery_claim_until=? WHERE operation_id=?")) {
            ps.setLong(1, claimUntil);
            ps.setString(2, operationId);
            ps.executeUpdate();
        }
    }

    private static int latestAttemptSeq(Connection connection, String operationId) throws SQLException {
        String sql = "SELECT COALESCE(MAX(seq_no), 0) FROM agent_action_attempt WHERE operation_id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private record Candidate(String operationId, ExecutionState state) {}
}

