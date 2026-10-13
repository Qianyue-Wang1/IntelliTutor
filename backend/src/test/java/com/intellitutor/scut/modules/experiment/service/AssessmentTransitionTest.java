package com.intellitutor.scut.modules.experiment.service;

import com.intellitutor.scut.modules.experiment.domain.GlobalStage;
import com.intellitutor.scut.modules.experiment.domain.StudentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssessmentTransitionTest {

    @Test
    void advancesThePersonalFiveRoundPathWithoutTheLegacyGroupOrQuestionnaireStops() {
        assertEquals(StudentState.WAITING_MODE_SELECTION, AssessmentService.nextStateForCompletedAssessment(GlobalStage.PRETEST));
        assertEquals(StudentState.WAITING_ROUND_4_TUTOR, AssessmentService.nextStateForCompletedAssessment(GlobalStage.ROUND_3_QUIZ));
        assertEquals(StudentState.WAITING_ROUND_5_TUTOR, AssessmentService.nextStateForCompletedAssessment(GlobalStage.ROUND_4_QUIZ));
        assertEquals(StudentState.WAITING_POSTTEST, AssessmentService.nextStateForCompletedAssessment(GlobalStage.ROUND_5_QUIZ));
        assertEquals(StudentState.WAITING_CREDENTIAL, AssessmentService.nextStateForCompletedAssessment(GlobalStage.POSTTEST));
    }

    @Test
    void resolvesFormalAssessmentsFromStoredPersonalState() {
        assertEquals(GlobalStage.PRETEST, AssessmentService.assessmentStageFor(StudentState.WAITING_PRETEST));
        assertEquals(GlobalStage.ROUND_4_QUIZ, AssessmentService.assessmentStageFor(StudentState.ROUND_4_QUIZ_IN_PROGRESS));
        assertEquals(GlobalStage.POSTTEST, AssessmentService.assessmentStageFor(StudentState.WAITING_POSTTEST));
    }

    @Test
    void keepsRoundZeroAfterThePretestAndAdvancesOnlyCompletedQuizzes() {
        assertEquals(0, ContentAssessmentService.nextRoundFor(GlobalStage.PRETEST));
        assertEquals(2, ContentAssessmentService.nextRoundFor(GlobalStage.ROUND_1_QUIZ));
        assertEquals(5, ContentAssessmentService.nextRoundFor(GlobalStage.ROUND_5_QUIZ));
        assertEquals(5, ContentAssessmentService.nextRoundFor(GlobalStage.POSTTEST));
    }
}
