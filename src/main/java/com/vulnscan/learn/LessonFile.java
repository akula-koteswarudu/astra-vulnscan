package com.vulnscan.learn;

import java.util.List;

/**
 * 05_learn/lessons.json. source: "distilled" (one teacher call) or "lessons_from:&lt;path&gt;" (cross-app transfer).
 * inputFindings / inputCap describe what the distiller saw. guidance is the exact block shown to the student.
 */
public record LessonFile(int schemaVersion, String source, String promptVersion, int inputFindings, Integer inputCapFrom,
                         List<Lesson> lessons, List<String> selectedIds, String guidance, int guidanceTokens) {
}
