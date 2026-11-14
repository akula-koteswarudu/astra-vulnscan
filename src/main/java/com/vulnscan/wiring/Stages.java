package com.vulnscan.wiring;

import com.vulnscan.config.LoadedConfig;
import com.vulnscan.pipeline.LaterStages;
import org.springframework.stereotype.Component;

/** Builds the post-scan stages (lessons, evaluation, report) for a run. */
@Component
public class Stages {

    public LaterStages later(LoadedConfig cfg) {
        return new com.vulnscan.pipeline.StandardLaterStages(new com.vulnscan.pipeline.LessonStage(),
                new com.vulnscan.pipeline.EvaluateStage(), new com.vulnscan.pipeline.ReportStage());
    }
}
