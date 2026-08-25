package com.trade.mall.agent.evidence.infrastructure;

import com.trade.mall.agent.evidence.port.DataSourceUnavailableException;
import com.trade.mall.agent.evidence.port.RefundLogReadPort;
import com.trade.mall.agent.evidence.port.RefundLogRecord;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** oms_order_refund_log（退款执行日志表）的真实 JDBC（数据库）只读适配器。 */
public final class JdbcRefundLogReadPort implements RefundLogReadPort {
    static final int MAX_ROWS = 500;

    private final DataSource dataSource;
    private final int queryTimeoutSeconds;

    public JdbcRefundLogReadPort(DataSource dataSource, Duration queryTimeout) {
        this.dataSource = JdbcEvidenceReadSupport.requireDataSource(dataSource);
        this.queryTimeoutSeconds = JdbcEvidenceReadSupport.timeoutSeconds(queryTimeout);
    }

    @Override
    public List<RefundLogRecord> findByOrderSn(String orderSn) {
        JdbcEvidenceReadSupport.requireOrderSn(orderSn);
        String sql = "SELECT id, refund_sn, order_sn, action, channel_code, success, error_code, error_msg, trace_id, create_time "
            + "FROM oms_order_refund_log WHERE order_sn = ? ORDER BY id ASC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            JdbcEvidenceReadSupport.configureReadOnlyStatement(connection, ps, queryTimeoutSeconds);
            ps.setString(1, orderSn);
            ps.setInt(2, MAX_ROWS + 1);
            try (ResultSet rs = ps.executeQuery()) {
                List<RefundLogRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    if (rows.size() == MAX_ROWS) {
                        throw new DataSourceUnavailableException(
                            "oms_order_refund_log rows exceed safe limit " + MAX_ROWS
                                + " for orderSn=" + orderSn + "; refusing truncated evidence");
                    }
                    rows.add(new RefundLogRecord(
                        rs.getLong("id"), rs.getString("refund_sn"), rs.getString("order_sn"),
                        rs.getString("action"), rs.getString("channel_code"), rs.getInt("success") != 0,
                        rs.getString("error_code"), rs.getString("error_msg"), rs.getString("trace_id"),
                        JdbcEvidenceReadSupport.epochMillis(rs.getTimestamp("create_time"))));
                }
                return List.copyOf(rows);
            }
        } catch (DataSourceUnavailableException e) {
            throw e;
        } catch (SQLException e) {
            throw JdbcEvidenceReadSupport.unavailable("oms_order_refund_log", orderSn, e);
        }
    }
}

