package com.vulnscan.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Small file helpers. Writes are atomic: temp file in the same directory, then rename. */
public final class Files2 {

    private Files2() {
    }

    public static void writeAtomic(Path target, String content) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path tmp = Files.createTempFile(target.toAbsolutePath().getParent(), ".tmp-", ".part");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + target, e);
        }
    }

    public static void writeJson(Path target, Object value) {
        try {
            writeAtomic(target, Json.json().writeValueAsString(value) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot serialise " + target, e);
        }
    }

    public static <T> T readJson(Path source, Class<T> type) {
        try {
            return Json.json().readValue(source.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + source, e);
        }
    }

    public static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Decompiled output can contain malformed UTF-8; fall back to a lenient decode.
            try {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            } catch (IOException e2) {
                throw new UncheckedIOException("cannot read " + file, e2);
            }
        }
    }

    public static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + dir, e);
        }
    }
}
