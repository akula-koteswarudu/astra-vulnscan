package com.vulnscan.pipeline;

/** Post-scan stages: lessons; evaluation and report (built in later steps, plugged in here). */
public class StandardLaterStages implements LaterStages {

    private final LessonStage lessons;
    private final LaterStages rest;

    public StandardLaterStages(LessonStage lessons, LaterStages rest) {
        this.lessons = lessons;
        this.rest = rest;
    }

    @Override
    public void distill(Experiment e) {
        lessons.distill(e);
    }

    @Override
    public String guidance(Experiment e) {
        return lessons.guidance(e);
    }

    @Override
    public void evaluate(Experiment e) {
        rest.evaluate(e);
    }

    @Override
    public void report(Experiment e) {
        rest.report(e);
    }
}
