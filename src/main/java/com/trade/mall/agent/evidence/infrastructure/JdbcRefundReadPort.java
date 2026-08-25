package com.trade.mall.agent.evidence.infrastructure;

import com.trade.mall.agent.evidence.port.RefundReadPort;
import com.trade.mall.agent.evidence.port.RefundRecord;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Optional;

/** oms_order_refund（退款表）的真实 JDBC（数据库）只读适配器；多退款记录时 fail-closed（失败即收紧）。 */
public final class JdbcRefundReadPort implements RefundReadPort {
    private final DataSource dataSource;
    private final int queryTimeoutSeconds;

    public JdbcRefundReadPort(DataSource dataSource, Duration queryTimeout) {
        this.dataSource = JdbcEvidenceReadSupport.requireDataSource(dataSource);
        this.queryTimeoutSeconds = JdbcEvidenceReadSupport.timeoutSeconds(queryTimeout);
    }

    @Override
    public Optional<RefundRecord> findByOrderSn(String orderSn) {
        String sql = "SELECT id, refund_sn, return_apply_id, order_sn, status, refund_amount, error_msg, finish_time "
            + "FROM oms_order_refund WHERE order_sn = ? ORDER BY id DESC LIMIT 2";
        return JdbcEvidenceReadSupport.querySingle(dataSource, queryTimeoutSeconds, "oms_order_refund", orderSn, sql,
            rs -> new RefundRecord(
                rs.getLong("id"), rs.getString("refund_sn"),
                JdbcEvidenceReadSupport.nullableLong(rs, "return_apply_id"), rs.getString("order_sn"),
                rs.getInt("status"), rs.getBigDecimal("refund_amount"), rs.getString("error_msg"),
                JdbcEvidenceReadSupport.nullableEpochMillis(rs.getTimestamp("finish_time"))));
    }
}

