# Google Doc draft (for review; numbers are from the run folders named)

## 1. Results: student recall before and after lessons

InsecureShop, scan prompt v4, file order shuffled per repeat, 3 repeats per arm, judge DeepSeek v4.1 Flash with a 3-order quick look, strict, verified evidence only (run `2026-11-15T1036_insecureshop_fec4c875`, published under `results/insecureshop-v4-headline`):

| Arm | Model | Items found of 19 (per run) | Mean |
|---|---|---|---|
| Teacher (ceiling) | claude-sonnet-5-5 | 15 | 15 |
| Student, no lessons | qwen3.8-27b | 9, 10, 10 | 9.7 (51%) |
| Student, with lessons | qwen3.8-27b | 15, 14, 13 | 14.0 (74%) |

Lift: +4.3 items (+22.8 points); the ranges do not overlap (baseline at most 10, with lessons at least 13). Checked four ways:
- **Enriched reference** (same items plus CWEs and places): +28.1 points.
- **Place check:** a credit counts only if the finding sits in a file the item names. Baseline 8.0, with lessons 11.7: lift +3.7. If a manifest finding for the exported content provider also counts as the right place, +4.3. The lift survives.
- **Judge stability:** with a 1-order quick look the same run scored +4.7; removing a single duplicate finding changed the quick look's answer and the score, which is how this weakness was found. With 3 orders all 11 experiments were re-evaluated: only 2 of 18 result rows moved, each by one student run.
- **Where it comes from:** items the baseline never found in any run and the lessons arm found in all three (GT-04 code loading, GT-07 file theft, GT-09 broadcast receiver), plus GT-14 (1 of 3 → 3 of 3). It does not come from the loosely worded WebView items.

Cross-app (InsecureShop lessons, student scans OVAA): +0.3 items (10.7 → 11.0). The lessons did not transfer. Reported as found. So the +4.3 is an in-sample result: the lessons were distilled from the same app the student was rescanned on, and one app cannot prove that they generalise.

Reproducing: the reports, metrics, lessons and findings of the key runs are committed under `results/`. A fresh clone in Docker with my LLM cache mounted reproduces the same numbers with no billed calls. Without the cache the run makes real calls (about $0.13 for the teacher scan, plus Pollen for the student and judge) and can differ slightly, because the teacher's temperature cannot be set.

## 2. How the lessons are made

- **One teacher call.** The teacher's verified findings go into a single distill call, which returns lessons as JSON. The answer key never reaches any prompt; a test enforces that only the evaluation code can read it.
- **General by construction, then checked.** The prompt asks for one lesson per vulnerability pattern: what to look for, why it is exploitable, and when it is not a finding. A deterministic sanitiser then replaces anything app-specific (class and package names, string literals, file names, line references) with placeholders such as `<String>`, and removes an example that is too close to the code it came from.
- **Held constant across experiments.** The 15 lessons were distilled once (run `2026-11-14T1846`) and reused, so prompt and ordering experiments compare the same lessons.

Example (L05, exactly as the student sees it):

```text
- L05 Exported Activity reflects caller Intent through setResult (CWE-927, M8: Security Misconfiguration)
  Applies to: Activity; APIs: setResult, getIntent, FLAG_GRANT_READ_URI_PERMISSION, FileProvider
  Look for: setResult(RESULT_OK, getIntent()) in an exported Activity; FileProvider or grantUriPermissions provider present in the app; Caller can set data URI and grant flags
  Why: The caller's Intent returns with its grant flags and URI, so the app grants the caller access to its own provider URIs. This leaks private files.
  Not a finding when: Result Intent is newly built with no caller URI or flags; Activity is not exported or no grantable provider exists
  Severity: High when combined with a broad FileProvider.
  Example: // exported Activity setResult(RESULT_OK, getIntent());
```

## 3. Match function and why

A ground-truth entry is one line of README prose with no file or line, for example "Possible to load any arbitrary URL in webview via Deeplink". A finding is structured: class, CWE, file, line, quoted evidence. So matching must compare meaning, not strings or locations.

