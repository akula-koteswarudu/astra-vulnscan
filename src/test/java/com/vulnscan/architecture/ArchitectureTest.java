package com.vulnscan.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Structural rules from the plan (section 1 rules 3-4, section 13). */
class ArchitectureTest {

    static final JavaClasses MAIN = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.vulnscan");

    @Test
    void groundTruthIsEvaluationOnly() {
        noClasses().that().resideOutsideOfPackages("..evaluate..", "..pipeline..", "..report..", "..cli..")
                .should().dependOnClassesThat().resideInAPackage("..evaluate..")
                .because("ground truth must never reach scanning, lessons or matching (plan rule 4)")
                .check(MAIN);
    }

    @Test
    void domainCodeIsFrameworkFree() {
        noClasses().that().resideInAnyPackage("..config..", "..ingest..", "..planning..", "..scan..", "..learn..", "..matching..",
                        "..evaluate..", "..report..", "..models..", "..pipeline..", "..prompts..", "..common..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("Spring is used only at the edges: wiring, CLI and the entry point (plan 13.1)")
                .check(MAIN);
    }

    @Test
    void nothingAppSpecificInCodeOrPrompts() throws IOException {
        for (Path root : List.of(Path.of("src/main"), Path.of("prompts"))) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    if (p.getFileName().toString().equals("common_words.txt")) {
                        continue; // vendored dictionary
                    }
                    String text = Files.readString(p).toLowerCase(Locale.ROOT);
                    assertThat(text).as("app name in %s", p).doesNotContain("insecureshop");
                }
            }
        }
    }
}
