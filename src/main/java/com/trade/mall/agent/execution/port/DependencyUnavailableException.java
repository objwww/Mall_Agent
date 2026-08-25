package com.trade.mall.agent.execution.port;

/**
 * 显式信号："这个依赖当前确定不可用，请求没有离开本进程。"
 *
 * <p>与 {@link java.net.ConnectException}/{@link java.net.UnknownHostException} 这类
 * "隐式"信号（要靠异常类型猜）不同，这个异常由适配器主动抛出，用于 mall 侧那种
 * "渠道未配置"但接口本身不报错、只是返回一段中文提示文案的场景（F-001 的根因）——
 * 适配器把"文案匹配"这一步做在自己内部并转译成显式异常，
 * {@link com.trade.mall.agent.execution.application.DependencyUnavailableClassifier}
 * 就不需要在分发器里重新解析原始文案。</p>
 */
public class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String message) { super(message); }
}

