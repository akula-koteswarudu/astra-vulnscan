package com.vulnscan.pipeline;

import com.vulnscan.UserFacingException;
import com.vulnscan.common.Files2;
import com.vulnscan.common.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One run folder: runs/&lt;YYYY-MM-DDTHHMM&gt;_&lt;app&gt;_&lt;confighash8&gt;/. Every artefact is written atomically (temp file +
 * rename). A stage is complete when its _DONE marker exists; --resume skips completed stages and refuses to continue
 * a run made with a different configuration.
 */
public class RunStore {

    static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm").withZone(ZoneOffset.UTC);
    static final String MARKERS = "_DONE";

    private final Path dir;

    private RunStore(Path dir) {
        this.dir = dir;
    }

    public static RunStore create(Path runsDir, String appName, String configHash, Instant now) {
        String base = NAME_TIME.format(now) + "_" + appName + "_" + configHash.substring(0, 8);
        Path dir = runsDir.resolve(base);
        for (int i = 2; Files.exists(dir); i++) {
            dir = runsDir.resolve(base + "-" + i);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create run folder " + dir, e);
        }
        return new RunStore(dir);
    }

    public static RunStore resume(Path dir, String configHash) {
        Path manifest = dir.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            throw new UserFacingException("not a run folder (no manifest.json): " + dir);
        }
        String recorded = Files2.readJson(manifest, Map.class).getOrDefault("config_hash", "").toString();
        if (!recorded.equals(configHash)) {
            throw new UserFacingException("refusing to resume " + dir + ": it was made with a different configuration (config hash "
                    + recorded.substring(0, Math.min(8, recorded.length())) + " vs current " + configHash.substring(0, 8)
                    + "). Start a new run instead.");
        }
        return new RunStore(dir);
    }

    public Path dir() {
        return dir;
    }

    public String runId() {
        return dir.getFileName().toString();
    }

    public Path path(String relative) {
        return dir.resolve(relative);
    }

    public void writeJson(String relative, Object value) {
        Files2.writeJson(dir.resolve(relative), value);
    }

    public void writeText(String relative, String text) {
        Files2.writeAtomic(dir.resolve(relative), text);
    }

    public <T> T readJson(String relative, Class<T> type) {
        return Files2.readJson(dir.resolve(relative), type);
    }

    public boolean exists(String relative) {
        return Files.exists(dir.resolve(relative));
    }

    public boolean isDone(String stage) {
        return Files.exists(dir.resolve(MARKERS).resolve(markerName(stage)));
    }

    public void markDone(String stage) {
        Files2.writeAtomic(dir.resolve(MARKERS).resolve(markerName(stage)), Instant.now().toString() + "\n");
    }

    private static String markerName(String stage) {
        return stage.replace(':', '_').replace('/', '_');
    }

    /** Appends one JSON line to logs.jsonl. */
    public synchronized void log(String event, Map<String, Object> fields) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("event", event);
        line.putAll(fields);
        try {
            Files.writeString(dir.resolve("logs.jsonl"), Json.json().copy().disable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT).writer().writeValueAsString(line) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
