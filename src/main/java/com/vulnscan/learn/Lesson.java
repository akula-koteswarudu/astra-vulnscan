package com.vulnscan.learn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A general lesson distilled from the teacher's findings. sourceFindingIds are for audit only and are never shown
 * to the student.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Lesson(String id, String title, AppliesTo appliesTo, List<String> signals, String whyExploitable,
                     List<String> falsePositiveGuards, String cwe, String owaspCategory, String severityGuidance,
                     String abstractExample, List<String> sourceFindingIds) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AppliesTo(List<String> componentTypes, List<String> apis) {
    }

    public Lesson withId(String newId) {
        return new Lesson(newId, title, appliesTo, signals, whyExploitable, falsePositiveGuards, cwe, owaspCategory,
                severityGuidance, abstractExample, sourceFindingIds);
    }

    public Lesson withoutExample() {
        return new Lesson(id, title, appliesTo, signals, whyExploitable, falsePositiveGuards, cwe, owaspCategory,
                severityGuidance, null, sourceFindingIds);
    }
}
