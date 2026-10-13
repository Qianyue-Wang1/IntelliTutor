package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssessmentServiceContentRoutingTest {

    @Test
    void startsAContentVersionedAssessmentInsteadOfUsingTheLegacyQuestionTables() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExperimentOrchestrationService orchestration = mock(ExperimentOrchestrationService.class);
        ContentAssessmentService contentAssessments = mock(ContentAssessmentService.class);
        Map<String, Object> assignment = Map.of("id", "assignment-1", "content_version", "unsupervised-learning-v1",
                "student_state", "WAITING_PRETEST");
        ExperimentDtos.AssessmentVO expected = new ExperimentDtos.AssessmentVO("attempt-1", "PRETEST", "前测", "IN_PROGRESS",
                OffsetDateTime.now(), OffsetDateTime.now().plusMinutes(6), List.of(), List.of());
        when(orchestration.assignment("user-1")).thenReturn(assignment);
        when(contentAssessments.start(eq("user-1"), eq(assignment), eq(com.intellitutor.scut.modules.experiment.domain.GlobalStage.PRETEST)))
                .thenReturn(expected);
        AssessmentService service = new AssessmentService(jdbc, new ObjectMapper(), mock(ExperimentAccessService.class), orchestration,
                mock(ExperimentEventService.class), contentAssessments);

        assertSame(expected, service.start("user-1"));
        verify(contentAssessments).start("user-1", assignment, com.intellitutor.scut.modules.experiment.domain.GlobalStage.PRETEST);
    }
}
