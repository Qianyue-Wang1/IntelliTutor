package com.intellitutor.scut.modules.experiment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellitutor.scut.modules.experiment.dto.ExperimentDtos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ContentAssessmentServiceTest {

    @Test
    void mapsLockedContentItemsToStudentQuestionsWithoutAnAnswerKeyField() {
        ExperimentContentService content = new ExperimentContentService(new ObjectMapper());
        JsonNode packageJson = content.readClasspathPackage("experiments/unsupervised-learning-v1.json");

        List<ExperimentDtos.QuestionVO> questions = ContentAssessmentService.studentQuestions(
                content.assessmentDefinition(packageJson, "Q2"));

        assertEquals(3, questions.size());
        assertEquals("Q2.1", questions.get(0).id());
        assertEquals("Q2.1", questions.get(0).code());
        assertEquals(4, questions.get(0).options().size());
        assertFalse(Arrays.stream(ExperimentDtos.QuestionVO.class.getRecordComponents())
                .anyMatch(component -> component.getName().toLowerCase().contains("answer")));
    }
}
