package com.intellitutor.scut.modules.experiment.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentSchemaMigrationTest {

    @Test
    void v4MigrationKeepsExistingAssignmentsAndAddsFiveRoundPersonalStateColumns() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V4__configurable_human_experiment.sql"));

        assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS content_version"));
        assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS selected_mode"));
        assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS state_version"));
        assertTrue(migration.contains("current_round BETWEEN 0 AND 5"));
        assertTrue(migration.contains("'NO_AI'"));
        assertTrue(migration.contains("'WAITING_ROUND_5_TUTOR'"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS t_experiment_command"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS t_experiment_credential"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS t_experiment_export_audit"));
        assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS"));
        assertTrue(!migration.contains("DROP TABLE"));
    }

    @Test
    void v5MigrationStoresAttemptsAgainstTheAssignmentLockedContentVersion() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V5__content_versioned_assessments.sql"));

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS t_content_assessment_attempt"));
        assertTrue(migration.contains("content_version VARCHAR(64) NOT NULL"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS t_content_assessment_response"));
        assertTrue(migration.contains("UNIQUE(assignment_id, assessment_code)"));
        assertTrue(!migration.contains("DROP TABLE"));
    }

    @Test
    void v7MigrationPreservesAbandonedAttemptsAndAddsStructuredTutorStepRecords() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V7__local_participant_experiment.sql"));

        assertTrue(migration.contains("'ABANDONED'"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS t_experiment_tutor_step_record"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS t_local_experiment_participant"));
        assertTrue(migration.contains("assignment_id VARCHAR(36) NOT NULL"));
        assertTrue(migration.contains("tutor_run_id VARCHAR(36) NOT NULL"));
        assertTrue(migration.contains("submitted_answer JSONB"));
        assertTrue(migration.contains("correctness BOOLEAN"));
        assertTrue(migration.contains("retry_count INTEGER NOT NULL"));
        assertTrue(!migration.contains("DROP TABLE"));
    }

    @Test
    void v9MigrationRestoresTheApprovedNoAiLecturesWithoutChangingAssessmentData() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V9__restore_v2_no_ai_lectures.sql"));

        assertTrue(migration.contains("jsonb_set"));
        assertTrue(migration.contains("{noAiLectures,M1}"));
        assertTrue(migration.contains("{noAiLectures,M5}"));
        assertTrue(!migration.contains("t_content_assessment"));
        assertTrue(!migration.contains("DELETE"));
    }
}
