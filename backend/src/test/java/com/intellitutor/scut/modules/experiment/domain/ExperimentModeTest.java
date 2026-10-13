package com.intellitutor.scut.modules.experiment.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExperimentModeTest {

    @Test
    void acceptsExactlyTheFourStudentSelectableModes() {
        assertEquals(List.of(ExperimentMode.DIRECT_LLM, ExperimentMode.BASIC_TUTOR,
                        ExperimentMode.FULL_TUTOR, ExperimentMode.NO_AI),
                List.of(ExperimentMode.values()));
        assertEquals(ExperimentMode.NO_AI, ExperimentMode.fromCode("NO_AI"));
        assertThrows(IllegalArgumentException.class, () -> ExperimentMode.fromCode("BLANK_CONTROL"));
    }

    @Test
    void onlyFullTutorReadsCrossRoundState() {
        assertTrue(ExperimentMode.DIRECT_LLM.usesAi());
        assertTrue(ExperimentMode.BASIC_TUTOR.usesAi());
        assertTrue(ExperimentMode.FULL_TUTOR.usesAi());
        assertFalse(ExperimentMode.NO_AI.usesAi());

        assertFalse(ExperimentMode.DIRECT_LLM.readsCrossRoundState());
        assertFalse(ExperimentMode.BASIC_TUTOR.readsCrossRoundState());
        assertTrue(ExperimentMode.FULL_TUTOR.readsCrossRoundState());
        assertFalse(ExperimentMode.NO_AI.readsCrossRoundState());
    }
}
