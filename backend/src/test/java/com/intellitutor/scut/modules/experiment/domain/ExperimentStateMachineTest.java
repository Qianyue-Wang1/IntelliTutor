package com.intellitutor.scut.modules.experiment.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ExperimentStateMachineTest {

    @Test
    void globalStagesAreStrictlySequential() {
        GlobalStage[] stages = GlobalStage.values();
        for (int i = 0; i < stages.length - 1; i++) assertEquals(stages[i + 1], stages[i].next());
        assertNull(GlobalStage.FINISHED.next());
    }

    @ParameterizedTest
    @CsvSource({
            "WAITING_LECTURE,LECTURE", "LECTURE_IN_PROGRESS,LECTURE",
            "WAITING_PRETEST,PRETEST", "PRETEST_IN_PROGRESS,PRETEST",
            "WAITING_ROUND_1_TUTOR,ROUND_1_TUTOR", "ROUND_1_TUTOR_IN_PROGRESS,ROUND_1_TUTOR",
            "WAITING_ROUND_2_QUIZ,ROUND_2_QUIZ", "ROUND_2_QUIZ_IN_PROGRESS,ROUND_2_QUIZ",
            "WAITING_ROUND_4_TUTOR,ROUND_4_TUTOR", "ROUND_4_TUTOR_IN_PROGRESS,ROUND_4_TUTOR",
            "WAITING_ROUND_5_QUIZ,ROUND_5_QUIZ", "ROUND_5_QUIZ_IN_PROGRESS,ROUND_5_QUIZ",
            "WAITING_POSTTEST,POSTTEST", "POSTTEST_IN_PROGRESS,POSTTEST",
            "WAITING_QUESTIONNAIRE,QUESTIONNAIRE", "QUESTIONNAIRE_IN_PROGRESS,QUESTIONNAIRE",
            "EXPERIMENT_FINISHED,FINISHED"
    })
    void onlyMatchingWaitingOrInProgressStateCanEnter(StudentState state, GlobalStage stage) {
        assertTrue(state.canEnter(stage));
        for (GlobalStage other : GlobalStage.values()) {
            if (other != stage) assertFalse(state.canEnter(other), () -> state + " must not enter " + other);
        }
    }

    @Test
    void blankControlIsTheOnlyGroupThatSkipsTutor() {
        assertFalse(ExperimentGroup.BLANK_CONTROL.participatesInTutor());
        assertTrue(ExperimentGroup.DIRECT_LLM.participatesInTutor());
        assertTrue(ExperimentGroup.BASIC_TUTOR.participatesInTutor());
        assertTrue(ExperimentGroup.FULL_TUTOR.participatesInTutor());
    }

    @Test
    void onlyBasicAndFullUseStructuredTutor() {
        assertTrue(ExperimentGroup.BASIC_TUTOR.structured());
        assertTrue(ExperimentGroup.FULL_TUTOR.structured());
        assertFalse(ExperimentGroup.DIRECT_LLM.structured());
        assertFalse(ExperimentGroup.BLANK_CONTROL.structured());
    }

    @Test
    void providesWaitingAndInProgressStatesForAllFiveLearningRounds() {
        for (int round = 1; round <= 5; round++) {
            String prefix = "ROUND_" + round;
            assertDoesNotThrow(() -> StudentState.valueOf("WAITING_" + prefix + "_TUTOR"));
            assertDoesNotThrow(() -> StudentState.valueOf(prefix + "_TUTOR_IN_PROGRESS"));
            assertDoesNotThrow(() -> StudentState.valueOf("WAITING_" + prefix + "_QUIZ"));
            assertDoesNotThrow(() -> StudentState.valueOf(prefix + "_QUIZ_IN_PROGRESS"));
        }
    }
}
