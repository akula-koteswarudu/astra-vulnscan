package com.vulnscan.pipeline;

/** The stages after scanning: lessons (distill), the guidance block, evaluation and the report. */
public interface LaterStages {

    void distill(Experiment experiment);

    /** The rendered lessons block for the with-lessons arm. */
    String guidance(Experiment experiment);

    void evaluate(Experiment experiment);

    void report(Experiment experiment);
}
