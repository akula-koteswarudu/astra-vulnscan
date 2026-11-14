package com.vulnscan.prompts;

import com.samskivert.mustache.Mustache;
import com.vulnscan.common.Files2;
import com.vulnscan.common.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompts/&lt;type&gt;/&lt;version&gt;.md. A file has a "&lt;!-- system --&gt;" part and a "&lt;!-- user --&gt;" part.
 * Published versions are never edited; a change is a new version file. Every prompt used is recorded with its sha256.
 */
public class PromptLibrary {

    static final String SYSTEM_MARK = "<!-- system -->";
    static final String USER_MARK = "<!-- user -->";

    private final Path dir;
    private final Mustache.Compiler compiler = Mustache.compiler().escapeHTML(false).defaultValue("").emptyStringIsFalse(true);
    private final Map<String, Prompt> loaded = new ConcurrentHashMap<>();

    public PromptLibrary(Path dir) {
        this.dir = dir;
    }

    public Prompt get(String type, String version) {
        return loaded.computeIfAbsent(type + "/" + version, k -> load(type, version));
    }

    /** type/version -> sha256 of every prompt loaded so far (for the run manifest). */
    public Map<String, String> used() {
        Map<String, String> out = new TreeMap<>();
        loaded.forEach((k, p) -> out.put(k, p.sha256()));
        return out;
    }

    private Prompt load(String type, String version) {
        Path file = dir.resolve(type).resolve(version + ".md");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("prompt not found: " + file);
        }
        String text = Files2.read(file);
        int s = text.indexOf(SYSTEM_MARK);
        int u = text.indexOf(USER_MARK);
        if (s < 0 || u < s) {
            throw new IllegalArgumentException(file + " must contain '" + SYSTEM_MARK + "' followed by '" + USER_MARK + "'");
        }
        String system = text.substring(s + SYSTEM_MARK.length(), u).strip();
        String user = text.substring(u + USER_MARK.length());
        return new Prompt(type, version, Hashing.sha256(text), system, compiler.compile(user));
    }
}
