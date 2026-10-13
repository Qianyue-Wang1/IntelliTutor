-- v2 was initially published with abbreviated No-AI lectures. Restore the
-- approved lecture text from the immutable v1 package without touching items,
-- answers, attempts, or participant state.
UPDATE t_experiment_content_version AS target
SET package_json = jsonb_set(
        jsonb_set(
                jsonb_set(
                        jsonb_set(
                                jsonb_set(target.package_json,
                                        '{noAiLectures,M1}', source.package_json #> '{noAiLectures,M1}'),
                                '{noAiLectures,M2}', source.package_json #> '{noAiLectures,M2}'),
                        '{noAiLectures,M3}', source.package_json #> '{noAiLectures,M3}'),
                '{noAiLectures,M4}', source.package_json #> '{noAiLectures,M4}'),
        '{noAiLectures,M5}', source.package_json #> '{noAiLectures,M5}')
FROM t_experiment_content_version AS source
WHERE target.experiment_id = source.experiment_id
  AND target.version = 'unsupervised-learning-v2'
  AND source.version = 'unsupervised-learning-v1';
