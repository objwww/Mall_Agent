package com.trade.mall.agent.evidence.infrastructure;

import com.trade.mall.agent.evidence.port.AfterSaleReadPort;
import com.trade.mall.agent.evidence.port.AfterSaleRecord;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Optional;

/** oms_order_return_apply（售后申请表）的真实 JDBC（数据库）只读适配器；多售后记录时不猜。 */
public final class JdbcAfterSaleReadPort implements AfterSaleReadPort {
    private final DataSource dataSource;
    private final int queryTimeoutSeconds;

    public JdbcAfterSaleReadPort(DataSource dataSource, Duration queryTimeout) {
        this.dataSource = JdbcEvidenceReadSupport.requireDataSource(dataSource);
        this.queryTimeoutSeconds = JdbcEvidenceReadSupport.timeoutSeconds(queryTimeout);
    }

    @Override
    public Optional<AfterSaleRecord> findByOrderSn(String orderSn) {
        String sql = "SELECT id, order_sn, status, reason, handle_note "
            + "FROM oms_order_return_apply WHERE order_sn = ? ORDER BY id DESC LIMIT 2";
        return JdbcEvidenceReadSupport.querySingle(dataSource, queryTimeoutSeconds, "oms_order_return_apply", orderSn, sql,
            rs -> new AfterSaleRecord(
                rs.getLong("id"), rs.getString("order_sn"), rs.getInt("status"),
                rs.getString("reason"), rs.getString("handle_note")));
    }
}

