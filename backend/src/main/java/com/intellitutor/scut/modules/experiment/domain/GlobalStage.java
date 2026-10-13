package com.intellitutor.scut.modules.experiment.domain;

import java.util.List;

public enum GlobalStage {
    SETUP,
    LECTURE,
    PRETEST,
    GROUP_ASSIGNMENT,
    ROUND_1_TUTOR,
    ROUND_1_QUIZ,
    ROUND_2_TUTOR,
    ROUND_2_QUIZ,
    ROUND_3_TUTOR,
    ROUND_3_QUIZ,
    ROUND_4_TUTOR,
    ROUND_4_QUIZ,
    ROUND_5_TUTOR,
    ROUND_5_QUIZ,
    POSTTEST,
    QUESTIONNAIRE,
    FINISHED;

    private static final List<GlobalStage> ORDER = List.of(values());

    public GlobalStage next() {
        int index = ORDER.indexOf(this);
        return index < 0 || index + 1 >= ORDER.size() ? null : ORDER.get(index + 1);
    }

    public int round() {
        String value = name();
        if (value.startsWith("ROUND_1")) return 1;
        if (value.startsWith("ROUND_2")) return 2;
        if (value.startsWith("ROUND_3")) return 3;
        if (value.startsWith("ROUND_4")) return 4;
        if (value.startsWith("ROUND_5")) return 5;
        return 0;
    }

    public boolean tutor() { return name().endsWith("_TUTOR"); }
    public boolean quiz() { return name().endsWith("_QUIZ"); }
}
