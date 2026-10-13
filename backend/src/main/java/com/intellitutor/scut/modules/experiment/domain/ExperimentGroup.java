package com.intellitutor.scut.modules.experiment.domain;

public enum ExperimentGroup {
    DIRECT_LLM, BASIC_TUTOR, FULL_TUTOR, BLANK_CONTROL;

    public boolean participatesInTutor() { return this != BLANK_CONTROL; }
    public boolean structured() { return this == BASIC_TUTOR || this == FULL_TUTOR; }

    public ExperimentMode studentMode() {
        return this == BLANK_CONTROL ? ExperimentMode.NO_AI : ExperimentMode.valueOf(name());
    }
}
