package com.intellitutor.scut.modules.experiment.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

public record ExperimentContextVO(
        boolean experimentMode,
        boolean assigned,
        String experimentId,
        String experimentName,
        String globalStage,
        String studentState,
        String participationStatus,
        String groupCode,
        Integer round,
        String roundTopic,
        String tutorMode,
        String sessionId,
        String attemptId,
        OffsetDateTime deadline,
        long stateVersion,
        String nextRoute,
        List<String> allowedActions,
        String waitingMessage,
        JsonNode stageContent,
        OffsetDateTime serverTime) {
}
