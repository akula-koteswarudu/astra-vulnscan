package com.vulnscan.pipeline;

import java.util.Arrays;

/** Experiment arms (plan 6.7). Student arms share units, prompt, model and parameters; only the guidance differs. */
public enum Arm {
    TEACHER("teacher"),
    STUDENT_BASELINE("student_baseline"),
    STUDENT_WITH_LESSONS("student_with_lessons");

    public final String id;

    Arm(String id) {
        this.id = id;
    }

    public boolean isStudent() {
        return this != TEACHER;
    }

    public static Arm of(String id) {
        return Arrays.stream(values()).filter(a -> a.id.equals(id)).findFirst()
                .orElseThrow(() -> new com.vulnscan.UserFacingException("unknown arm '" + id + "' (known: teacher, student_baseline, student_with_lessons)"));
    }
}
