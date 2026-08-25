package com.trade.mall.agent.config;

/**
 * KillSwitch —— M-CFG-01。总闸门：是否允许发起"花钱"的资金动作（如退款）。
 *
 * <p><b>ARCH-CFG-001：默认值写死在代码里，不是配置文件/框架的默认值。</b>
 * 如果用 {@code @Value("${money.action.allowed:false}")} 这种写法，"false" 只是
 * Spring 属性解析失败时的兜底字符串，任何人在配置中心加一行、改一个 profile，
 * 就能在不经过代码 review 的情况下把默认值悄悄改成 true。写成 Java 编译期常量，
 * 改它必须改代码、必须过 code review——这是刻意把"改变全局默认放行状态"这件事
 * 变得笨重，笨重本身就是一种安全设计。</p>
 *
 * <p><b>INV-CFG-001：读配置失败 → fail-closed（返回 false）。</b>
 * 对照 INV-CFG-002（LLM 注册表健康检查失败 → 保留旧实例，即 fail-open-to-old）：
 * 两者失败时的方向刻意相反，但遵循同一条更高层原则——出错时选择"影响面更小、
 * 后果更可预测"的一侧。KillSwitch 失败关闭 = 少做一次可能花钱的动作，风险是
 * 晚一点处理一个已经暂停的工单，可逆；LLM 注册表失败切换 = 用一个未经健康检查
 * 的新模型做判断，风险是做出不可预测的错误判断，不可逆。</p>
 */
public final class KillSwitch {

    /** ARCH-CFG-001：写死在代码里，禁止改成 @Value 的默认值。 */
    public static final boolean DEFAULT_MONEY_ACTION_ALLOWED = false;

    private final ConfigReader reader;

    public KillSwitch(ConfigReader reader) {
        this.reader = reader;
    }

    /**
     * 是否允许发起花钱动作。
     * 读配置源抛出任何异常，或返回 null（"配置项不存在"），都按 fail-closed 处理——
     * 绝不把异常透传给调用方，调用方也就不可能"忘记 catch 从而误当成允许"。
     */
    public boolean moneyActionAllowed() {
        try {
            Boolean v = reader.readMoneyActionAllowed();
            return v != null ? v : DEFAULT_MONEY_ACTION_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 配置源端口（生产实现：Nacos）。M-CFG-01 §怎么拆：读配置的 I/O 与"怎么解读失败"分离。 */
    public interface ConfigReader {
        Boolean readMoneyActionAllowed() throws Exception;
    }
}

