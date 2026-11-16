# Design: the rules as built

The rules the code applies today, stage by stage. Settings are named by config key. Results, run steps and limitations are in the [README](../README.md).

## 1. Pipeline and run folder

Stages run in this order and write into `runs/<UTC time>_<app>_<config hash>/`.

| Stage | Writes |
|---|---|
| decompile | `01_decompile/stats.json` (jadx output is cached by APK sha256) |
| ingest | `02_ingest/app_model.json` |
| plan | `03_plan/units_teacher.json`, `units_student.json` |
| scan: teacher, then student baseline | `04_scan/<arm>/run_<k>/`: `raw/` (every model answer), `findings.json`, `results.json`, `usage.json` |
| distill | `05_learn/lessons.json`, `rejected_lessons.json` |
| scan: student with lessons | as above |
| evaluate | `07_evaluate/metrics.json`, `matches.json`, `judge_decisions.json` |
| report | `report.md`, `report.html` |

1. A stage is done when its marker exists in `_DONE/`; `run --resume` skips done stages.
2. Resume is refused when the config hash in `manifest.json` or the app's ingest hash differs. Reason: one run folder, one configuration.
3. Every model answer is cached on disk by a hash of the exact request (section 8); a repeated request makes no call.
4. `evaluate --run DIR` needs no rescan: it rebuilds each arm's findings from the saved raw answers with the current parse, evidence and merge code, then judges and reports again. The settings used are recorded in `manifest.json`.

Code: `Experiment`, `RunStore`, `RunManifest`, `Reprocessor`.

## 2. Ingest

**Effectively exported.** The attribute wins when written. When it is missing:

| Component | Exported when |
|---|---|
| activity, alias, service, receiver | it has an intent filter |
| provider | targetSdk <= 16 |

A deep link is a filter with `VIEW`, `BROWSABLE` and a data scheme.

**First-party code** is an allow-list of package prefixes:

1. The manifest package.
2. The package of each manifest-declared class that is not under `ingest.library_prefixes` and shares its first two segments with the manifest package. Reason: merged manifests contain library components.
3. `ingest.extra_first_party_prefixes`.
4. Minus generated classes (`ingest.exclude_class_patterns`).

Everything else is library code and is never scanned.

**Resources.** A `strings.xml` entry is kept when its name contains a credential-like token (`ingest.strings_filter.name_patterns`), its value matches one of seven secret formats (AWS key ids, JWTs and others), or its value looks random (12+ characters, entropy 3.5+, no spaces). Also kept: the network security config and FileProvider paths.

**Dependency list.** Name and version from `META-INF/*.version`, else from a library's `BuildConfig`, else the three-segment package prefix with version unknown.

Code: `ManifestParser`, `FirstPartyClassifier`, `ResourceExtractor`, `SecretPatterns`, `DependencyInventory`.

## 3. Planning

**Budget.** Tokens are estimated as characters / 4, plus 15%.

1. Per-request cap = the smallest of the model's `planning_budget_tokens` (or context size), 80% of its input tokens per minute, and 80% of its total tokens per minute minus `max_output_tokens`.
2. Code budget = cap minus `planning.prompt_overhead_tokens` (1,200) minus `planning.reserved_guidance_tokens` (4,000, student only, so lessons always fit).
3. The run stops before any scan if the code budget is below `planning.min_code_budget_tokens` (2,000).

**Whole app or units** (`planning.mode: auto`): one request if all first-party code plus its context fits the code budget. Otherwise:

| Unit kind | Contents |
|---|---|
| app context | Full manifest, resources, dependency list, no code; scanned once |
| component | One per entry point (exported or deep-link component; every component if there is none), plus the files it references, then the files that reference it, added by priority while they fit |
| package | Files no entry point reached, grouped by package |

The class graph is textual and one hop: a fully qualified name, or a same-package simple name as a whole word. A file may be in several units.

**Same units for both models** (`planning.shared_units`): teacher and student get the units planned for the smaller of the two budgets. Reason: "teacher found it, student missed it" then compares the same code.

**Oversized.** A single file larger than the budget is skipped and listed in the plan. A unit whose answer is cut off is split at scan time (section 4).

Code: `ScanPlanner`, `Prioritizer`, `TextReferenceGraphBuilder`.

## 4. Scan

**Prompt order:** fixed rules and output format, the lesson block (with-lessons arm only), app context (package, SDK levels, manifest slice, resources, dependency list), then each file between `FILE` markers. Only the lesson block differs between the student arms.

