-- Configurable five-round human experiment. All changes are additive and keep V3 records readable.

ALTER TABLE t_experiment ADD COLUMN IF NOT EXISTS registration_policy VARCHAR(24) NOT NULL DEFAULT 'SMS_VERIFIED';
ALTER TABLE t_experiment ADD COLUMN IF NOT EXISTS content_version VARCHAR(64);

ALTER TABLE t_student_experiment_assignment ADD COLUMN IF NOT EXISTS content_version VARCHAR(64);
ALTER TABLE t_student_experiment_assignment ADD COLUMN IF NOT EXISTS registration_method VARCHAR(24);
ALTER TABLE t_student_experiment_assignment ADD COLUMN IF NOT EXISTS selected_mode VARCHAR(24);
ALTER TABLE t_student_experiment_assignment ADD COLUMN IF NOT EXISTS mode_selected_at TIMESTAMPTZ;
ALTER TABLE t_student_experiment_assignment ADD COLUMN IF NOT EXISTS current_learning_method VARCHAR(24);
ALTER TABLE t_student_experiment_assignment ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE t_student_experiment_assignment DROP CONSTRAINT IF EXISTS chk_experiment_group_code;
ALTER TABLE t_student_experiment_assignment ADD CONSTRAINT chk_experiment_group_code
    CHECK (group_code IS NULL OR group_code IN ('DIRECT_LLM','BASIC_TUTOR','FULL_TUTOR','BLANK_CONTROL','NO_AI'));
ALTER TABLE t_student_experiment_assignment DROP CONSTRAINT IF EXISTS chk_experiment_round;
ALTER TABLE t_student_experiment_assignment ADD CONSTRAINT chk_experiment_round
    CHECK (current_round BETWEEN 0 AND 5);
ALTER TABLE t_student_experiment_assignment DROP CONSTRAINT IF EXISTS chk_student_state;
ALTER TABLE t_student_experiment_assignment ADD CONSTRAINT chk_student_state CHECK (student_state IN (
    'MUST_CHANGE_PASSWORD','WAITING_LECTURE','LECTURE_IN_PROGRESS','WAITING_PRETEST','PRETEST_IN_PROGRESS',
    'WAITING_GROUP_ASSIGNMENT','WAITING_MODE_SELECTION',
    'WAITING_ROUND_1_TUTOR','ROUND_1_TUTOR_IN_PROGRESS','WAITING_ROUND_1_QUIZ','ROUND_1_QUIZ_IN_PROGRESS',
    'WAITING_ROUND_2_TUTOR','ROUND_2_TUTOR_IN_PROGRESS','WAITING_ROUND_2_QUIZ','ROUND_2_QUIZ_IN_PROGRESS',
    'WAITING_ROUND_3_TUTOR','ROUND_3_TUTOR_IN_PROGRESS','WAITING_ROUND_3_QUIZ','ROUND_3_QUIZ_IN_PROGRESS',
    'WAITING_ROUND_4_TUTOR','ROUND_4_TUTOR_IN_PROGRESS','WAITING_ROUND_4_QUIZ','ROUND_4_QUIZ_IN_PROGRESS',
    'WAITING_ROUND_5_TUTOR','ROUND_5_TUTOR_IN_PROGRESS','WAITING_ROUND_5_QUIZ','ROUND_5_QUIZ_IN_PROGRESS',
    'WAITING_POSTTEST','POSTTEST_IN_PROGRESS','WAITING_QUESTIONNAIRE','QUESTIONNAIRE_IN_PROGRESS',
    'WAITING_CREDENTIAL','EXPERIMENT_FINISHED'
));
ALTER TABLE t_student_experiment_assignment ADD CONSTRAINT chk_assignment_registration_method
    CHECK (registration_method IS NULL OR registration_method IN ('SMS_VERIFIED','INVITE_CODE'));
ALTER TABLE t_student_experiment_assignment ADD CONSTRAINT chk_assignment_selected_mode
    CHECK (selected_mode IS NULL OR selected_mode IN ('DIRECT_LLM','BASIC_TUTOR','FULL_TUTOR','NO_AI'));
ALTER TABLE t_student_experiment_assignment ADD CONSTRAINT chk_assignment_learning_method
    CHECK (current_learning_method IS NULL OR current_learning_method IN ('explanation','discussion'));

