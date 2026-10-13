package com.intellitutor.scut.modules.experiment.service;

import com.intellitutor.scut.modules.experiment.domain.ExperimentGroup;
import com.intellitutor.scut.modules.experiment.domain.GlobalStage;
import com.intellitutor.scut.modules.experiment.domain.StudentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentTutorServiceFeatureFlagsTest {

    @Test
    void basicTutorKeepsCurrentSessionAdaptationWithoutLearnerState() {
        ExperimentTutorService.TutorFeatureFlags flags =
                ExperimentTutorService.tutorFeatureFlags(ExperimentGroup.BASIC_TUTOR, 3);

        assertAll(
                () -> assertFalse(flags.personalizedMode()),
                () -> assertTrue(flags.adaptiveMode()),
                () -> assertFalse(flags.crossSessionEnabled()),
                () -> assertFalse(flags.persistLearnerState())
        );
    }

    @Test
    void fullTutorRoundOneDoesNotReadCrossSessionState() {
        ExperimentTutorService.TutorFeatureFlags flags =
                ExperimentTutorService.tutorFeatureFlags(ExperimentGroup.FULL_TUTOR, 1);

        assertAll(
                () -> assertTrue(flags.personalizedMode()),
                () -> assertTrue(flags.adaptiveMode()),
                () -> assertFalse(flags.crossSessionEnabled()),
                () -> assertTrue(flags.persistLearnerState())
        );
    }

    @Test
    void fullTutorLaterRoundsEnableAllTutorFeatures() {
        ExperimentTutorService.TutorFeatureFlags flags =
                ExperimentTutorService.tutorFeatureFlags(ExperimentGroup.FULL_TUTOR, 2);

        assertAll(
                () -> assertTrue(flags.personalizedMode()),
                () -> assertTrue(flags.adaptiveMode()),
                () -> assertTrue(flags.crossSessionEnabled()),
                () -> assertTrue(flags.persistLearnerState())
        );
    }

    @Test
    void learnerStateScopeIsStableAcrossRunsInOneAssignment() {
        assertEquals(
                "experiment:experiment-1:assignment:assignment-1",
                ExperimentTutorService.learnerStateScope("experiment-1", "assignment-1")
        );
    }

    @Test
    void resolvesTutorStageFromTheStudentStateInsteadOfTheSharedExperimentStage() {
        assertEquals(GlobalStage.ROUND_4_TUTOR,
                ExperimentTutorService.tutorStage(StudentState.WAITING_ROUND_4_TUTOR));
        assertEquals(GlobalStage.ROUND_4_TUTOR,
                ExperimentTutorService.tutorStage(StudentState.ROUND_4_TUTOR_IN_PROGRESS));
    }
}
