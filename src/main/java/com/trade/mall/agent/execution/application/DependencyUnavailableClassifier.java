package com.trade.mall.agent.execution.application;

import com.trade.mall.agent.execution.port.DependencyUnavailableException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * DependencyUnavailableClassifier —— 把"请求确定从未离开进程"的信号从一大堆异常
 * 里挑出来，从而支持 T13（DISPATCHED --DEPENDENCY_UNAVAILABLE--> BLOCKED），
 * 修复 F-001：{@code WechatRefundChannel.java:98} 把"未配置该退款渠道"误判为
 * UNKNOWN 的缺陷（未配置其实是 100% 确定没发生，不该背上"可能有副作用"的不确定性）。
 *
 * <p><b>为什么单独一个类，而不是散在各 catch 块里：</b></p>
 * <ul>
 *   <li>mall 侧的"未配置"信号是脆弱的字符串匹配（渠道层没有区分错误码，只在异常/返回值
 *       里塞了一句中文提示），这种耦合迟早要腐化调用它的每一处代码——集中到一个类里，
 *       改动、测试都只碰这一个文件，是 ADR-011"把脆弱耦合关进笼子里，而不是假装能消灭它"
 *       的直接应用。</li>
 *   <li>可以独立、详尽地做单元测试，把 mall 侧目前已知的所有文案变体都固化成用例，
 *       未来 mall 侧改文案，测试立刻能感知（这也是它必须与真实文案保持同步的唯一手段）。</li>
 * </ul>
 *
 * <p><b>红线：绝不能把"超时"的任何变体收进来。</b> TIMEOUT 与 T13 的
 * DEPENDENCY_UNAVAILABLE 在语义上是对立的——前者"说不清"，后者"确定没发生"。
 * 如果哪天有人把 {@code SocketTimeoutException} 加进 {@code NEVER_SENT}，
 * 这个类就会把不确定性伪装成确定性，直接违反 INV-UNK-001 的精神。</p>
 */
public final class DependencyUnavailableClassifier {

    /** 异常类型本身就意味着"连接都没建立起来"——TCP 层面确定无请求发出。 */
    private static final List<Class<? extends Throwable>> NEVER_SENT = List.of(
        ConnectException.class,
        UnknownHostException.class,
        DependencyUnavailableException.class
    );

    /** mall 侧"未配置"文案的已知变体（F-001）。小写包含匹配。 */
    private static final List<String> NOT_CONFIGURED_MARKERS = List.of(
        "未配置", "not configured", "missing config", "channel not configured", "渠道未开通"
    );

    private DependencyUnavailableClassifier() {}

    /** 沿 cause 链最多回溯的层数，防止病态的自引用/超深链条导致死循环。 */
    private static final int MAX_CAUSE_DEPTH = 8;

    /**
     * 该异常是否意味着"请求确定没有离开本进程"（→ 触发 T13，映射到 BLOCKED，而非 UNKNOWN）。
     *
     * <p>沿 {@code getCause()} 链逐层判断，而不是只看最外层异常：ActionPort 的真实实现
     * （见 {@code HttpMallRefundActionPort}）会把底层的 {@link ConnectException}/
     * {@link UnknownHostException} 包一层 {@code RuntimeException("HTTP call failed...", e)}
     * 再抛出（ActionPort.execute() 的契约里没有 checked exception，适配器必须自己转换），
     * 只看最外层会永远命中不了 NEVER_SENT，白白让这个类的存在意义落空。</p>
     */
    public static boolean isDependencyUnavailable(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < MAX_CAUSE_DEPTH) {
            for (Class<? extends Throwable> c : NEVER_SENT) {
                if (c.isInstance(cur)) return true;
            }
            if (isNotConfiguredMessage(cur.getMessage())) return true;
            cur = cur.getCause();
        }
        return false;
    }

    /** F-001 专用：mall 把"未配置"伪装成一段提示文案时的识别逻辑，单独暴露以便直接测试。 */
    public static boolean isNotConfiguredMessage(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        for (String marker : NOT_CONFIGURED_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}

