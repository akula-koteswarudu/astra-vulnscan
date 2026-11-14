package com.vulnscan.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vulnscan.common.Hashing;
import com.vulnscan.common.Json;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Loads and validates the three config files.
 * <ol>
 *   <li>app file deep-merged over the built-in defaults (so a new-app file only states what differs);</li>
 *   <li>profile defaults for {@code runs} and {@code arms} applied where the app file is silent (explicit values win);</li>
 *   <li>bound to records with unknown keys rejected; Bean Validation; then cross-field and "not built yet" checks.</li>
 * </ol>
 * Relative paths are resolved against {@code baseDir} (the repo root when run normally).
 */
public class ConfigLoader {

    static final String DEFAULTS_RESOURCE = "/config-defaults.yaml";
    /** Providers with an adapter in the registry. Kept here so config errors appear before anything is built. */
    static final Set<String> KNOWN_PROVIDERS = Set.of("anthropic", "openai_compatible");

    private final ObjectMapper yaml = Json.yaml();
    private final Validator validator;
    private final Path baseDir;

    public ConfigLoader(Validator validator, Path baseDir) {
        this.validator = validator;
        this.baseDir = baseDir;
    }

    public LoadedConfig load(Path appFile) {
        Path resolvedAppFile = resolve(appFile.toString());
        ObjectNode appTree = readTree(resolvedAppFile);
        ObjectNode merged = deepMerge(readDefaults(), appTree);
        applyProfile(merged, appTree);
        AppConfig app = bind(merged, AppConfig.class, resolvedAppFile);
        validate(app, resolvedAppFile);

        Path modelsFile = resolve(app.modelsFile());
        ModelsConfig models = bind(readTree(modelsFile), ModelsConfig.class, modelsFile);
        validate(models, modelsFile);

        Path matchingFile = resolve(app.matchingFile());
        MatchingConfig matching = bind(readTree(matchingFile), MatchingConfig.class, matchingFile);
        validate(matching, matchingFile);

        List<String> errors = new ArrayList<>();
        checkCrossFields(app, models, errors);
        if (!errors.isEmpty()) {
            throw new ConfigException("invalid configuration (" + resolvedAppFile + "):\n  - " + String.join("\n  - ", errors));
        }
        return new LoadedConfig(app, models, matching, resolvedAppFile, hash(app, models, matching));
    }

