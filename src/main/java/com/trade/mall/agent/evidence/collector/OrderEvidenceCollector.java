package com.trade.mall.agent.evidence.collector;

import com.trade.mall.agent.evidence.port.EvidenceResult;
import com.trade.mall.agent.evidence.port.OrderReadPort;
import com.trade.mall.agent.evidence.port.OrderRecord;

/**
 * `oms_order` 证据采集器——`sourceType = "ORDER"`。
 *
 * <p>D5 范围内锚点固定是 orderSn（`plan` D5 步骤 1 提到的三种锚点里，本采集器只用这一种；
 * refundSn/traceId 锚点由 {@code RefundEvidenceCollector}/未来的按 traceId 查询扩展负责，
 * 见 `D5-REPORT.md` §4"未做"）。
 *
 * <p>{@code catch (RuntimeException)} 这一层是 `ARCH-EVID-002`
 * （"catch 异常后不得返回空集"）的行为落点：任何从 {@link OrderReadPort} 冒出来的异常，
 * 不论是显式的 {@code DataSourceUnavailableException} 还是别的什么运行时异常，
 * 都被无条件翻译成 {@link EvidenceResult.Unavailable}，绝不会被吞掉后返回
 * {@link EvidenceResult.Empty}——`SelfCheck §45` 专门用一个"抛意外异常而不是显式断开标志"
 * 的读端口验证这条路径，不只是验证"断开"这一种最明显的失败方式。</p>
 */
public final class OrderEvidenceCollector implements EvidenceCollector<OrderRecord> {

    public static final String SOURCE_TYPE = "ORDER";
    private static final String TABLE = "oms_order";

    private final OrderReadPort readPort;

    public OrderEvidenceCollector(OrderReadPort readPort) { this.readPort = readPort; }

    @Override
    public String sourceType() { return SOURCE_TYPE; }

    @Override
    public EvidenceResult<OrderRecord> collect(String anchor) {
        try {
            return readPort.findByOrderSn(anchor)
                .<EvidenceResult<OrderRecord>>map(EvidenceResult.Present::new)
                .orElseGet(EvidenceResult.Empty::new);
        } catch (RuntimeException ex) {
            return new EvidenceResult.Unavailable<>(describe(ex));
        }
    }

    private static String describe(RuntimeException ex) {
        String msg = ex.getMessage();
        return TABLE + " 查询失败：" + (msg == null || msg.isBlank() ? ex.getClass().getSimpleName() : msg);
    }
}

