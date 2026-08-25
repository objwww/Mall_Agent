package com.trade.mall.agent.orchestration;

/**
 * 人工批准决定——三态，对应 `AWAITING_APPROVAL` 状态的三条出边。
 *
 * <p>真实系统里这条决定来自一个异步事件（人在批准界面点了按钮，或一个定时扫描器
 * 发现批准已过期），{@link DiagnosisOrchestrator#resumeAfterApproval} 把它建模成一次
 * 显式的方法调用——D8 的编排器是一个同步演示驱动器，不是常驻服务，"等待人的决定"
 * 这件事本来该是"进程结束，几天后一个新请求带着 approvalId 把流程接回来"，这里简化
 * 成"调用方直接告诉编排器人已经做了什么决定"，诚实的简化说明见
 * `D8-REPORT.md` §4"未做"。</p>
 */
public enum ApprovalDecision {
    GRANT,
    REJECT,
    LET_EXPIRE
}

