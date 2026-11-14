package com.vulnscan.pipeline;

/** Post-scan stages: lessons, evaluation, report. */
public class StandardLaterStages implements LaterStages {

    private final LessonStage lessons;
    private final EvaluateStage evaluate;
    private final ReportStage report;

    public StandardLaterStages(LessonStage lessons, EvaluateStage evaluate, ReportStage report) {
        this.lessons = lessons;
        this.evaluate = evaluate;
        this.report = report;
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
        evaluate.evaluate(e);
    }

    @Override
    public void report(Experiment e) {
        report.report(e);
    }
}
