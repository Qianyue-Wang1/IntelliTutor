package com.intellitutor.scut.modules.experiment.domain;

public enum ExperimentMode {
    DIRECT_LLM,
    BASIC_TUTOR,
    FULL_TUTOR,
    NO_AI;

    public static ExperimentMode fromCode(String code) {
        return valueOf(code);
    }

    public boolean usesAi() {
        return this != NO_AI;
    }

    public boolean structuredTutor() {
        return this == BASIC_TUTOR || this == FULL_TUTOR;
    }

    public boolean readsCrossRoundState() {
        return this == FULL_TUTOR;
    }
}
