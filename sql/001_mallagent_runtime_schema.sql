-- MallAgent 独立运行时最小持久化结构（MySQL 8+）。
-- 仅包含当前 JDBC 仓储真实使用的七张表，不创建通用工作流、消息队列或空扩展表。

CREATE TABLE IF NOT EXISTS agent_diagnosis_run (
    diagnosis_id VARCHAR(128) NOT NULL COMMENT '诊断唯一编号',
    ticket_sn VARCHAR(128) NOT NULL COMMENT '工单编号',
    state VARCHAR(64) NOT NULL COMMENT '诊断状态',
    seq INT NOT NULL COMMENT '状态转移序号，禁止旧快照覆盖新快照',
    snapshot_format VARCHAR(32) NOT NULL COMMENT '快照格式，当前为JAVA_SERIAL_V1',
    snapshot_blob LONGBLOB NOT NULL COMMENT '诊断恢复检查点',
    updated_at BIGINT NOT NULL COMMENT '更新时间毫秒时间戳',
    PRIMARY KEY (diagnosis_id),
    KEY idx_agent_diagnosis_state (state, updated_at),
    KEY idx_agent_diagnosis_ticket (ticket_sn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MallAgent诊断检查点';

CREATE TABLE IF NOT EXISTS agent_event (
    event_id VARCHAR(512) NOT NULL COMMENT '业务事件幂等键',
    aggregate_id VARCHAR(191) NOT NULL COMMENT '聚合编号',
    event_type VARCHAR(128) NOT NULL COMMENT '事件类型',
    seq_no INT NOT NULL DEFAULT 0 COMMENT '事件序号',
    payload TEXT NULL COMMENT '事件负载',
    occurred_at BIGINT NOT NULL COMMENT '发生时间毫秒时间戳',
    PRIMARY KEY (event_id),
    KEY idx_agent_event_agg_time (aggregate_id, occurred_at),
    KEY idx_agent_event_agg_type_seq (aggregate_id, event_type, seq_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MallAgent领域事件账本';

CREATE TABLE IF NOT EXISTS agent_approval (
    approval_id VARCHAR(191) NOT NULL COMMENT '审批唯一编号',
    operation_id VARCHAR(191) NOT NULL COMMENT '业务操作编号',
    action_type VARCHAR(64) NOT NULL COMMENT '动作类型',
    action_version VARCHAR(64) NOT NULL COMMENT '动作版本',
    params_hash VARCHAR(128) NOT NULL COMMENT '审批参数哈希',
    expires_at BIGINT NOT NULL COMMENT '过期时间毫秒时间戳',
    state VARCHAR(32) NOT NULL COMMENT '审批状态',
    approver_id VARCHAR(191) NULL COMMENT '可信审批人编号',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at BIGINT NOT NULL COMMENT '创建时间毫秒时间戳',
    updated_at BIGINT NOT NULL COMMENT '更新时间毫秒时间戳',
    PRIMARY KEY (approval_id),
    KEY idx_agent_approval_operation (operation_id, updated_at),
    KEY idx_agent_approval_state_time (state, updated_at),
    KEY idx_agent_approval_expiry (state, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MallAgent高风险动作审批';

CREATE TABLE IF NOT EXISTS agent_action_execution (
    operation_id VARCHAR(191) NOT NULL COMMENT '稳定业务操作编号',
    state VARCHAR(32) NOT NULL COMMENT '动作执行状态',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    reconcile_count INT NOT NULL DEFAULT 0 COMMENT '对账次数',
    next_reconcile_at BIGINT NULL COMMENT '下次对账时间',
    first_unknown_at BIGINT NULL COMMENT '首次结果未知时间',
    recovery_claim_until BIGINT NULL COMMENT '恢复扫描租约到期时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间毫秒时间戳',
    PRIMARY KEY (operation_id),
    KEY idx_exec_state_next (state, next_reconcile_at),
    KEY idx_exec_recovery_claim (state, recovery_claim_until, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MallAgent资金动作执行聚合';

CREATE TABLE IF NOT EXISTS agent_action_attempt (
    operation_id VARCHAR(191) NOT NULL COMMENT '业务操作编号',
    seq_no INT NOT NULL COMMENT '物理尝试序号',
    outcome VARCHAR(32) NOT NULL COMMENT '尝试结果',
    PRIMARY KEY (operation_id, seq_no),
    KEY idx_attempt_outcome (outcome)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MallAgent动作物理尝试';

CREATE TABLE IF NOT EXISTS agent_action_attempt_sequence (
    operation_id VARCHAR(191) NOT NULL COMMENT '业务操作编号',
    last_seq INT NOT NULL COMMENT '已分配最大尝试序号',
    PRIMARY KEY (operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MallAgent尝试序号分配器';

CREATE TABLE IF NOT EXISTS agent_prompt_version (
    prompt_version VARCHAR(191) NOT NULL COMMENT '提示词不可变版本',
    prompt_text TEXT NOT NULL COMMENT '提示词正文',
    is_current TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为新诊断默认版本',
    created_at BIGINT NOT NULL COMMENT '发布时间毫秒时间戳',
    PRIMARY KEY (prompt_version),
    KEY idx_agent_prompt_current (is_current, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MallAgent提示词版本历史';

CREATE TABLE IF NOT EXISTS agent_skill_version (
    skill_version VARCHAR(191) NOT NULL COMMENT '技能指令不可变版本',
    skill_instructions TEXT NOT NULL COMMENT '实际附加到模型系统提示词的技能指令',
    is_current TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为新诊断默认版本',
    created_at BIGINT NOT NULL COMMENT '发布时间毫秒时间戳',
    PRIMARY KEY (skill_version),
    KEY idx_agent_skill_current (is_current, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MallAgent技能指令版本历史';

CREATE TABLE IF NOT EXISTS agent_nonfund_execution (
    operation_id VARCHAR(191) NOT NULL COMMENT '非资金动作幂等编号',
    action_type VARCHAR(64) NOT NULL COMMENT '动作类型',
    params_hash VARCHAR(128) NOT NULL COMMENT '参数哈希',
    state VARCHAR(32) NOT NULL COMMENT 'PENDING/SUCCEEDED/FAILED',
    updated_at BIGINT NOT NULL COMMENT '更新时间毫秒时间戳',
    PRIMARY KEY (operation_id),
    KEY idx_agent_nonfund_state_time (state, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MallAgent非资金动作执行记录';
