package com.vulnscan.pipeline;

/** The stages after scanning: lessons (distill), the guidance block, evaluation and the report. */
public interface LaterStages {

    void distill(Experiment experiment);

    /** The rendered lessons block for the with-lessons arm. */
    String guidance(Experiment experiment);

    void evaluate(Experiment experiment);

    void report(Experiment experiment);

    /** Placeholder used until a stage is built: stops the run cleanly; --resume continues later. */
    LaterStages NOT_BUILT = new LaterStages() {
        @Override
        public void distill(Experiment e) {
            throw new com.vulnscan.UserFacingException("stage distill is not built yet; completed stages are saved in " + e.store().dir());
        }

        @Override
        public String guidance(Experiment e) {
            throw new com.vulnscan.UserFacingException("lessons are not built yet");
        }

        @Override
        public void evaluate(Experiment e) {
            throw new com.vulnscan.UserFacingException("stage evaluate is not built yet");
        }

        @Override
        public void report(Experiment e) {
            throw new com.vulnscan.UserFacingException("stage report is not built yet");
        }
    };
}
