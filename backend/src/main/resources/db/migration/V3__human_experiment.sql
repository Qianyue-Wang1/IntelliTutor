-- Human Experiment Orchestration Layer. All new timestamps are UTC-aware.
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

ALTER TABLE t_user ADD COLUMN IF NOT EXISTS login_account VARCHAR(64);
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMPTZ;
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_login_account ON t_user(login_account) WHERE deleted = 0;
ALTER TABLE t_user DROP CONSTRAINT IF EXISTS chk_user_contact;
ALTER TABLE t_user ADD CONSTRAINT chk_user_contact CHECK (
    email IS NOT NULL OR phone_number IS NOT NULL OR login_account IS NOT NULL
);

CREATE TABLE IF NOT EXISTS t_experiment (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    config_version VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    current_stage_code VARCHAR(40) NOT NULL DEFAULT 'SETUP',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT chk_experiment_status CHECK (status IN ('DRAFT','ACTIVE','FINISHED'))
);

CREATE TABLE IF NOT EXISTS t_experiment_group (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    experiment_id VARCHAR(36) NOT NULL REFERENCES t_experiment(id),
    code VARCHAR(32) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(experiment_id, code)
);

CREATE TABLE IF NOT EXISTS t_experiment_question (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    experiment_id VARCHAR(36) NOT NULL REFERENCES t_experiment(id),
    code VARCHAR(64) NOT NULL,
    question_type VARCHAR(32) NOT NULL,
    prompt TEXT NOT NULL,
    options JSONB,
    answer_key JSONB,
    numeric_tolerance NUMERIC(18,8),
    images JSONB,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(experiment_id, code)
);

CREATE TABLE IF NOT EXISTS t_assessment (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    experiment_id VARCHAR(36) NOT NULL REFERENCES t_experiment(id),
    code VARCHAR(64) NOT NULL,
    assessment_type VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL,
    duration_seconds INTEGER,
    group_code VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(experiment_id, code)
);