**Outcome of one unit:**

| Answer | Action | Status |
|---|---|---|
| Complete, valid JSON | Findings go to the checks below | `ok` |
| Cut off | One retry with doubled output room. Still cut off: split the unit (whole app into units, a unit into single files), at most two levels deep. | `ok` or `partial`; `truncated` if it cannot be split |
| Refused | Category recorded, no findings | `refused` |
| Not readable as JSON | One repair call quoting the error | `parse_failed` if still unreadable |
| Call fails after retries | The other units continue | `failed` |

**Per-finding checks.** A finding is dropped and counted unless it has a title, a location with a file or component, evidence, and a known severity and kind. Kept findings are split into one per location.

**Evidence check.** Whitespace is ignored. The first rule that fits wins.

| # | The quoted evidence | Status |
|---|---|---|
| 1 | dependency finding: matches a line of the dependency list | `inventory_match`, else `inventory_unmatched` |
| 2 | is found exactly in the cited file | `verified_exact` |
| 3 | is found exactly in another file of the unit | `relocated` (file corrected) |
| 4 | is found in the unit's manifest or resource text | `verified_exact` for manifest and resource findings, `relocated` for code findings |
| 5 | uses `...`: every fragment appears in order | `verified_fuzzy` |
| 6 | has several lines: each appears in order, at most 3 file lines apart | `verified_fuzzy` |
| 7 | has lines in another order: all appear word for word within 20 lines | `verified_fuzzy` |
| 8 | is 90% similar to a stretch of the cited file (`scan.evidence.fuzzy_threshold`) | `verified_fuzzy` |
| 9 | none of these | `unverified` |

Rules 3 to 8 need at least `scan.evidence.min_evidence_chars` (25), except manifest and resource findings in rule 4. Verified means `verified_exact`, `verified_fuzzy`, `relocated` or `inventory_match`. An inventory match only proves the library is present, so it carries a `needs_advisory_check` flag.

**Duplicate merge** (per arm run, across units). Two findings are one when they are in the same file and any of:

1. same CWE and same symbol (when neither finding has a file, also the same component);
2. overlapping lines and the same CWE or CWE family (`cwe_groups` in `configs/matching.yaml`);
3. the same quoted evidence, and the CWEs do not disagree or both quote a single manifest attribute.

The kept copy is the one with the best evidence status, then higher confidence, then content order. It carries the higher confidence, every unit id and every other location (`also_at`). `scan.dedup: off` disables merging.

Code: `Scanner`, `PromptBuilder`, `FindingParser`, `Normalizer`, `EvidenceVerifier`, `Deduplicator`.

## 5. Lessons

**One teacher call.** Input: the teacher's verified findings, merged across teacher runs. Above `distill.max_input_findings` (60), only the most confident finding per CWE and class is kept. The call never sees the answer list. A cut-off answer gets one doubled retry; an unusable one, one repair call.

**A lesson contains:** title, component types and APIs it applies to, signals, why it is exploitable, false-positive guards, CWE, OWASP category, severity guidance, an abstract example, and source finding ids (never shown to the student).

**Sanitiser** (rules only, no model):

| Denied in lesson text | Exempt |
|---|---|
| App identifiers (package, classes, methods, components, string names, hosts) and their distinctive parts of 4+ characters | Framework callbacks, platform names, words used in the bundled library code, common English words, `<Placeholders>` |
| The app's string literals | Platform names, URI schemes, MIME types, common words, literals under 6 characters unless credential-like |
| File names, paths, line references | `AndroidManifest.xml`, `strings.xml`, `build.gradle` |

Answer-key check: names and literals are masked in the lesson's example and in its source evidence; if their token similarity is above `distill.sanitizer.example_similarity_max` (0.6), the example is removed. Limit: the sanitiser is lexical.

On a violation (`distill.sanitizer.on_violation`): `redact` replaces the term with a typed placeholder and checks again, dropping a lesson that still fails; `drop` drops the lesson. Every hit is saved in `rejected_lessons.json`.

**Final block.** Lessons are ranked by the strongest severity of their source findings, then by their number, and taken while under `distill.max_lessons` (15) and `distill.max_lesson_tokens` (4,000), then printed in id order, so the block is identical in every request.

**The run stops** when the teacher has no verified findings, the call fails, is refused or stays unusable, or no lesson survives. The with-lessons arm never runs with an empty block.

`distill.lessons_from` loads a saved lesson file instead: no call, and no second sanitiser pass.

