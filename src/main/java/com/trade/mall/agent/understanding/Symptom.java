package com.trade.mall.agent.understanding;

/**
 * 工单里的一条可观测症状（值对象）——只记"用户说了什么现象"，不带任何判断或归因，
 * 呼应 `NG-002`/`INV-EVAL-001`"只看可观测症状"这条评测隔离防线：理解层的产出不能
 * 掺入任何只有注入了故障事实的人才写得出来的措辞。
 */
public record Symptom(String description) {
    public Symptom {
        if (description == null || description.isBlank()) throw new IllegalArgumentException("description must not be blank");
    }
}