1. **Evidence gate:** a finding counts only if its quoted evidence is found in the decompiled code (exact, moved, or lightly rearranged lines). This removes invented code before scoring.
2. **Structured pre-score:** CWE groups, component and file overlap. Cheap, used to rank candidates and to put the most likely pair first.
3. **Quick look (one call per run, 3 orders):** the judge sees all items and all findings and shortlists pairs that might be the same flaw; only shortlisted pairs go to step 4, so a pair it overlooks counts as "not found". It runs in 3 orders (as listed plus 2 fixed shuffles) and the shortlists are combined: on the headline run a single order missed 22 true pairs.
4. **LLM judge, pairwise:** "same flaw: match, partial or no?" The judge (DeepSeek) is a different model family from the teacher (Anthropic) and the student (Qwen), so it is not grading its own family's writing. Temperature 0, cached, health check fails the run if more than 20% of answers are unparseable.
5. **One-to-one assignment (Hungarian):** each item is credited to at most one finding and each finding to at most one item, maximising credits. A vague finding cannot claim three items; three duplicate findings cannot claim one item three times.
6. **Strict headline** (judge said "match"), lenient shown beside it.

Why this over alternatives: string or CWE match fails on README prose; location match needs locations the README does not have; an LLM judge alone over-credits, hence the evidence gate and one-to-one. The judge was audited on every decision of one run (the v3 run, `results/insecureshop-v3-with-judge-audit`): 95% agreement, kappa 0.79 (see the limitation on who labelled).

Known weakness, measured: with README-only items, the judge cannot tell apart items that share a consequence but differ in place (GT-02, 03, 06, 13, 17 all end in "loads an arbitrary URL in a WebView"). The total stays right but the credit can land on the neighbouring item. The enriched reference and the place check are the controls; both agree with the headline.

Second weakness, measured and fixed: the quick look is one call over a long list, and LLMs show position bias (they overlook items depending on where they sit, especially in the middle of long inputs). A single order missed 22 true pairs and made the score depend on list order. The union of 3 orders fixed it here. For very long lists, checking pairs in batches, or all pairs, is the next step.

## 4. A real app with 1M+ lines

Measured today: InsecureShop decompiles to 350,791 lines, of which 2,166 are the app's own code (0.6%). A whole-app teacher scan was 36K input tokens and $0.127. At the same rate 1M lines is about 16.6M input tokens. The money (about $33 of input per teacher pass) is not the problem. The problems are:

- **It does not fit.** No context window holds 16.6M tokens, so the code must be cut into units, and a flow that crosses units is easy to miss.
- **Noise grows with size.** On the one real app I scanned, 5 of 11 distinct issues were noise and one issue came back once per unit. At 1M lines, triage load decides whether the tool is usable.
- **Most of an APK is not the app.** Over 99% of InsecureShop's decompiled lines are libraries and generated code.

What the real-app run measured (Markor, 196 first-party files): 39 units, 1.22M input tokens, $2.90, a token estimate 1.5× too low, and duplicates across units. So the design is a funnel, where every stage is cheaper than the next:

