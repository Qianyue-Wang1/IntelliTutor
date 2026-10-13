package com.intellitutor.scut.modules.experiment.service;

import com.intellitutor.scut.modules.experiment.domain.GlobalStage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentAdminServiceStageSequenceTest {

    @Test
    void acceptsTheExplicitLegacyThreeRoundSequenceAndCurrentFiveRoundSequence() {
        List<String> legacy = List.of(
                "SETUP", "LECTURE", "PRETEST", "GROUP_ASSIGNMENT",
                "ROUND_1_TUTOR", "ROUND_1_QUIZ", "ROUND_2_TUTOR", "ROUND_2_QUIZ",
                "ROUND_3_TUTOR", "ROUND_3_QUIZ", "POSTTEST", "QUESTIONNAIRE", "FINISHED");
        List<String> current = Arrays.stream(GlobalStage.values()).map(Enum::name).toList();

        assertTrue(ExperimentAdminService.isSupportedStageSequence(legacy));
        assertTrue(ExperimentAdminService.isSupportedStageSequence(current));
    }

    @Test
    void rejectsMixedOrIncompleteSequences() {
        assertFalse(ExperimentAdminService.isSupportedStageSequence(List.of(
                "SETUP", "LECTURE", "PRETEST", "ROUND_1_TUTOR", "POSTTEST", "FINISHED")));
    }
}
