package com.vulnscan.config;

import java.nio.file.Path;

/** Everything loaded for one run. configHash identifies the resolved configuration (run folder name, resume check). */
public record LoadedConfig(AppConfig app, ModelsConfig models, MatchingConfig matching, Path appFile, String configHash) {
}
