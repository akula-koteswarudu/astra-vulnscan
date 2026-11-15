package com.vulnscan.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @TempDir
    Path dir;
    ConfigLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        loader = new ConfigLoader(Validation.buildDefaultValidatorFactory().getValidator(), dir);
        Files.createDirectories(dir.resolve("configs"));
        Files.copy(Path.of("configs/models.yaml"), dir.resolve("configs/models.yaml"));
        Files.copy(Path.of("configs/matching.yaml"), dir.resolve("configs/matching.yaml"));
    }

    private Path write(String name, String yaml) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, yaml);
        return p;
    }

    @Test
    void realConfigsLoad() {
        ConfigLoader repoLoader = new ConfigLoader(Validation.buildDefaultValidatorFactory().getValidator(), Path.of(""));
        LoadedConfig c = repoLoader.load(Path.of("configs/insecureshop.yaml"));
        assertThat(c.app().app().name()).isEqualTo("insecureshop");
        assertThat(c.app().runs().studentRuns()).isEqualTo(1); // deterministic core run
        assertThat(c.models().spec("teacher").temperature()).isNull();
        assertThat(c.matching().locationScores().clazz()).isEqualTo(0.8);
        assertThat(c.configHash()).hasSize(64);

        LoadedConfig example = repoLoader.load(Path.of("configs/example_new_app.yaml"));
        assertThat(example.app().app().groundTruth()).isNull();
        assertThat(example.app().planning().hopLimit()).isEqualTo(1); // from built-in defaults
    }

    @Test
    void minimalAppFileGetsDefaultsAndAssignmentProfile() throws IOException {
        LoadedConfig c = loader.load(write("a.yaml", "app: {name: x, input: apps/x.apk}\n"));
        assertThat(c.app().profile()).isEqualTo(AppConfig.Profile.ASSIGNMENT);
        assertThat(c.app().runs().teacherRuns()).isEqualTo(1);
        assertThat(c.app().arms().checklistControl()).isFalse();
        assertThat(c.app().ingest().libraryPrefixes()).contains("androidx.");
    }

    @Test
    void unknownKeyIsRejectedWithItsName() throws IOException {
        Path p = write("a.yaml", "app: {name: x, input: y}\nplanning: {hop_limt: 2}\n");
        assertThatThrownBy(() -> loader.load(p))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("unknown key 'hop_limt'");
    }

    @Test
    void invalidValueFailsValidation() throws IOException {
        Path p = write("a.yaml", "app: {name: x, input: y}\ndecompile: {timeout_s: -1}\n");
        assertThatThrownBy(() -> loader.load(p))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("decompile.timeoutS");
    }

    @Test
    void badEnumValueFails() throws IOException {
        Path p = write("a.yaml", "app: {name: x, input: y}\nplanning: {mode: sometimes}\n");
        assertThatThrownBy(() -> loader.load(p)).isInstanceOf(ConfigException.class).hasMessageContaining("planning.mode");
    }

    @Test
    void fullProfileGivesThreeStudentRunsAndLeavesTheUnbuiltChecklistArmOff() throws IOException {
        LoadedConfig c = loader.load(write("a.yaml", "profile: full\napp: {name: x, input: y}\n"));
        assertThat(c.app().runs().studentRuns()).isEqualTo(3);
        assertThat(c.app().arms().checklistControl()).isFalse();
        Path p = write("b.yaml", "profile: full\napp: {name: x, input: y}\narms: {checklist_control: true}\n");
        assertThatThrownBy(() -> loader.load(p)).hasMessageContaining("checklist_control");
    }

    @Test
    void optionsThatNothingReadsAreRejectedUnlessDefault() throws IOException {
        Path p = write("a.yaml", "app: {name: x, input: y}\nplanning: {hop_limit: 2}\n"
                + "evaluate: {count_unverified: true, audit_file: labels.csv}\n");
        assertThatThrownBy(() -> loader.load(p))
                .hasMessageContaining("hop_limit").hasMessageContaining("count_unverified").hasMessageContaining("audit_file");
    }

    @Test
    void explicitValuesWinOverProfile() throws IOException {
        Path p = write("a.yaml", "profile: full\napp: {name: x, input: y}\narms: {checklist_control: false}\nruns: {student_runs: 2}\n");
        LoadedConfig c = loader.load(p);
        assertThat(c.app().runs().studentRuns()).isEqualTo(2);
        assertThat(c.app().arms().checklistControl()).isFalse();
    }

    @Test
    void unbuiltOptionsAreRejected() throws IOException {
        Path p = write("a.yaml", "app: {name: x, input: y}\nplanning: {graph_builder: androguard}\n"
                + "distill: {sanitizer: {on_violation: rewrite}}\n");
        assertThatThrownBy(() -> loader.load(p))
                .hasMessageContaining("androguard")
                .hasMessageContaining("rewrite");
    }

    @Test
    void judgeMustDifferFromTeacherAndRolesMustExist() throws IOException {
        String models = Files.readString(dir.resolve("configs/models.yaml"))
                .replace("model: deepseek/deepseek-v4.1-flash", "model: claude-sonnet-5-5")
                .replace("roles: {teacher: teacher, student: student_pollinations, judge: judge_pollinations}",
                        "roles: {teacher: teacher, student: nobody, judge: judge_pollinations}");
        Files.writeString(dir.resolve("configs/models.yaml"), models);
        Path p = write("a.yaml", "app: {name: x, input: y}\n");
        assertThatThrownBy(() -> loader.load(p)).hasMessageContaining("undefined model 'nobody'");

        Files.writeString(dir.resolve("configs/models.yaml"), models.replace("student: nobody", "student: student_pollinations"));
        assertThatThrownBy(() -> loader.load(p)).hasMessageContaining("judge must not be the teacher");
    }

    @Test
    void appConfigCanOverrideARole() throws IOException {
        Path p = write("a.yaml", "app: {name: x, input: y}\nroles: {student: student_pollinations_sampled}\n");
        LoadedConfig c = loader.load(p);
        assertThat(c.models().roles().student()).isEqualTo("student_pollinations_sampled");
        assertThat(c.models().roles().judge()).isEqualTo("judge_pollinations");
        Path bad = write("b.yaml", "app: {name: x, input: y}\nroles: {student: nobody}\n");
        assertThatThrownBy(() -> loader.load(bad)).hasMessageContaining("undefined model 'nobody'");
    }

    @Test
    void lessonBlockMustFitReservedGuidance() throws IOException {
        Path p = write("a.yaml", "app: {name: x, input: y}\ndistill: {max_lesson_tokens: 5000}\n");
        assertThatThrownBy(() -> loader.load(p)).hasMessageContaining("reserved_guidance_tokens");
    }

    @Test
    void missingFileIsClear() {
        assertThatThrownBy(() -> loader.load(dir.resolve("nope.yaml"))).hasMessageContaining("config file not found");
    }
}
