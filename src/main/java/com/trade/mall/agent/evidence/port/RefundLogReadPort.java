package com.trade.mall.agent.evidence.port;

import java.util.List;

/**
 * `M-ADP-01`：`oms_order_refund_log`（Append-Only 执行历史）的只读端口。
 *
 * <p>与另外三个读端口的一个刻意不同：这里直接返回 {@code List<RefundLogRecord>}
 * （空列表表示"确认没有日志"，不是 {@code Optional}）——**这是允许的**，
 * {@code ARCH-EVID-001} 约束的是 `evidence.collector` 包的方法返回类型，不是
 * `evidence.port` 这一层的只读适配器；适配器可以像真实的 MyBatis Mapper 一样自然地
 * 返回裸集合，"裸集合不能直接暴露给上游判定逻辑"这条规则只在证据真正被封装成
 * {@code EvidenceResult} 之前的那一步生效——`RefundLogEvidenceCollector` 就是做这件事的地方，
 * 见该类类头对这个边界的说明。</p>
 */
public interface RefundLogReadPort {
    List<RefundLogRecord> findByOrderSn(String orderSn);
}

