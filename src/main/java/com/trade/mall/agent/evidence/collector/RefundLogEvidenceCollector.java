package com.trade.mall.agent.evidence.collector;

import com.trade.mall.agent.evidence.port.EvidenceResult;
import com.trade.mall.agent.evidence.port.RefundLogBundle;
import com.trade.mall.agent.evidence.port.RefundLogReadPort;

import java.util.List;

/**
 * `oms_order_refund_log` 证据采集器——`sourceType = "REFUND_LOG"`，本项目"告警"证据源
 * 的落地（D5 计划步骤 1 里的"订单、退款单、售后单、**告警**"，`M-ADP-01` 12 维度卡）：
 * `CHANNEL_FAILED`/`CHANNEL_UNKNOWN` 这些 action 记录，是判定层将来定位故障根因最直接的信号。
 *
 * <p>这是 {@link RefundLogReadPort} 唯一一个底层返回裸 {@code List} 的读端口
 * （见该接口类头说明）——本类是把"裸集合"翻译成 {@link EvidenceResult} 的那道边界：
 * 空列表翻译成 {@link EvidenceResult.Empty}（确认没有日志，不是查询失败），非空列表包进
 * {@link RefundLogBundle} 后翻译成 {@link EvidenceResult.Present}。调用方从
 * {@code EvidenceCollector.collect()} 拿到的**永远不是**裸 List，这条边界只在这一个类的
 * 方法体内部短暂存在，不会泄漏给上游。</p>
 */
public final class RefundLogEvidenceCollector implements EvidenceCollector<RefundLogBundle> {

    public static final String SOURCE_TYPE = "REFUND_LOG";
    private static final String TABLE = "oms_order_refund_log";

    private final RefundLogReadPort readPort;

    public RefundLogEvidenceCollector(RefundLogReadPort readPort) { this.readPort = readPort; }

    @Override
    public String sourceType() { return SOURCE_TYPE; }

    @Override
    public EvidenceResult<RefundLogBundle> collect(String anchor) {
        try {
            List<com.trade.mall.agent.evidence.port.RefundLogRecord> rows = readPort.findByOrderSn(anchor);
            if (rows.isEmpty()) {
                return new EvidenceResult.Empty<>();
            }
            return new EvidenceResult.Present<>(new RefundLogBundle(rows));
        } catch (RuntimeException ex) {
            return new EvidenceResult.Unavailable<>(describe(ex));
        }
    }

    private static String describe(RuntimeException ex) {
        String msg = ex.getMessage();
        return TABLE + " 查询失败：" + (msg == null || msg.isBlank() ? ex.getClass().getSimpleName() : msg);
    }
}

