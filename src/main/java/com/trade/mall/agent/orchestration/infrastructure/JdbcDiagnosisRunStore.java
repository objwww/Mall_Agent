package com.trade.mall.agent.orchestration.infrastructure;

import com.trade.mall.agent.orchestration.DiagnosisRun;
import com.trade.mall.agent.orchestration.DiagnosisRunStore;

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
 * MySQL/JDBC（数据库连接标准接口）版诊断检查点存储；不引入 ORM（对象关系映射框架）。
 * 对同一个 diagnosisId 使用 SELECT ... FOR UPDATE，并拒绝用较旧 seq 覆盖较新检查点。
 */
public final class JdbcDiagnosisRunStore implements DiagnosisRunStore {

    private final DataSource dataSource;
    private final LongSupplier clock;

    public JdbcDiagnosisRunStore(DataSource dataSource, LongSupplier clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Override
    public void save(DiagnosisRun run) {
        byte[] snapshot = DiagnosisRunSerialization.encode(run);
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Integer storedSeq = lockCurrentSeq(connection, run.diagnosisId());
                if (storedSeq != null && storedSeq > run.seq()) {
                    throw new IllegalStateException(
                        "refuse stale diagnosis checkpoint: diagnosisId=" + run.diagnosisId()
                            + " storedSeq=" + storedSeq + " incomingSeq=" + run.seq());
                }
                if (storedSeq == null) insert(connection, run, snapshot);
                else update(connection, run, snapshot);
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                try { connection.rollback(); } catch (SQLException rollbackIgnored) {}
                throw e;
            } finally {
                try { connection.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot persist diagnosis checkpoint: " + run.diagnosisId(), e);
        }
    }

    @Override
    public Optional<DiagnosisRun> find(String diagnosisId) {
        String sql = "SELECT snapshot_format, snapshot_blob FROM agent_diagnosis_run WHERE diagnosis_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, diagnosisId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String format = rs.getString(1);
                if (!"JAVA_SERIAL_V1".equals(format)) {
                    throw new IllegalStateException("unsupported diagnosis checkpoint format: " + format);
                }
                DiagnosisRun run = DiagnosisRunSerialization.decode(rs.getBytes(2));
                if (!diagnosisId.equals(run.diagnosisId())) {
                    throw new IllegalStateException("checkpoint diagnosisId mismatch: expected=" + diagnosisId
                        + " actual=" + run.diagnosisId());
                }
                return Optional.of(run);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot load diagnosis checkpoint: " + diagnosisId, e);
        }
    }

    @Override
    public List<DiagnosisRun> findByState(com.trade.mall.agent.orchestration.DiagnosisState state, int limit) {
        if (limit <= 0) return List.of();
        String sql = "SELECT diagnosis_id, snapshot_format, snapshot_blob FROM agent_diagnosis_run "
            + "WHERE state = ? ORDER BY updated_at, diagnosis_id LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<DiagnosisRun> runs = new ArrayList<>();
                while (rs.next()) {
                    String diagnosisId = rs.getString(1);
                    String format = rs.getString(2);
                    if (!"JAVA_SERIAL_V1".equals(format)) {
                        throw new IllegalStateException("unsupported diagnosis checkpoint format: " + format);
                    }
                    DiagnosisRun run = DiagnosisRunSerialization.decode(rs.getBytes(3));
                    if (!diagnosisId.equals(run.diagnosisId())) {
                        throw new IllegalStateException("checkpoint diagnosisId mismatch: expected=" + diagnosisId
                            + " actual=" + run.diagnosisId());
                    }
                    runs.add(run);
                }
                return List.copyOf(runs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot scan diagnosis checkpoints by state: " + state, e);
        }
    }

    @Override
    public List<DiagnosisRun> recentTerminal(int limit) {
        if (limit <= 0) return List.of();
        String sql = "SELECT diagnosis_id, snapshot_format, snapshot_blob FROM agent_diagnosis_run "
            + "WHERE state IN ('RESOLVED','CLOSED_NO_ACTION','REJECTED','EXPIRED','ESCALATED_HUMAN') "
            + "ORDER BY updated_at DESC, diagnosis_id DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<DiagnosisRun> runs = new ArrayList<>();
                while (rs.next()) runs.add(decodeRow(rs.getString(1), rs.getString(2), rs.getBytes(3)));
                return List.copyOf(runs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot scan recent terminal diagnosis checkpoints", e);
        }
    }

    private static DiagnosisRun decodeRow(String diagnosisId, String format, byte[] bytes) {
        if (!"JAVA_SERIAL_V1".equals(format)) {
            throw new IllegalStateException("unsupported diagnosis checkpoint format: " + format);
        }
        DiagnosisRun run = DiagnosisRunSerialization.decode(bytes);
        if (!diagnosisId.equals(run.diagnosisId())) {
            throw new IllegalStateException("checkpoint diagnosisId mismatch: expected=" + diagnosisId
                + " actual=" + run.diagnosisId());
        }
        return run;
    }

    private static Integer lockCurrentSeq(Connection connection, String diagnosisId) throws SQLException {
        String sql = "SELECT seq FROM agent_diagnosis_run WHERE diagnosis_id = ? FOR UPDATE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, diagnosisId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private void insert(Connection connection, DiagnosisRun run, byte[] snapshot) throws SQLException {
        String sql = "INSERT INTO agent_diagnosis_run "
            + "(diagnosis_id, ticket_sn, state, seq, snapshot_format, snapshot_blob, updated_at) "
            + "VALUES (?, ?, ?, ?, 'JAVA_SERIAL_V1', ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, run, snapshot);
            ps.executeUpdate();
        }
    }

    private void update(Connection connection, DiagnosisRun run, byte[] snapshot) throws SQLException {
        String sql = "UPDATE agent_diagnosis_run SET ticket_sn=?, state=?, seq=?, "
            + "snapshot_format='JAVA_SERIAL_V1', snapshot_blob=?, updated_at=? WHERE diagnosis_id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, run.ticketSn());
            ps.setString(2, run.state().name());
            ps.setInt(3, run.seq());
            ps.setBytes(4, snapshot);
            ps.setLong(5, clock.getAsLong());
            ps.setString(6, run.diagnosisId());
            ps.executeUpdate();
        }
    }

    private void bind(PreparedStatement ps, DiagnosisRun run, byte[] snapshot) throws SQLException {
        ps.setString(1, run.diagnosisId());
        ps.setString(2, run.ticketSn());
        ps.setString(3, run.state().name());
        ps.setInt(4, run.seq());
        ps.setBytes(5, snapshot);
        ps.setLong(6, clock.getAsLong());
    }
}

