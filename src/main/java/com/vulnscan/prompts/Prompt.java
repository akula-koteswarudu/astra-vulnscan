package com.vulnscan.prompts;

import com.samskivert.mustache.Template;
import java.util.Map;

/** One versioned prompt: a system part and a user part (Mustache templates), plus the file's sha256 for the record. */
public record Prompt(String type, String version, String sha256, String systemText, Template userTemplate) {

    /** The system part has no variables: it stays byte-identical across calls (provider prompt caching). */
    public String system() {
        return systemText;
    }

    public String user(Map<String, Object> context) {
        return userTemplate.execute(context).strip() + "\n";
    }
}
