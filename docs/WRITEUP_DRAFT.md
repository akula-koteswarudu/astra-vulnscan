# Google Doc draft (for review; numbers are from the run folders named)

## 1. Results: student recall before and after lessons

InsecureShop, scan prompt v4, file order shuffled per repeat, 3 repeats per arm, judge DeepSeek v4.1 Flash, strict, verified evidence only (`runs/2026-11-15T1036_insecureshop_fec4c875`):

| Arm | Model | Items found of 19 (per run) | Mean |
|---|---|---|---|
| Teacher (ceiling) | claude-sonnet-5-5 | 15 | 15 |
| Student, no lessons | qwen3.8-27b | 9, 10, 9 | 9.3 (49%) |
| Student, with lessons | qwen3.8-27b | 15, 14, 13 | 14.0 (74%) |

Lift: +4.7 items (+24.6 points); the ranges do not overlap. Checked three more ways:
- **Enriched reference** (same items plus CWEs and places): +28.1 points.
- **Place check** (new, 15 Nov): a credit counts only if the finding sits in a file the item names. Baseline 8.0, with lessons 12.0: lift +4.0. If a manifest finding for the exported content provider also counts as the right place, +4.7. The lift survives.
- **Where it comes from:** items the baseline never found in any run and the lessons arm found in all three (GT-04 code loading, GT-07 file theft, GT-09 broadcast receiver), plus GT-14 (1 of 3 → 3 of 3). It does not come from the loosely worded WebView items.

Cross-app (InsecureShop lessons, student scans OVAA): +0.3 items (10.7 → 11.0). The lessons did not transfer. Reported as found.

The same numbers were reproduced inside the Docker image from the LLM cache.

## 2. Match function and why

A ground-truth entry is one line of README prose with no file or line, for example "Possible to load any arbitrary URL in webview via Deeplink". A finding is structured: class, CWE, file, line, quoted evidence. So matching must compare meaning, not strings or locations.

1. **Evidence gate:** a finding counts only if its quoted evidence is found in the decompiled code (exact, moved, or lightly rearranged lines). This removes invented code before scoring.
2. **Structured pre-score:** CWE groups, component and file overlap. Cheap, used to rank candidates and to put the most likely pair first.
3. **LLM judge, pairwise:** "same flaw: match, partial or no?" The judge (DeepSeek) is a different model family from the teacher (Anthropic) and the student (Qwen), so it is not grading its own family's writing. Temperature 0, cached, health check fails the run if more than 20% of answers are unparseable.
4. **One-to-one assignment (Hungarian):** each item is credited to at most one finding and each finding to at most one item, maximising credits. A vague finding cannot claim three items; three duplicate findings cannot claim one item three times.
5. **Strict headline** (judge said "match"), lenient shown beside it.

Why this over alternatives: string or CWE match fails on README prose; location match needs locations the README does not have; an LLM judge alone over-credits, hence the evidence gate and one-to-one. The judge was audited on its decisions: 95% agreement, kappa 0.79 (see limitation on who labelled).

Known weakness, measured: with README-only items, the judge cannot tell apart items that share a consequence but differ in place (GT-02, 03, 06, 13, 17 all end in "loads an arbitrary URL in a WebView"). The total stays right but the credit can land on the neighbouring item. The enriched reference and the place check are the controls; both agree with the headline.

## 3. A real app with 1M+ lines

Measured today: InsecureShop decompiles to 350,791 lines, of which 2,166 are the app's own code (0.6%). A whole-app teacher scan was 36K input tokens and $0.127. Naively sending 1M lines would be about 16.6M input tokens, about $33 per teacher pass for input alone (output adds more), and most of it would be library code nobody asked about. So the design is a funnel, where every stage is cheaper than the next:

1. **Cut to first-party code.** Built: manifest-derived allow-list, generated code excluded. Next: library fingerprinting by code hashes, not package names, because obfuscation renames packages.
2. **Map the attack surface without an LLM.** Entry points from the manifest (exported components, deep links, providers, receivers), sinks (WebView loading, file and SQL APIs, exec, crypto, logging, setResult), and a call graph. Keep code on a path from an attacker-reachable entry to a sink. Units become these slices, not files. Classes everyone calls get a short summary instead of their full body.
3. **Tier the models.** This is the point of the teacher-to-student loop: the cheap student scans every slice with the lessons; the teacher reviews only the riskiest slices, re-checks student findings, and scores a random sample to watch the student's quality drift.
4. **Pay once for shared context.** System prompt, lessons and manifest are the same for every unit: prompt caching. Non-urgent scans go through batch APIs.
5. **Incremental rescans.** Units are already cached by content hash; a new release rescans only changed slices and keeps finding identities stable across versions.
6. **Operations.** Queue plus worker pool with per-provider rate limits (built: concurrency and token budgets per model), retries, resume from any stage (built), every number traceable to a run folder (built).

Hard parts, said plainly: flows that cross many units (method summaries composed across the call graph, or letting the model ask for callers and callees within a budget); reflection and string-named intents that break the call graph; obfuscation; native code, which this scanner does not read.

## 4. Models used and why

- **Teacher: claude-sonnet-5-5** ($2/$10 per million tokens), adaptive thinking, effort high. The brief asks for Sonnet class. Its temperature cannot be set, so the teacher runs once and is cached.
- **Student: qwen3.8-27b**, open weights. Started on Groq's free tier; its limits (8K tokens per minute for this model) forced small chunks, and the student lost cross-file context. Moved to Pollinations (one 120K-token request per app). Needs `reasoning_effort: none`, otherwise it spends its whole output budget thinking and returns nothing. Temperature 0 with a fixed seed; variance is produced on purpose by shuffling file order (and, in a separate experiment, by sampling at 0.7).
- **Judge: deepseek-v4.1-flash**, chosen to be a third family so neither the teacher's nor the student's family grades itself; cheap enough to judge every pair.
- **Adding a model** is a config entry; a new provider is one adapter class plus one registry line.

## 5. Limitations (short list for the doc)

- In-sample lift: lessons come from the app they are tested on. The one cross-app test (OVAA) showed no transfer.
- InsecureShop and its list are public; models may have seen them.
- The judge audit labels were produced by Claude, not by a person; until I review them they are a second model's opinion, not a human check.
- README items have no places; per-item credit can land on a neighbouring WebView item (totals checked by place, see section 1).
- Small N: 3 repeats per arm, no significance test.
- Static only; native code and Flutter/React Native bundles are not read.

## 6. Held-out testing (for the interview)

- Leave-one-app-out over several deliberately vulnerable apps (OVAA, InsecureBankv2, DIVA, AndroGoat and others): distil lessons from the other apps, test on the held-out one.
- Hold out vulnerability classes too: does a lesson about exported components help on a class the teacher never saw?
- Contamination control: renamed or obfuscated copies of the public apps, or private apps with planted bugs.
- Report the mean and spread over repeats and apps; the OVAA result (+0.3) is the honest starting point.
