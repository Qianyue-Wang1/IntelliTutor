-- ============================================================
-- Phase 1: t_session & t_task 建表脚本
-- 对应 Java 实体：SessionRecord、TaskRecord
-- ============================================================
-- ------------------------------------------------------------
-- t_session：会话记录表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_session (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    session_type VARCHAR(20) NOT NULL,
    -- 'explanation' | 'discussion'
    question TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    -- active / completed / expired
    python_session_id VARCHAR(36),
    -- Python 端分配的 session_id
    state_snapshot JSONB,
    -- 最后一次同步的状态快照（用于灾后恢复）
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_accessed_at TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    -- 逻辑删除：0=正常，1=已删除
    CONSTRAINT pk_session PRIMARY KEY (id),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES t_user(id)
);
CREATE INDEX IF NOT EXISTS idx_session_user_id ON t_session(user_id);
CREATE INDEX IF NOT EXISTS idx_session_status ON t_session(status);
CREATE INDEX IF NOT EXISTS idx_session_created_at ON t_session(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_session_deleted ON t_session(deleted);
COMMENT ON TABLE t_session IS '用户学习会话记录';
COMMENT ON COLUMN t_session.id IS 'Java 生成的 UUID，前端全程使用此 ID';
COMMENT ON COLUMN t_session.user_id IS '关联 t_user.id';
COMMENT ON COLUMN t_session.session_type IS 'explanation（讲解模式）或 discussion（讨论模式）';
COMMENT ON COLUMN t_session.question IS '原始题目文本';
COMMENT ON COLUMN t_session.status IS 'active=进行中, completed=已完成, expired=已过期';
COMMENT ON COLUMN t_session.python_session_id IS 'Python FastAPI 端分配的 session_id（MQ 消费完成后回填）';
COMMENT ON COLUMN t_session.state_snapshot IS 'Python sessionstate 完整响应 data 字段，用于 Python 重启后恢复 in-memory session';
COMMENT ON COLUMN t_session.last_accessed_at IS '最近一次代理请求时间（用于会话过期判断）';
COMMENT ON COLUMN t_session.deleted IS '逻辑删除标记：0=正常，1=已删除';
-- ------------------------------------------------------------
-- t_task：异步推理任务表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_task (
    id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    task_type VARCHAR(60) NOT NULL,
    -- EXPLANATION_INIT | EXPLANATION_GETSTEP | EXPLANATION_COMMIT_ANSWER
    -- DISCUSSION_INIT  | DISCUSSION_FEEDBACK
    status VARCHAR(20) NOT NULL DEFAULT 'queued',
    -- queued / processing / success / failed
    request_body TEXT,
    -- 发给 Python 的 JSON 请求体（GET 类型为 NULL）
    result TEXT,
    -- Python 返回 JSON（成功后填入）
    error_message TEXT,
    -- 失败原因
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    CONSTRAINT pk_task PRIMARY KEY (id),
    CONSTRAINT fk_task_session FOREIGN KEY (session_id) REFERENCES t_session(id)
);
CREATE INDEX IF NOT EXISTS idx_task_session_id ON t_task(session_id);
CREATE INDEX IF NOT EXISTS idx_task_status ON t_task(status);
CREATE INDEX IF NOT EXISTS idx_task_created_at ON t_task(created_at DESC);
COMMENT ON TABLE t_task IS '异步 LLM 推理任务记录';
COMMENT ON COLUMN t_task.id IS 'Java 生成的 UUID，前端轮询时使用';
COMMENT ON COLUMN t_task.session_id IS '关联 t_session.id';
COMMENT ON COLUMN t_task.task_type IS '任务类型枚举（见字段注释）';
COMMENT ON COLUMN t_task.status IS 'queued=已入队, processing=消费中, success=成功, failed=失败';
COMMENT ON COLUMN t_task.request_body IS '发送给 Python 的请求体 JSON 快照（调试/重试用）';
COMMENT ON COLUMN t_task.result IS '来自 Python 的完整响应 JSON';
COMMENT ON COLUMN t_task.error_message IS '失败时的错误描述（消费者写入）';
COMMENT ON COLUMN t_task.completed_at IS 'MQ 消费完成时间（success/failed 均填入）';