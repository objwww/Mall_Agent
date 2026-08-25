package com.trade.mall.agent.evidence.port;

import java.util.Optional;

/**
 * `M-ADP-01`：`oms_order` 的只读端口——生产实现可以是 JDBC（数据库）只读适配器或 MyBatis Mapper，DB 账号只授
 * SELECT（`INV-BND-001`，D0 已建账号）。
 *
 * <p>方法签名约定（本包四个读端口一致）：{@code Optional.empty()} 表示"查询成功执行，
 * 确认没有这一行"（EMPTY 的原料）；任何异常（连接失败、SQL 执行错误、超时）都用
 * {@link DataSourceUnavailableException} 或任意 {@link RuntimeException} 表达，由上一层
 * 的 {@code EvidenceCollector} 统一 catch 并翻译成 {@code EvidenceResult.Unavailable}——
 * 这个接口本身不做三值封装，三值封装是 `evidence.collector` 层的职责，这里只是老老实实
 * 的一个只读 DAO 方法签名，行为上等价于一个 MyBatis Mapper 方法。</p>
 */
public interface OrderReadPort {
    Optional<OrderRecord> findByOrderSn(String orderSn);
}

