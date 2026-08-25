package com.trade.mall.agent.evidence.infrastructure;

import com.trade.mall.agent.evidence.port.OrderReadPort;
import com.trade.mall.agent.evidence.port.OrderRecord;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Optional;

/** oms_order（订单表）的真实 JDBC（数据库）只读适配器。 */
public final class JdbcOrderReadPort implements OrderReadPort {
    private final DataSource dataSource;
    private final int queryTimeoutSeconds;

    public JdbcOrderReadPort(DataSource dataSource, Duration queryTimeout) {
        this.dataSource = JdbcEvidenceReadSupport.requireDataSource(dataSource);
        this.queryTimeoutSeconds = JdbcEvidenceReadSupport.timeoutSeconds(queryTimeout);
    }

    @Override
    public Optional<OrderRecord> findByOrderSn(String orderSn) {
        String sql = "SELECT id, order_sn, status, member_username, pay_amount, create_time "
            + "FROM oms_order WHERE order_sn = ? ORDER BY id DESC LIMIT 2";
        return JdbcEvidenceReadSupport.querySingle(dataSource, queryTimeoutSeconds, "oms_order", orderSn, sql,
            rs -> new OrderRecord(
                rs.getLong("id"), rs.getString("order_sn"), rs.getInt("status"),
                rs.getString("member_username"), rs.getBigDecimal("pay_amount"),
                JdbcEvidenceReadSupport.epochMillis(rs.getTimestamp("create_time"))));
    }
}

