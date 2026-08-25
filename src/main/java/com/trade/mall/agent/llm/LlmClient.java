package com.trade.mall.agent.llm;

import java.time.Duration;

/**
 * LlmClient —— 一个具体模型部署的客户端（`M-LLM-01` §2）。
 *
 * <p>方法契约（`architecture_rules.md`/`M-LLM-01-llm-registry.md` §2 原样照抄，
 * 不是本项目自己发明的接口）：{@link #complete} 抛 {@link LlmUnavailableException}
 * 表示请求根本没发出去（可安全换实例/等恢复重试）；抛 {@link LlmTimeoutException}
 * 表示请求发出了但没等到应答——**LLM 调用无副作用**，这是 D2 起反复强调的"发钱的调用
 * 超时不能重试，LLM 调用超时可以直接重试"这条分水岭在类型层面的落点
 * （`M-LLM-01` §1.3）。</p>
 */
public interface LlmClient {

    String modelId();

    /**
     * @throws LlmUnavailableException 请求未发出（可重试：换实例/等恢复）
     * @throws LlmTimeoutException     请求发出但无应答（无副作用，可直接重试）
     * @throws LlmQuotaException       请求发出但被限流拒绝（可退避重试）
     */
    LlmResponse complete(LlmRequest request);

    /** 健康探测：一次极小的真实请求。 */
    boolean healthy();

    /** 优雅关闭：等待在途请求完成后释放连接池。 */
    void shutdown(Duration grace);
}

