package com.intellitutor.scut.modules.experiment.controller;

import com.intellitutor.scut.common.results.Result;
import com.intellitutor.scut.common.utils.UserContext;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import com.intellitutor.scut.modules.experiment.service.ExperimentEventService;
import com.intellitutor.scut.modules.experiment.service.ExperimentCredentialService;
import com.intellitutor.scut.modules.experiment.service.ExperimentOrchestrationService;
import com.intellitutor.scut.modules.experiment.service.LocalParticipantExperimentService;
import com.intellitutor.scut.modules.experiment.service.NoAiLearningService;
import com.intellitutor.scut.modules.experiment.service.ParticipantExperimentExportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentExperimentControllerTest {

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    void modeSelectionUsesTheAuthenticatedStudentOnly() {
        ExperimentOrchestrationService orchestration = mock(ExperimentOrchestrationService.class);
        StudentExperimentController controller = new StudentExperimentController(orchestration, mock(ExperimentEventService.class),
                mock(NoAiLearningService.class), mock(ExperimentCredentialService.class), mock(LocalParticipantExperimentService.class), mock(ParticipantExperimentExportService.class));
        UserContext.setUserId("student-1");
        ExperimentDtos.SelectModeReq request = new ExperimentDtos.SelectModeReq("DIRECT_LLM", "command-1", 2);
        ExperimentDtos.CommandResult expected = new ExperimentDtos.CommandResult("DIRECT_LLM", "WAITING_ROUND_1_TUTOR", 1, 3,
                "/experiment/tutor/start");
        when(orchestration.selectMode("student-1", request)).thenReturn(expected);

        Result<ExperimentDtos.CommandResult> response = controller.selectMode(request);

        assertEquals(expected, response.getData());
        verify(orchestration).selectMode(eq("student-1"), eq(request));
    }

    @Test
    void learningMethodSelectionUsesTheAuthenticatedStudentOnly() {
        ExperimentOrchestrationService orchestration = mock(ExperimentOrchestrationService.class);
        StudentExperimentController controller = new StudentExperimentController(orchestration, mock(ExperimentEventService.class),
                mock(NoAiLearningService.class), mock(ExperimentCredentialService.class), mock(LocalParticipantExperimentService.class), mock(ParticipantExperimentExportService.class));
        UserContext.setUserId("student-1");
        ExperimentDtos.SelectLearningMethodReq request = new ExperimentDtos.SelectLearningMethodReq(1, "discussion", "command-2", 3);
        ExperimentDtos.CommandResult expected = new ExperimentDtos.CommandResult("BASIC_TUTOR", "ROUND_1_TUTOR_IN_PROGRESS", 1, 4,
                "/experiment/tutor/start");
        when(orchestration.selectLearningMethod("student-1", request)).thenReturn(expected);

        Result<ExperimentDtos.CommandResult> response = controller.selectLearningMethod(request);

        assertEquals(expected, response.getData());
        verify(orchestration).selectLearningMethod(eq("student-1"), eq(request));
    }

    @Test
    void noAiCompletionUsesTheAuthenticatedStudentOnly() {
        NoAiLearningService noAi = mock(NoAiLearningService.class);
        StudentExperimentController controller = new StudentExperimentController(mock(ExperimentOrchestrationService.class),
                mock(ExperimentEventService.class), noAi, mock(ExperimentCredentialService.class), mock(LocalParticipantExperimentService.class), mock(ParticipantExperimentExportService.class));
        UserContext.setUserId("student-1");
        ExperimentDtos.NoAiCompleteReq request = new ExperimentDtos.NoAiCompleteReq("complete-1");

        controller.completeNoAiLecture(request);

        verify(noAi).complete("student-1", "complete-1");
    }

    @Test
    void credentialIssuanceUsesTheAuthenticatedStudentOnly() {
        ExperimentCredentialService credentials = mock(ExperimentCredentialService.class);
        StudentExperimentController controller = new StudentExperimentController(mock(ExperimentOrchestrationService.class),
                mock(ExperimentEventService.class), mock(NoAiLearningService.class), credentials, mock(LocalParticipantExperimentService.class), mock(ParticipantExperimentExportService.class));
        UserContext.setUserId("student-1");
        ExperimentDtos.CredentialIssueReq request = new ExperimentDtos.CredentialIssueReq("issue-1");

        controller.issueCredential(request);

        verify(credentials).issue("student-1", "issue-1");
    }
}
