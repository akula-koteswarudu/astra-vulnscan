# Build log

Progress per plan step (PLAN.md section 7) and every deviation from the plan, with the reason and what was done instead.
Format: `[step] status: notes`. Deviations are marked **DEVIATION**.

## Phase A

### Step 1: Setup
- Toolchain (all under `.tools/`, nothing global): Temurin JDK 21.0.13 and jadx 1.5.6 (already present); Maven 3.9.16 via the Maven wrapper 3.3.4 (`mvnw`, only-script type). Wrapper scripts verified against Maven Central sha1; the Maven distribution verified against its published sha512 and pinned by sha256 in `.mvn/wrapper/maven-wrapper.properties`. `MAVEN_USER_HOME=.tools/maven` (env.sh), local repository `.tools/m2` (`.mvn/maven.config`). Verified that nothing under `~/.m2` was created or modified.
- `scripts/env.sh` and `scripts/bootstrap.sh`: uv/Python parts removed; bootstrap now fetches JDK + jadx and pre-fetches Maven dependencies through the wrapper.
- Versions pinned (latest stable on Maven Central on 14 Nov 2026): Spring Boot 4.1.2, picocli-spring-boot-starter 4.7.7, anthropic-java 3.8.0, openai-java 4.105.0, jmustache 1.16, ArchUnit 1.5.1. Jackson 2 (`com.fasterxml`) is used for our JSON/YAML because both provider SDKs use it.
- Anthropic Java SDK usage checked against the claude-api skill and the 3.8.0 jar (`javap`): beta messages endpoint, `createStreaming` + `BetaMessageAccumulator`, `BetaOutputConfig.format(BetaJsonOutputFormat)` + `effort`, `fallbacksDefault()` + beta `server-side-fallback-2026-07-01`, `stopReason`/`stopDetails` (refusal). No temperature is sent to Sonnet 5.5 (non-default sampling is rejected).
- Provider smoke calls: run through the real adapters in step 6 (the adapters are the code that loads `.env`), not with curl here.

### Step 2: Config — done
- `ConfigLoader`: app file deep-merged over built-in defaults (`src/main/resources/config-defaults.yaml`), profile fills `runs`/`arms` only where the app file is silent, Jackson binding with unknown keys rejected (error names the key and the known keys), Jakarta Bean Validation, then cross-field and "not built yet" checks. 11 tests pass.
- Additions to the plan's schema (needed to express plan rules, not new features): `planning.graph_builder` (`text_reference` | `androguard`, the latter rejected as a placeholder), `rate_limit.rpd` (Groq reports a requests-per-day limit), and a check that `distill.max_lesson_tokens <= planning.reserved_guidance_tokens` (the lesson block must fit the space reserved in every student unit).
- Rejected as not built yet (will be lifted as Phases B–C build them): `arms.checklist_control` (so `profile: full` currently fails unless it is overridden), `arms.lessons_focus_student_misses`, `on_violation: rewrite`, `json_mode: json_schema`, `prioritizer_signals_file`.
- **DEVIATION (config values, anticipated by plan step 1 / 6.4):** with Groq's real free-tier limit of 8,000 tokens per minute (input + output counted together), the plan's example numbers give a negative student code budget (0.8 × 8000 − 3000 overhead − 8000 output − 3000 guidance). Defaults changed to: student `max_output_tokens` 2000, `prompt_overhead_tokens` 1500, `reserved_guidance_tokens` 1500, `distill.max_lesson_tokens` 1500 (was 2500). Revisited with measured prompt sizes in step 7.

### Step 3: Decompiler + layout detection — done
- `JadxDecompiler` (subprocess via a `ProcessRunner` port, argument list, timeout kills the process tree, `JAVA_OPTS` in the child env, temp dir + atomic rename into `cache/decompiled/<sha256>/`, failure/warning marker counts, jadx version recorded), `LayoutDetector` (jadx, jadx Gradle export, apktool), `InputResolver` (APK sha256 check; directory input with app_id = sha256 of sorted (path, file sha256)). 12 unit tests pass.
- Real run (opt-in integration test): InsecureShop decompiled in 7.3 s, exit 3 → partial success, 2,179 Java files, 8 failed-method markers, 813 warnings; second call hit the cache.

### Step 4: Ingest — done
- `ManifestParser` (hardened DOM: DOCTYPE rejected, external entities off, secure processing; namespace-URI attribute lookup; exported/anomaly rules, permission inheritance, deep links, provider extras, meta-data, raw element XML kept for manifest slices), `FirstPartyClassifier` (derived allow-list, two-segment rule, generated-class globs), `ResourceExtractor`, `SecretPatterns`, `DependencyInventory`, `JavaText` (regex helpers), `Ingestor` (deterministic; `ingest_hash`). 46 unit tests pass (incl. XXE rejection, determinism).
- Real InsecureShop ingest: 2,179 files, 22 first-party (prefix `com.insecureshop`), 29 generated files excluded from the app package; 13 components with correct effective exported (e.g. `ChooserActivity` and `WebView2Activity` exported via intent filters with no attribute; `WebViewActivity` the only deep link; library `UploadService` kept in the manifest but its namespace not first-party); FileProvider `root-path` flagged broad; 47 inventory entries incl. a library BuildConfig version; same `ingest_hash` on two runs.
- Fixed during real-data check (generic fixes, tests added): name patterns now match whole name tokens (substring "api" matched "capital"); the entropy test skips resource references and dotted class names; the `@Override` regex accepts jadx's trailing `// SuperClass` comment; the inventory skips the app's own generated classes.
- Addition to the plan's AppModel: `framework_callbacks` (method names declared with `@Override` in first-party code), needed for the sanitiser exemption in 6.6; `applicationName`, `applicationXml` and per-component `raw_xml`/`meta_data`, needed for manifest slices and FileProvider path resolution.

### Step 5: Ground truth before any scan — done
- `ReadmeConverter` (strict `N. **Title:** description`, sequential numbering, fails loudly on any other line in the section), `GroundTruthLoader` (only reader of ground-truth files; validates ids, required fields, kind/visibility vocabulary, CWE format; unknown keys rejected), CLI `gt-from-readme` (refuses to overwrite an existing file). 8 tests.
- `ground_truth/insecureshop.yaml`: 19 README items verbatim (test asserts equality with the converter output) + enrichment for all 19 (accepted CWEs, kind, locations, static visibility, note), written from the decompiled code. Visibility: 17 full, GT-10 (Cognito) partial, GT-08 (known-vulnerable component) inventory_only. GT-08's location is the author's reading (exported upload-service library component), noted in the file.
- Committed to git before any scan run (no scanner exists yet at this commit).
