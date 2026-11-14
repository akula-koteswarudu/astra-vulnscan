package com.vulnscan.learn;

/** One sanitiser hit: which lesson field contained which term, the rule that flagged it and what was done. */
public record Violation(String lessonId, String field, String term, String rule, String action) {
}
