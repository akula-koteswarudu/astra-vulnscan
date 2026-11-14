package com.vulnscan.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API keys from a gitignored .env file (KEY=VALUE lines) or the process environment; .env wins so a local file
 * is predictable. Values are never logged or included in messages: errors name only the variable.
 */
public class Secrets {

    private final Map<String, String> dotenv;

    public Secrets(Map<String, String> dotenv) {
        this.dotenv = Map.copyOf(dotenv);
    }

    public static Secrets load(Path dotenvFile) {
        Map<String, String> values = new HashMap<>();
        if (Files.isRegularFile(dotenvFile)) {
            try {
                List<String> lines = Files.readAllLines(dotenvFile);
                for (String raw : lines) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                        continue;
                    }
                    if (line.startsWith("export ")) {
                        line = line.substring("export ".length()).strip();
                    }
                    String key = line.substring(0, line.indexOf('=')).strip();
                    String value = line.substring(line.indexOf('=') + 1).strip();
                    if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (!value.isEmpty()) {
                        values.put(key, value);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + dotenvFile, e);
            }
        }
        return new Secrets(values);
    }

    public String require(String name) {
        String v = dotenv.get(name);
        if (v == null || v.isBlank()) {
            v = System.getenv(name);
        }
        if (v == null || v.isBlank()) {
            throw new ConfigException("missing API key: set " + name + " in .env or the environment");
        }
        return v;
    }

    public boolean has(String name) {
        String v = dotenv.getOrDefault(name, System.getenv(name));
        return v != null && !v.isBlank();
    }

    @Override
    public String toString() {
        return "Secrets[" + dotenv.size() + " values from .env, redacted]";
    }
}
