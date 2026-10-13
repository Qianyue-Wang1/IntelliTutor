-- Local participant runs are restartable. Historical attempts remain retained but are not active.
ALTER TABLE t_student_experiment_assignment DROP CONSTRAINT IF EXISTS chk_participation_status;
ALTER TABLE t_student_experiment_assignment ADD CONSTRAINT chk_participation_status
    CHECK (participation_status IN ('ACTIVE','WITHDRAWN','EXCLUDED','ABANDONED'));

ALTER TABLE t_student_experiment_assignment DROP CONSTRAINT IF EXISTS chk_assignment_registration_method;
ALTER TABLE t_student_experiment_assignment ADD CONSTRAINT chk_assignment_registration_method
    CHECK (registration_method IS NULL OR registration_method IN ('SMS_VERIFIED','INVITE_CODE','LOCAL'));

UPDATE t_experiment SET registration_policy = 'LOCAL'
WHERE code = 'machine-learning' AND status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS t_local_experiment_participant (
    assignment_id VARCHAR(36) PRIMARY KEY REFERENCES t_student_experiment_assignment(id),
    participant_name VARCHAR(120) NOT NULL,
    phone_number VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Each committed objective answer from a structured tutor is retained separately from model messages.
CREATE TABLE IF NOT EXISTS t_experiment_tutor_step_record (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    assignment_id VARCHAR(36) NOT NULL REFERENCES t_student_experiment_assignment(id),
    tutor_run_id VARCHAR(36) NOT NULL REFERENCES t_experiment_tutor_run(id),
    task_id VARCHAR(80) NOT NULL,
    round_no INTEGER NOT NULL,
    learning_mode VARCHAR(24) NOT NULL,
    step_key VARCHAR(120) NOT NULL,
    submission_type VARCHAR(24) NOT NULL,
    submitted_answer JSONB,
    correctness BOOLEAN,
    retry_count INTEGER NOT NULL DEFAULT 0,
    answered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(tutor_run_id, task_id)
);

CREATE INDEX IF NOT EXISTS idx_tutor_step_record_assignment
    ON t_experiment_tutor_step_record(assignment_id, round_no, answered_at);
