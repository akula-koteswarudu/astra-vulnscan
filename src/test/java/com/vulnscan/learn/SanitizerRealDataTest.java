package com.vulnscan.learn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.vulnscan.common.Json;
import com.vulnscan.config.AppConfig;
import com.vulnscan.config.ConfigLoader;
import com.vulnscan.config.LoadedConfig;
import com.vulnscan.pipeline.PreparedApp;
import com.vulnscan.wiring.PipelineFactory;
import jakarta.validation.Validation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Opt-in: re-sanitises the lessons saved in a run folder (-Drun=runs/...) and prints what would be flagged. No API calls. */
@Tag("integration")
class SanitizerRealDataTest {

    @Test
    void resanitiseSavedLessons() throws Exception {
        String run = System.getProperty("run");
        Assumptions.assumeTrue(run != null && Files.exists(Path.of(run, "05_learn/rejected_lessons.json")));
        LoadedConfig cfg = new ConfigLoader(Validation.buildDefaultValidatorFactory().getValidator(), Path.of(""))
                .load(Path.of("configs/insecureshop.yaml"));
        PipelineFactory f = new PipelineFactory(Path.of("").toAbsolutePath());
        PreparedApp app = f.appPreparer(cfg).prepare(f.resolve(cfg.app().app().input()), null);
        Map<String, Object> rejected = Json.json().readValue(Path.of(run, "05_learn/rejected_lessons.json").toFile(), new TypeReference<>() { });
        List<Lesson> lessons = Json.json().convertValue(rejected.get("original_lessons"), new TypeReference<List<Lesson>>() { });
        LessonSanitizer s = new LessonSanitizer(app.model(), app.code(), CommonWords.load(), cfg.app().ingest().stringsFilter().namePatterns(),
                AppConfig.OnViolation.REDACT, 0.6);
        LessonSanitizer.Result r = s.sanitize(lessons, Map.of());
        System.out.println("RESAN deny lists " + s.denyListSizes());
        System.out.println("RESAN flagged: " + r.violations().stream().map(v -> v.rule() + ":" + v.term())
                .collect(Collectors.groupingBy(x -> x, Collectors.counting())));
        System.out.println("RESAN dropped " + r.dropped().size());
    }
}
