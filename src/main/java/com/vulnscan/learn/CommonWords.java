package com.vulnscan.learn;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** The vendored common-English-words list (src/main/resources/common_words.txt). */
public final class CommonWords {

    private CommonWords() {
    }

    public static Set<String> load() {
        Set<String> words = new HashSet<>();
        try (InputStream in = CommonWords.class.getResourceAsStream("/common_words.txt")) {
            if (in == null) {
                throw new IllegalStateException("missing resource common_words.txt");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    words.add(line.strip());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return words;
    }
}