CREATE TABLE IF NOT EXISTS t_assessment_question (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    assessment_id VARCHAR(36) NOT NULL REFERENCES t_assessment(id),
    question_id VARCHAR(36) NOT NULL REFERENCES t_experiment_question(id),
    sequence_no INTEGER NOT NULL,
    points NUMERIC(10,2) NOT NULL DEFAULT 1,
    UNIQUE(assessment_id, question_id),
    UNIQUE(assessment_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS t_experiment_stage (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    experiment_id VARCHAR(36) NOT NULL REFERENCES t_experiment(id),
    stage_code VARCHAR(40) NOT NULL,
    stage_type VARCHAR(24) NOT NULL,
    sequence_no INTEGER NOT NULL,
    round_no INTEGER,
    duration_seconds INTEGER,
    title VARCHAR(160) NOT NULL,
    lecture_markdown TEXT,
    assessment_id VARCHAR(36) REFERENCES t_assessment(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(experiment_id, stage_code),
    UNIQUE(experiment_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS t_experiment_stage_group_config (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    stage_id VARCHAR(36) NOT NULL REFERENCES t_experiment_stage(id),
    group_code VARCHAR(32) NOT NULL,
    participating BOOLEAN NOT NULL DEFAULT TRUE,
    tutor_question_id VARCHAR(36) REFERENCES t_experiment_question(id),
    assessment_id VARCHAR(36) REFERENCES t_assessment(id),
    mode_policy VARCHAR(32),
    UNIQUE(stage_id, group_code)
);

CREATE TABLE IF NOT EXISTS t_student_experiment_assignment (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    experiment_id VARCHAR(36) NOT NULL REFERENCES t_experiment(id),
    user_id VARCHAR(36) NOT NULL REFERENCES t_user(id),
    student_id VARCHAR(64) NOT NULL,
    class_code VARCHAR(64),
    group_code VARCHAR(32),
    student_state VARCHAR(64) NOT NULL DEFAULT 'MUST_CHANGE_PASSWORD',
    password_resume_state VARCHAR(64),
    current_round INTEGER NOT NULL DEFAULT 0,
    participation_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    participation_reason TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_activity_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(experiment_id, user_id),
    UNIQUE(experiment_id, student_id),
    CONSTRAINT chk_participation_status CHECK (participation_status IN ('ACTIVE','WITHDRAWN','EXCLUDED'))
);
ALTER TABLE t_student_experiment_assignment ADD CONSTRAINT chk_experiment_group_code
    CHECK (group_code IS NULL OR group_code IN ('DIRECT_LLM','BASIC_TUTOR','FULL_TUTOR','BLANK_CONTROL'));
ALTER TABLE t_student_experiment_assignment ADD CONSTRAINT chk_experiment_round
    CHECK (current_round BETWEEN 0 AND 3);
ALTER TABLE t_student_experiment_assignment ADD CONSTRAINT chk_student_state CHECK (student_state IN (
    'MUST_CHANGE_PASSWORD','WAITING_LECTURE','LECTURE_IN_PROGRESS','WAITING_PRETEST','PRETEST_IN_PROGRESS',
    'WAITING_GROUP_ASSIGNMENT','WAITING_ROUND_1_TUTOR','ROUND_1_TUTOR_IN_PROGRESS','WAITING_ROUND_1_QUIZ','ROUND_1_QUIZ_IN_PROGRESS',
    'WAITING_ROUND_2_TUTOR','ROUND_2_TUTOR_IN_PROGRESS','WAITING_ROUND_2_QUIZ','ROUND_2_QUIZ_IN_PROGRESS',
    'WAITING_ROUND_3_TUTOR','ROUND_3_TUTOR_IN_PROGRESS','WAITING_ROUND_3_QUIZ','ROUND_3_QUIZ_IN_PROGRESS',
    'WAITING_POSTTEST','POSTTEST_IN_PROGRESS','WAITING_QUESTIONNAIRE','QUESTIONNAIRE_IN_PROGRESS','EXPERIMENT_FINISHED'
));

CREATE TABLE IF NOT EXISTS t_student_stage_progress (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    assignment_id VARCHAR(36) NOT NULL REFERENCES t_student_experiment_assignment(id),
    stage_id VARCHAR(36) NOT NULL REFERENCES t_experiment_stage(id),
    status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    started_at TIMESTAMPTZ,
    last_heartbeat_at TIMESTAMPTZ,
    active_seconds BIGINT NOT NULL DEFAULT 0,
    completed_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(assignment_id, stage_id)
);
ALTER TABLE t_student_stage_progress ADD CONSTRAINT chk_stage_progress_status
    CHECK (status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED'));

CREATE TABLE IF NOT EXISTS t_assessment_attempt (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    assignment_id VARCHAR(36) NOT NULL REFERENCES t_student_experiment_assignment(id),
    stage_id VARCHAR(36) NOT NULL REFERENCES t_experiment_stage(id),
    assessment_id VARCHAR(36) NOT NULL REFERENCES t_assessment(id),
    status VARCHAR(24) NOT NULL DEFAULT 'IN_PROGRESS',
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deadline_at TIMESTAMPTZ,
    submitted_at TIMESTAMPTZ,
    final_score NUMERIC(12,2),
    max_score NUMERIC(12,2),
    submit_idempotency_key VARCHAR(80),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(assignment_id, stage_id),
    UNIQUE(assignment_id, assessment_id)
);
ALTER TABLE t_assessment_attempt ADD CONSTRAINT chk_assessment_attempt_status
    CHECK (status IN ('IN_PROGRESS','SUBMITTED','AUTO_SUBMITTED'));

CREATE TABLE IF NOT EXISTS t_assessment_response (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    attempt_id VARCHAR(36) NOT NULL REFERENCES t_assessment_attempt(id),
    question_id VARCHAR(36) NOT NULL REFERENCES t_experiment_question(id),
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
    UNIQUE(attempt_id, question_id)
);

CREATE TABLE IF NOT EXISTS t_experiment_tutor_run (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    assignment_id VARCHAR(36) NOT NULL REFERENCES t_student_experiment_assignment(id),
    stage_id VARCHAR(36) NOT NULL REFERENCES t_experiment_stage(id),
    round_no INTEGER NOT NULL,
    group_code_snapshot VARCHAR(32) NOT NULL,
    question_id VARCHAR(36) NOT NULL REFERENCES t_experiment_question(id),
    mode VARCHAR(24),
    status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    session_id VARCHAR(36) UNIQUE REFERENCES t_session(id),
    personalization_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    personalization_context JSONB,
    learner_state_status VARCHAR(24),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(assignment_id, stage_id),
    UNIQUE(assignment_id, round_no)
);
ALTER TABLE t_experiment_tutor_run ADD CONSTRAINT chk_tutor_mode
    CHECK (mode IS NULL OR mode IN ('direct','explanation','discussion'));
ALTER TABLE t_experiment_tutor_run ADD CONSTRAINT chk_tutor_run_status
    CHECK (status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED'));
ALTER TABLE t_experiment_tutor_run ADD CONSTRAINT chk_learner_state_status
    CHECK (learner_state_status IS NULL OR learner_state_status IN ('NOT_APPLICABLE','PENDING','READY','FAILED'));

CREATE OR REPLACE FUNCTION freeze_experiment_assignment_group() RETURNS TRIGGER AS $$
DECLARE current_stage VARCHAR(40);
BEGIN
    IF OLD.group_code IS DISTINCT FROM NEW.group_code THEN
        SELECT e.current_stage_code INTO current_stage FROM t_experiment e WHERE e.id=OLD.experiment_id;
        IF current_stage NOT IN ('SETUP','LECTURE','PRETEST','GROUP_ASSIGNMENT') THEN
            RAISE EXCEPTION 'experiment group is frozen after Round 1 opens';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS trg_freeze_experiment_group ON t_student_experiment_assignment;
CREATE TRIGGER trg_freeze_experiment_group BEFORE UPDATE OF group_code ON t_student_experiment_assignment
    FOR EACH ROW EXECUTE FUNCTION freeze_experiment_assignment_group();

CREATE OR REPLACE FUNCTION freeze_experiment_tutor_mode() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.session_id IS NOT NULL AND OLD.mode IS DISTINCT FROM NEW.mode THEN
        RAISE EXCEPTION 'tutor mode is frozen after session creation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS trg_freeze_tutor_mode ON t_experiment_tutor_run;
CREATE TRIGGER trg_freeze_tutor_mode BEFORE UPDATE OF mode ON t_experiment_tutor_run
    FOR EACH ROW EXECUTE FUNCTION freeze_experiment_tutor_mode();

CREATE TABLE IF NOT EXISTS t_tutor_message (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    tutor_run_id VARCHAR(36) NOT NULL REFERENCES t_experiment_tutor_run(id),
    session_id VARCHAR(36) NOT NULL REFERENCES t_session(id),
    message_key VARCHAR(80),
    role VARCHAR(24) NOT NULL,
    message_type VARCHAR(40) NOT NULL,
    content TEXT,
    step VARCHAR(80),
    correctness BOOLEAN,
    knowledge_points JSONB,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    client_timestamp TIMESTAMPTZ,
    server_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(session_id, message_key)
);

CREATE TABLE IF NOT EXISTS t_experiment_event_log (
    event_id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    client_event_id VARCHAR(80),
    experiment_id VARCHAR(36) NOT NULL REFERENCES t_experiment(id),
    assignment_id VARCHAR(36) REFERENCES t_student_experiment_assignment(id),
    actor_user_id VARCHAR(36) REFERENCES t_user(id),
    subject_user_id VARCHAR(36) REFERENCES t_user(id),
    experiment_group VARCHAR(32),
    round_no INTEGER,
    experiment_stage VARCHAR(40) NOT NULL,
    tutor_mode VARCHAR(24),
    question_id VARCHAR(36),
    session_id VARCHAR(36),
    event_type VARCHAR(64) NOT NULL,
    event_data JSONB NOT NULL DEFAULT '{}'::JSONB,
    client_timestamp TIMESTAMPTZ,
    server_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_event_client_id
    ON t_experiment_event_log(actor_user_id, client_event_id) WHERE client_event_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_assignment_state ON t_student_experiment_assignment(experiment_id, participation_status, student_state);
CREATE INDEX IF NOT EXISTS idx_progress_heartbeat ON t_student_stage_progress(last_heartbeat_at);
CREATE INDEX IF NOT EXISTS idx_attempt_deadline ON t_assessment_attempt(status, deadline_at);
CREATE INDEX IF NOT EXISTS idx_tutor_run_assignment ON t_experiment_tutor_run(assignment_id, round_no);
CREATE INDEX IF NOT EXISTS idx_event_subject_time ON t_experiment_event_log(subject_user_id, server_timestamp);

ALTER TABLE t_session ADD COLUMN IF NOT EXISTS experiment_tutor_run_id VARCHAR(36);
ALTER TABLE t_session ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;
CREATE UNIQUE INDEX IF NOT EXISTS uk_session_experiment_tutor_run
    ON t_session(experiment_tutor_run_id) WHERE experiment_tutor_run_id IS NOT NULL;
ALTER TABLE t_task ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(80);
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_session_idempotency
    ON t_task(session_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
