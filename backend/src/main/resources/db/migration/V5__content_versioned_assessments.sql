-- Formal assessment attempts are bound to an assignment's immutable content package.
-- Legacy t_assessment_* records remain available for already-configured experiments.
CREATE TABLE IF NOT EXISTS t_content_assessment_attempt (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    assignment_id VARCHAR(36) NOT NULL REFERENCES t_student_experiment_assignment(id),
    content_version VARCHAR(64) NOT NULL,
    assessment_code VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'IN_PROGRESS',
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deadline_at TIMESTAMPTZ NOT NULL,
    submitted_at TIMESTAMPTZ,
    final_score NUMERIC(12,2),
    max_score NUMERIC(12,2),
    submit_idempotency_key VARCHAR(80),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(assignment_id, assessment_code),
    CONSTRAINT chk_content_attempt_status CHECK (status IN ('IN_PROGRESS','SUBMITTED','AUTO_SUBMITTED'))
);

CREATE TABLE IF NOT EXISTS t_content_assessment_response (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    attempt_id VARCHAR(36) NOT NULL REFERENCES t_content_assessment_attempt(id),
    question_code VARCHAR(64) NOT NULL,
    answer JSONB,
    revision INTEGER NOT NULL DEFAULT 0,
    first_viewed_at TIMESTAMPTZ,
    first_answered_at TIMESTAMPTZ,
    last_answered_at TIMESTAMPTZ,
    change_count INTEGER NOT NULL DEFAULT 0,
    answer_changed BOOLEAN NOT NULL DEFAULT FALSE,
    last_idempotency_key VARCHAR(80),
    submitted_at TIMESTAMPTZ,
    duration_ms BIGINT,
    is_correct BOOLEAN,
    score NUMERIC(12,2),
    UNIQUE(attempt_id, question_code)
);

CREATE INDEX IF NOT EXISTS idx_content_assessment_attempt_deadline
    ON t_content_assessment_attempt(status, deadline_at);
CREATE INDEX IF NOT EXISTS idx_content_assessment_attempt_assignment
    ON t_content_assessment_attempt(assignment_id, assessment_code);
