UPDATE t_experiment
SET registration_policy = 'SMS_OR_INVITE'
WHERE code = 'machine-learning' AND registration_policy = 'SMS_VERIFIED';