ALTER TABLE t_experiment_tutor_run ADD COLUMN IF NOT EXISTS selected_mode_snapshot VARCHAR(24);
ALTER TABLE t_experiment_tutor_run ADD COLUMN IF NOT EXISTS interaction_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE t_experiment_tutor_run ADD COLUMN IF NOT EXISTS completion_reason VARCHAR(32);
ALTER TABLE t_experiment_tutor_run ADD CONSTRAINT chk_tutor_selected_mode
    CHECK (selected_mode_snapshot IS NULL OR selected_mode_snapshot IN ('DIRECT_LLM','BASIC_TUTOR','FULL_TUTOR','NO_AI'));

CREATE TABLE IF NOT EXISTS t_experiment_content_version (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    experiment_id VARCHAR(36) NOT NULL REFERENCES t_experiment(id),
    version VARCHAR(64) NOT NULL,
    package_json JSONB NOT NULL,
    model_version VARCHAR(128),
    prompt_version VARCHAR(128),
    published_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_by VARCHAR(36) REFERENCES t_user(id),
    UNIQUE(experiment_id, version)
);

CREATE TABLE IF NOT EXISTS t_experiment_invite_code (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    experiment_id VARCHAR(36) NOT NULL REFERENCES t_experiment(id),
    code_hash VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    max_uses INTEGER,
    used_count INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(experiment_id, code_hash),
    CONSTRAINT chk_invite_max_uses CHECK (max_uses IS NULL OR max_uses > 0),
    CONSTRAINT chk_invite_used_count CHECK (used_count >= 0)
);

CREATE TABLE IF NOT EXISTS t_experiment_command (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    assignment_id VARCHAR(36) NOT NULL REFERENCES t_student_experiment_assignment(id),
    command_type VARCHAR(48) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    response_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(assignment_id, command_type, idempotency_key)
);

CREATE TABLE IF NOT EXISTS t_no_ai_learning_run (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    assignment_id VARCHAR(36) NOT NULL REFERENCES t_student_experiment_assignment(id),
    round_no INTEGER NOT NULL,
    content_version VARCHAR(64) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_activity_at TIMESTAMPTZ,
    bottom_reached_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    max_scroll_percent NUMERIC(5,2) NOT NULL DEFAULT 0,
    completion_idempotency_key VARCHAR(80),
    UNIQUE(assignment_id, round_no),
    CONSTRAINT chk_no_ai_round CHECK (round_no BETWEEN 1 AND 5),
    CONSTRAINT chk_no_ai_scroll CHECK (max_scroll_percent BETWEEN 0 AND 100)
);

CREATE TABLE IF NOT EXISTS t_experiment_credential (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    assignment_id VARCHAR(36) NOT NULL UNIQUE REFERENCES t_student_experiment_assignment(id),
    credential_no VARCHAR(80) NOT NULL UNIQUE,
    participant_name VARCHAR(120) NOT NULL,
    participant_code VARCHAR(80) NOT NULL,
    topic VARCHAR(160) NOT NULL,
    selected_mode VARCHAR(24) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    issue_idempotency_key VARCHAR(80) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS t_experiment_export_audit (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    experiment_id VARCHAR(36) NOT NULL REFERENCES t_experiment(id),
    admin_user_id VARCHAR(36) NOT NULL REFERENCES t_user(id),
    export_format VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_assignment_personal_state
    ON t_student_experiment_assignment(experiment_id, participation_status, selected_mode, student_state);
CREATE INDEX IF NOT EXISTS idx_experiment_command_assignment
    ON t_experiment_command(assignment_id, command_type);
CREATE INDEX IF NOT EXISTS idx_invite_lookup
    ON t_experiment_invite_code(experiment_id, code_hash) WHERE enabled;

CREATE OR REPLACE FUNCTION freeze_experiment_assignment_group() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.group_code IS DISTINCT FROM NEW.group_code AND OLD.selected_mode IS NOT NULL THEN
        RAISE EXCEPTION 'experiment group is frozen after student mode selection';
    END IF;
    IF OLD.selected_mode IS NOT NULL AND OLD.selected_mode IS DISTINCT FROM NEW.selected_mode THEN
        RAISE EXCEPTION 'student experiment mode is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS trg_freeze_experiment_group ON t_student_experiment_assignment;
CREATE TRIGGER trg_freeze_experiment_group BEFORE UPDATE OF group_code, selected_mode ON t_student_experiment_assignment
    FOR EACH ROW EXECUTE FUNCTION freeze_experiment_assignment_group();
