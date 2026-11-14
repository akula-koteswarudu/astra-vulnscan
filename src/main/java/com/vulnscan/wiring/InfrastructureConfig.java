package com.vulnscan.wiring;

import com.vulnscan.config.ConfigLoader;
import jakarta.validation.Validator;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Stateless infrastructure beans. Everything run-specific is built by {@link PipelineFactory}. */
@Configuration
public class InfrastructureConfig {

    /** All relative paths in configs are resolved against the working directory (the repo root). */
    @Bean
    public Path baseDir() {
        return Path.of("").toAbsolutePath();
    }

    @Bean
    public ConfigLoader configLoader(Validator validator, Path baseDir) {
        return new ConfigLoader(validator, baseDir);
    }
}