    public Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : baseDir.resolve(p).normalize();
    }

    // ---- profile -------------------------------------------------------------------------------------------

    /** Profile defaults (section 5 of the plan). Only keys the app file does not set are filled. */
    static void applyProfile(ObjectNode merged, ObjectNode appTree) {
        String profile = merged.path("profile").asText("assignment");
        ObjectNode runs = merged.with("runs");
        ObjectNode arms = merged.with("arms");
        int studentRuns = "full".equals(profile) ? 3 : 1;
        boolean checklist = "full".equals(profile);
        setIfAbsent(runs, appTree.path("runs"), "teacher_runs", 1);
        setIfAbsent(runs, appTree.path("runs"), "student_runs", studentRuns);
        setIfAbsent(arms, appTree.path("arms"), "checklist_control", checklist);
        setIfAbsent(arms, appTree.path("arms"), "lessons_focus_student_misses", false);
    }

    private static void setIfAbsent(ObjectNode target, JsonNode explicit, String key, Object value) {
        if (explicit.has(key)) {
            return;
        }
        if (value instanceof Integer i) {
            target.put(key, i);
        } else {
            target.put(key, (Boolean) value);
        }
    }

    // ---- validation ----------------------------------------------------------------------------------------

    private <T> void validate(T value, Path file) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            String msg = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("\n  - "));
            throw new ConfigException("invalid configuration in " + file + ":\n  - " + msg);
        }
    }

    /** Checks that span fields or files, plus options that exist in the schema but are not built yet. */
    static void checkCrossFields(AppConfig app, ModelsConfig models, List<String> errors) {
        ModelsConfig.Roles roles = models.roles();
        for (String role : List.of(roles.teacher(), roles.student(), roles.judge())) {
            if (!models.models().containsKey(role)) {
                errors.add("roles refers to undefined model '" + role + "' (defined: " + new TreeSet<>(models.models().keySet()) + ")");
            }
        }
        if (errors.isEmpty()) {
            ModelsConfig.ModelSpec teacher = models.spec(roles.teacher());
            ModelsConfig.ModelSpec judge = models.spec(roles.judge());
            ModelsConfig.ModelSpec student = models.spec(roles.student());
            if (judge.model().equals(teacher.model())) {
                errors.add("the judge must not be the teacher model (" + judge.model() + ")");
            }
            if (judge.model().equals(student.model())) {
                errors.add("the judge must not be the student model (" + judge.model() + ")");
            }
        }
        for (Map.Entry<String, ModelsConfig.ModelSpec> e : models.models().entrySet()) {
            ModelsConfig.ModelSpec m = e.getValue();
            String name = "models." + e.getKey();
            if (!KNOWN_PROVIDERS.contains(m.provider())) {
                errors.add(name + ".provider '" + m.provider() + "' has no adapter (known: " + new TreeSet<>(KNOWN_PROVIDERS) + ")");
            }
            if ("openai_compatible".equals(m.provider()) && (m.baseUrl() == null || m.baseUrl().isBlank())) {
                errors.add(name + ".base_url is required for provider openai_compatible");
            }
            if (!m.rateLimit().hasPerMinuteTokenLimit()) {
                errors.add(name + ".rate_limit needs tpm and/or itpm (tokens per minute)");
            }
            if (m.jsonMode() == ModelsConfig.JsonMode.JSON_SCHEMA) {
                errors.add(name + ".json_mode json_schema is not built yet (use instruction or json_object)");
            }
        }
        if (app.planning().graphBuilder() == AppConfig.GraphBuilder.ANDROGUARD) {
            errors.add("planning.graph_builder androguard is a documented placeholder, not built (use text_reference)");
        }
        if (app.planning().prioritizerSignalsFile() != null) {
            errors.add("planning.prioritizer_signals_file is not built yet (leave null for the built-in signal list)");
        }
        if (app.distill().sanitizer().onViolation() == AppConfig.OnViolation.REWRITE) {
            errors.add("distill.sanitizer.on_violation rewrite is not built yet (use redact or drop)");
        }
        if (app.arms().checklistControl()) {
            errors.add("arms.checklist_control (the full profile's checklist arm) is not built yet");
        }
        if (app.arms().lessonsFocusStudentMisses()) {
            errors.add("arms.lessons_focus_student_misses is not built yet");
        }
        if (app.distill().maxLessonTokens() > app.planning().reservedGuidanceTokens()) {
            errors.add("distill.max_lesson_tokens (" + app.distill().maxLessonTokens()
                    + ") must not exceed planning.reserved_guidance_tokens (" + app.planning().reservedGuidanceTokens()
                    + "): the lesson block must fit the space reserved in every student unit");
        }
    }

    // ---- YAML plumbing -------------------------------------------------------------------------------------

    private ObjectNode readTree(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new ConfigException("config file not found: " + file);
        }
        try {
            JsonNode node = yaml.readTree(file.toFile());
            if (!(node instanceof ObjectNode obj)) {
                throw new ConfigException("config file is empty or not a mapping: " + file);
            }
            return obj;
        } catch (IOException e) {
            throw new ConfigException("cannot parse " + file + ": " + e.getMessage(), e);
        }
    }

    private ObjectNode readDefaults() {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + DEFAULTS_RESOURCE);
            }
            return (ObjectNode) yaml.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + DEFAULTS_RESOURCE, e);
        }
    }

    /** Objects merge key by key; anything else (scalars, lists) in the override replaces the base. */
    static ObjectNode deepMerge(ObjectNode base, ObjectNode override) {
        Iterator<Map.Entry<String, JsonNode>> it = override.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode existing = base.get(e.getKey());
            if (existing instanceof ObjectNode baseObj && e.getValue() instanceof ObjectNode overObj) {
                deepMerge(baseObj, overObj);
            } else {
                base.set(e.getKey(), e.getValue());
            }
        }
        return base;
    }

    private <T> T bind(ObjectNode tree, Class<T> type, Path file) {
        try {
            return yaml.treeToValue(tree, type);
        } catch (UnrecognizedPropertyException e) {
            throw new ConfigException("unknown key '" + e.getPropertyName() + "' at " + path(e) + " in " + file
                    + " (known keys: " + e.getKnownPropertyIds() + ")", e);
        } catch (JsonProcessingException e) {
            throw new ConfigException("invalid value in " + file + " at " + path(e) + ": " + e.getOriginalMessage(), e);
        }
    }

    private static String path(JsonProcessingException e) {
        if (e instanceof com.fasterxml.jackson.databind.JsonMappingException jme) {
            return jme.getPath().stream()
                    .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                    .collect(Collectors.joining("."));
        }
        return "?";
    }

    private static String hash(Object... parts) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) {
                sb.append(Json.json().writeValueAsString(p));
            }
            return Hashing.sha256(sb.toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
