package com.trade.mall.agent.execution.infrastructure;

import com.trade.mall.agent.execution.application.AttemptSequence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MySQL（数据库）版尝试序号生成器。利用 LAST_INSERT_ID(expr) 在单条 UPSERT 中原子地
 * 为每个 operationId（操作编号）分配严格递增序号，JVM 重启后不会回到 1。
 */
public final class JdbcAttemptSequence implements AttemptSequence {

    private final DataSource dataSource;

    public JdbcAttemptSequence(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public int nextSeq(String operationId) {
        String upsert = "INSERT INTO agent_action_attempt_sequence (operation_id, last_seq) "
            + "VALUES (?, LAST_INSERT_ID(1)) "
            + "ON DUPLICATE KEY UPDATE last_seq = LAST_INSERT_ID(last_seq + 1)";
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(upsert)) {
                ps.setString(1, operationId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("SELECT LAST_INSERT_ID()");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("database did not return attempt sequence");
                long seq = rs.getLong(1);
                if (seq <= 0 || seq > Integer.MAX_VALUE) {
                    throw new IllegalStateException("invalid attempt sequence for " + operationId + ": " + seq);
                }
                return (int) seq;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot allocate attempt sequence: " + operationId, e);
        }
    }
}