1. **Cut to first-party code.** Built: manifest-derived allow-list, generated code excluded. Next: library fingerprinting by code hashes, not package names, because obfuscation renames packages.
2. **Map the attack surface without an LLM.** Entry points from the manifest (exported components, deep links, providers, receivers), sinks (WebView loading, file and SQL APIs, exec, crypto, logging, setResult), and a call graph. Keep code on a path from an attacker-reachable entry to a sink. Units become these slices, not files. Classes everyone calls get a short summary instead of their full body.
3. **Tier the models.** This is the point of the teacher-to-student loop: the cheap student scans every slice with the lessons; the teacher reviews only the riskiest slices, re-checks student findings, and scores a random sample to watch the student's quality drift. This step depends on lessons that transfer, which my one cross-app test did not show (+0.3). It needs a lesson library built from many apps and proven on held-out apps first.
4. **Pay once for shared context.** System prompt, lessons and manifest are the same for every unit: prompt caching. Non-urgent scans go through batch APIs.
5. **Incremental rescans.** Built: model answers are cached by request content, so an unchanged unit costs nothing to rescan. Proposed: rescan a unit when anything it depends on changed (an unchanged helper can become exploitable through a changed caller), and give findings an identity that is stable across versions (today a finding's id includes the run).
6. **Confirm before a human sees it.** Proposed: a second pass (the teacher, or the dynamic agent) tries to refute each finding, so triage load does not grow with app size.
7. **Operations.** Queue plus worker pool with per-provider rate limits (built: concurrency and token budgets per model), retries, resume from any stage (built), every number traceable to a run folder (built).

Hard parts, said plainly: flows that cross many units (method summaries composed across the call graph, or letting the model ask for callers and callees within a budget); reflection and string-named intents that break the call graph; obfuscation; native code, which this scanner does not read.

## 5. Optional: the frontier scan on a real APK

Markor 2026.10.0, an open-source Markdown editor from F-Droid. An open-source app was chosen over a store app: its licence allows analysis, findings can be checked against the public source, and nothing unfixed in someone's commercial product gets published. The teacher scanned 39 units (1.22M input tokens, $2.90).

- **Real vs noise:** 31 raw findings, 21 after the fixed dedup, 11 distinct issues by hand triage (the triage groups the same weakness in several files as one issue). 6 real (1 high, 1 severity unconfirmed, 4 low), 5 noise. One noise finding came from missing context: the data was handled safely inside a third-party library outside the scanned code. The high-severity issue is removed from the published report until the maintainers have been notified; the low-severity ones are in the redacted report under `results/`. Labels were drafted with Claude from the decompiled code and not exploited at runtime. The teacher rated one issue (an exported screen that opens caller-chosen file paths) high; my review confirmed the code issue but could not confirm its impact statically, so its severity is counted as unconfirmed.
- **What broke, and the fixes:**
  - First-party detection missed the author's own utility library (built from the same repo); fixed by one config line.
  - The same app-wide manifest setting came back from every unit with different CWEs or none, so dedup missed it (7 copies of one issue); dedup now merges the same quoted evidence.
  - Teacher and student were planned on different budgets, so 11 of 39 units differed; both now get the same units.
  - The token estimate was about 1.5× too low for Claude's tokenizer, so the cost came in at $2.90, not the estimated ~$1.65 of input.
  - The internet dropped during the student scan; the retry layer recovered with no lost unit.

## 6. Models used and why

- **Teacher: claude-sonnet-5-5** ($2/$10 per million tokens), adaptive thinking, effort high. The brief asks for Sonnet class. Its temperature cannot be set, so the teacher runs once and is cached.
- **Student: qwen3.8-27b**, open weights. Started on Groq's free tier; its limits (8K tokens per minute for this model) forced small chunks, and the student lost cross-file context. Moved to Pollinations, an OpenAI-compatible host for open models (one 120K-token request per app). Needs `reasoning_effort: none`, otherwise it spends its whole output budget thinking and returns nothing. Temperature 0 with a fixed seed; variance is produced on purpose by shuffling file order (and, in a separate experiment, by sampling at 0.7).
- **Judge: deepseek-v4.1-flash**, chosen to be a third family so neither the teacher's nor the student's family grades itself; cheap enough to judge every pair.
- **Adding a model** is a config entry; a new provider is one adapter class plus one registry line.
- **Cost of everything in this document:** about $4.09 of Anthropic credit, of which $2.90 was the Markor scan. The student and judge are billed in Pollen on Pollinations; re-judging all 11 experiments used 0.068 Pollen.

## 7. Limitations and what I would do next

| Limitation | Next step |
|---|---|
| In-sample lift; the one cross-app test (OVAA) showed no transfer. | Leave-one-app-out over several apps; lessons distilled from several apps and teacher runs, merged. |
| InsecureShop and its list are public; models may have seen them. | Renamed or obfuscated copies, or private apps with planted bugs. |
| Small N: 3 repeats per arm, no significance test. | More repeats; bootstrap confidence intervals or a paired test per item. |
| Judge audit labels drafted by Claude and reviewed by me, so not fully independent. | A second human labeller; a reasoning or stronger judge; an all-pairs check on a sample. |
| README items have no places; per-item credit can land on a neighbouring WebView item. | Location-level recall as a reported metric (the place check already gives +3.7 to +4.3). |
| Units come from a text-based, one-hop class graph. | A bytecode call graph (Androguard / SootUp) with entry-point-to-sink slices and summaries of shared classes; the key step for 1M+ line apps (section 4). |
| Static only; native code, React Native/Flutter bundles and split APKs are not read. | Bundle and native readers; the dynamic agent in the dynamic-analysis section for runtime-only bugs. |
| No control for "any checklist would help". | The planned checklist control arm: the student gets a generic MASVS checklist instead of the lessons. |

## 8. Held-out testing (for the interview)

- Leave-one-app-out over several deliberately vulnerable apps (OVAA, InsecureBankv2, DIVA, AndroGoat and others): distil lessons from the other apps, test on the held-out one.
- Hold out vulnerability classes too: does a lesson about exported components help on a class the teacher never saw?
- Contamination control: renamed or obfuscated copies of the public apps, or private apps with planted bugs.
- Report the mean and spread over repeats and apps; the OVAA result (+0.3) is the honest starting point.
