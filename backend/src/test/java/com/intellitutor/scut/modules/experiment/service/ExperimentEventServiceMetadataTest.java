package com.intellitutor.scut.modules.experiment.service;

import com.intellitutor.scut.modules.experiment.domain.StudentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExperimentEventServiceMetadataTest {

    @Test
    void recordsPretestAsAnIndependentMeasurement() {
        ExperimentEventService.EventMetadata metadata = ExperimentEventService.eventMetadata(
                StudentState.PRETEST_IN_PROGRESS, 0, "discussion", "FULL_TUTOR");

        assertAll(
                () -> assertEquals("PRETEST", metadata.stage()),
                () -> assertEquals(0, metadata.round()),
                () -> assertNull(metadata.tutorMode())
        );
    }

    @Test
    void keepsTheRoundTutorModeForItsQuiz() {
        ExperimentEventService.EventMetadata metadata = ExperimentEventService.eventMetadata(
                StudentState.ROUND_3_QUIZ_IN_PROGRESS, 3, "explanation", "FULL_TUTOR");

        assertAll(
                () -> assertEquals("ROUND_3_QUIZ", metadata.stage()),
                () -> assertEquals(3, metadata.round()),
                () -> assertEquals("explanation", metadata.tutorMode())
        );
    }

    @Test
    void recordsPosttestWithoutThePreviousTutorMode() {
        ExperimentEventService.EventMetadata metadata = ExperimentEventService.eventMetadata(
                StudentState.POSTTEST_IN_PROGRESS, 5, "explanation", "FULL_TUTOR");

        assertAll(
                () -> assertEquals("POSTTEST", metadata.stage()),
                () -> assertEquals(0, metadata.round()),
                () -> assertNull(metadata.tutorMode())
        );
    }

    @Test
    void recordsDirectLlmTeachingWithItsDirectMode() {
        ExperimentEventService.EventMetadata metadata = ExperimentEventService.eventMetadata(
                StudentState.ROUND_2_TUTOR_IN_PROGRESS, 2, "direct", "DIRECT_LLM");

        assertAll(
                () -> assertEquals("ROUND_2_TUTOR", metadata.stage()),
                () -> assertEquals(2, metadata.round()),
                () -> assertEquals("direct", metadata.tutorMode())
        );
    }

    @Test
    void recordsNoAiQuizWithTheNoAiLearningMode() {
        ExperimentEventService.EventMetadata metadata = ExperimentEventService.eventMetadata(
                StudentState.ROUND_2_QUIZ_IN_PROGRESS, 2, null, "NO_AI");

        assertAll(
                () -> assertEquals("ROUND_2_QUIZ", metadata.stage()),
                () -> assertEquals(2, metadata.round()),
                () -> assertEquals("no_ai", metadata.tutorMode())
        );
    }
}
