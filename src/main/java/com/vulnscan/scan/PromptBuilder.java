package com.vulnscan.scan;

import com.vulnscan.ingest.AppModel;
import com.vulnscan.ingest.CodeBase;
import com.vulnscan.planning.CodeView;
import com.vulnscan.planning.ScanUnit;
import com.vulnscan.prompts.Prompt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the scan prompt for a unit. Order (stable prefix first, for provider prompt caching): system rules + output
 * schema -> guidance block -> unit context -> code. Only the guidance block differs between student arms.
 */
public class PromptBuilder {

    /** JSON schema of the scan answer (sent as structured output where the provider supports it). */
    public static final Map<String, Object> FINDINGS_SCHEMA = findingsSchema();

    private final Prompt prompt;
    private final AppModel app;
    private final CodeBase code;

    public PromptBuilder(Prompt prompt, AppModel app, CodeBase code) {
        this.prompt = prompt;
        this.app = app;
        this.code = code;
    }

    public String system() {
        return prompt.system();
    }

    public String version() {
        return prompt.version();
    }

    public String user(ScanUnit unit, String guidance) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("guidance", guidance == null || guidance.isBlank() ? null : guidance.strip());
        ctx.put("package_name", app.packageName());
        ctx.put("min_sdk", app.manifest().minSdk());
        ctx.put("target_sdk", app.manifest().targetSdk());
        ctx.put("manifest_slice", unit.manifestSlice());
        ctx.put("resources", unit.resources());
        ctx.put("dependencies", unit.dependencies());
        List<Map<String, Object>> files = new ArrayList<>();
        for (String path : unit.files()) {
            files.add(Map.of("path", path, "text", CodeView.render(code.text(path))));
        }
        ctx.put("files", files);
        ctx.put("has_files", !files.isEmpty());
        return prompt.user(ctx);
    }

    private static Map<String, Object> findingsSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> nullableStr = Map.of("type", List.of("string", "null"));
        Map<String, Object> location = Map.of("type", "object",
                "properties", Map.of("file", str, "component", nullableStr, "symbol", nullableStr),
                "required", List.of("file", "component", "symbol"), "additionalProperties", false);
        Map<String, Object> props = new HashMap<>();
        props.put("title", str);
        props.put("vuln_class", str);
        props.put("cwe", str);
        props.put("owasp_category", Map.of("type", "string", "enum", List.of("MASVS-STORAGE", "MASVS-CRYPTO", "MASVS-AUTH",
                "MASVS-NETWORK", "MASVS-PLATFORM", "MASVS-CODE", "MASVS-RESILIENCE", "MASVS-PRIVACY")));
        props.put("severity", Map.of("type", "string", "enum", List.of("critical", "high", "medium", "low", "info")));
        props.put("confidence", Map.of("type", "number"));
        props.put("locations", Map.of("type", "array", "items", location));
        props.put("evidence", str);
        props.put("rationale", str);
        props.put("kind", Map.of("type", "string", "enum", List.of("code", "manifest", "resource", "dependency")));
        Map<String, Object> finding = Map.of("type", "object", "properties", props,
                "required", List.of("title", "vuln_class", "cwe", "owasp_category", "severity", "confidence", "locations",
                        "evidence", "rationale", "kind"),
                "additionalProperties", false);
        return Map.of("type", "object", "properties", Map.of("findings", Map.of("type", "array", "items", finding)),
                "required", List.of("findings"), "additionalProperties", false);
    }
}
