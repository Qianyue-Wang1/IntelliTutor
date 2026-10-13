-- New local machine-learning attempts begin at condition selection. Keep legacy pretest
-- states and historical answers intact; only unfinished local attempts are moved forward.
UPDATE t_student_experiment_assignment
SET student_state = 'WAITING_MODE_SELECTION',
    content_version = 'unsupervised-learning-v2',
    state_version = state_version + 1,
    updated_at = NOW(),
    last_activity_at = NOW()
WHERE registration_method = 'LOCAL'
  AND participation_status = 'ACTIVE'
  AND student_state IN ('WAITING_PRETEST', 'PRETEST_IN_PROGRESS');

UPDATE t_experiment
SET content_version = 'unsupervised-learning-v2',
    config_version = 'unsupervised-learning-v2',
    updated_at = NOW()
WHERE code = 'machine-learning'
  AND status = 'ACTIVE';