Code: `LessonStage`, `LessonDistiller`, `LessonSanitizer`, `LessonSelector`.

## 6. Matching

**Reference sources:**

| Source | Used when | The judge sees |
|---|---|---|
| `readme` | An answer list exists (headline) | Title and description, verbatim |
| `enriched` | The list has the author's CWEs and places (robustness check) | Also CWEs and places |
| `teacher_findings` | No answer list | Each verified teacher finding's title and rationale |

**Step 1, structured pre-score** (0 to 1, no model). Place: same class and method 1.0, same class 0.8, same component 0.6, same file 0.5; a README item that names an app class counts as a place. CWE: accepted 1.0, same family 0.7. Total: weighted average (place 0.6, CWE 0.4) of the signals that exist. Pairs below `prefilter_min_score` (0.3) are dropped only in enriched mode; otherwise the score only orders pairs.

**Step 2, shortlist.** One call shows the judge all items and a run's findings and asks which items each finding plausibly describes. It runs in 3 orders (`evaluate.screen_orders`): content order and two fixed shuffles. Reason: a list-wise answer depends on order. The results are combined as a union, because this step only proposes pairs; step 3 decides.

**Step 3, pairwise confirm.** One call per shortlisted pair. The judge sees the item and the finding's title, class, CWE, location, evidence and rationale. It does not see the arm, model, run or id, so it cannot favour an arm. It answers same type, same root cause, and same place (only when the item names a place), then a verdict:

- `match`: same type and root cause (and place, when asked);
- `partial`: related, but a different root cause or place;
- `no`: anything else, including an unreadable answer after one repair and a pair never shortlisted.

**Step 4, one-to-one assignment.** Each accepted pair gets weight 1000 + pre-score; every other pair gets 0. The Hungarian method finds the pairing with the highest total. Because the pre-score is at most 1, one more matched item always outweighs any gain in scores: the number of matches is maximised first, their quality second. Strict accepts `match`; lenient also accepts `partial`.

Code: `MatchingPipeline`, `StructuredScorer`, `Judge`, `HungarianAssignment`, `GroundTruthReference`, `TeacherFindingsReference`; `configs/matching.yaml`.

## 7. Metrics

The judge decides once per arm run, on all findings. Each number is an assignment over those decisions.

| Number | Computed as |
|---|---|
| Recall, strict (headline) | items credited one-to-one by a verified finding with `match` / items |
| Recall, lenient | same, with `match` or `partial` |
| Recall incl. unverified | strict, over all findings |
| Precision | verified findings that match any item (many-to-one) / verified findings |

- Repeats: each run is its own row. Per arm: mean, minimum and maximum of strict recall. Lift = mean with lessons minus mean baseline. No significance test.
- The teacher is scored the same way, as the ceiling.
- Judge health: if more than 20% of judge calls fail, no metrics are written. Reason: a broken judge must not look like 0% recall.
- No answer list: only the student arms are scored, every number is labelled "agreement with teacher", and precision and the teacher row are omitted.

Code: `Evaluator`, `Metrics`, `EvaluateStage`.

## 8. Model layer and extending it

Every model is used through one interface, `LlmClient.complete(LlmRequest)`. Two adapters exist: `AnthropicClient`, and `OpenAiCompatibleClient` for any chat-completions endpoint (set `base_url`).

Wrappers, outermost first:

| Wrapper | Why it sits there |
|---|---|
| Usage tracking | Sees cache hits too, counted as cached |
| Cache | A hit skips retries and quota |
| Retry | Backoff on 429, 5xx and connection errors |
| Rate limit | Paces every real attempt |

Cache key: provider, model, system text, messages, output schema, temperature, max tokens, params and the repeat index (so repeated runs stay independent). Refusals and errors are not cached.

| To add | Do this |
|---|---|
| A model | An entry in `configs/models.yaml`; point a role at it there or with `roles:` in the app config. The judge must differ from the teacher and the student |
| A provider | One class implementing `LlmClient`, one line in `ProviderRegistry`, its name in `ConfigLoader.KNOWN_PROVIDERS` |
| An app | Copy `configs/example_new_app.yaml`; set `app.name` and `app.input`. Optional: `app.ground_truth`, `ingest.extra_first_party_prefixes`, `distill.lessons_from` |

Code: `LlmClient`, `ModelClientFactory`, `CachingClient`, `RetryingClient`, `RateLimitedClient`, `UsageTrackingClient`, `ProviderRegistry`.
