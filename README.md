# astra-vulnscan

A static, LLM-based vulnerability scanner for Android apps with a teacher-to-student loop. A frontier model
(the **teacher**, Claude Sonnet 5.5) scans a decompiled APK and turns its findings into general lessons; a small open
model (the **student**, Qwen 3.8 27B) scans the same app with and without those lessons; a third model (the **judge**,
DeepSeek v4.1 Flash) matches every finding against a reference list, and the tool reports how much the student's recall
improved. Java 21, Spring Boot (non-web) + picocli, built and tested with project-local tools only.

## Results

**InsecureShop** (19 known vulnerabilities from its README; scan prompt v4; file order shuffled per repeat; strict
judge, verified evidence only). Full report: [`results/insecureshop-v4-headline/report.html`](results/insecureshop-v4-headline/report.html)

| Arm | Model | Found (3 runs) | Mean recall |
|---|---|---|---|
| Teacher (ceiling) | claude-sonnet-5-5 | 15 | 79% |
| Student, no lessons | qwen/qwen3.8-27b | 9, 10, 10 | 9.7 / 19 (51%) |
| Student, with lessons | qwen/qwen3.8-27b | 15, 14, 13 | 14.0 / 19 (74%) |

**Lift: +4.3 vulnerabilities (+22.8 points); the two ranges do not overlap.** Checked four more ways:
- Against an enriched reference (same items with CWEs and places): +28.1 points.
- Place check (a credit counts only if the finding is in a file the item names): +3.7 to +4.3.
- Judge stability: the judge's shortlist step runs in 3 orders; re-evaluating all 11 experiments this way moved 2 of 18 rows by one run.
- Where it comes from: dynamic code loading, file theft and the insecure broadcast receiver go from 0/3 to 3/3 runs.

**Honest limits.** The lessons were distilled from the same app, so this lift is in-sample. On a second app (OVAA, 18
known vulnerabilities) the same lessons gave **+0.3 items: no transfer** ([report](results/ovaa-transfer-v4/report.html)).
All 11 experiments (prompt versions, sampling vs shuffled order, both references): [`results/comparison.html`](results/comparison.html).
What each results folder contains: [`results/README.md`](results/README.md).

**A real app.** The teacher also scanned Markor, an open-source Markdown editor from F-Droid (39 units, 1.22M input
tokens, $2.90). Its 31 findings were 11 distinct issues: 6 real (1 high, 5 low) and 5 noise. Details of the real issues
are withheld until the maintainers have been notified. The scan also exposed what breaks on a real app; those fixes are
described in [`BUILD_LOG.md`](BUILD_LOG.md).

## Run it with Docker (only API keys and an APK needed)

1. **Keys.** `cp .env.example .env` and fill in:
   - `ANTHROPIC_API_KEY`: the teacher.
   - `POLLINATIONS_API_KEY`: the student and the judge ([pollinations.ai](https://pollinations.ai)).
   - `GROQ_API_KEY`: only for the older Groq entries in `configs/models.yaml`; can stay empty.

2. **APKs** go in `apps/` (gitignored). Each config checks the file's sha256 before decompiling.

   | App | Download | Save as | sha256 |
   |---|---|---|---|
   | InsecureShop | [releases](https://github.com/hax0rgb/InsecureShop/releases) | `apps/InsecureShop.apk` | `a83298ae4a37fcab8101e8b41e513dd2199af71a94ea537d556a318e07d4d1bd` |
   | OVAA | [releases](https://github.com/oversecured/ovaa/releases) (1.0.0, `app-debug.apk`) | `apps/OVAA.apk` | `073d87a2bcbfcd539146854681d4968960dbee0209673b17cdbe1aaed6705233` |

3. **Build** (runs the 163 unit tests inside the build; they use fake models, no network):
   ```bash
   docker build -t vulnscan .
   ```

4. **Run** the whole pipeline: decompile → ingest → plan → teacher scan → student scan → lessons → student rescan → evaluate → report.
   ```bash
   docker run --rm --env-file .env \
     -v "$PWD/apps:/app/apps" -v "$PWD/runs:/app/runs" -v "$PWD/cache:/app/cache" \
     vulnscan run --config configs/insecureshop.yaml
   ```
   The run folder appears under `runs/<time>_<app>_<config hash>/` with `report.html`. On Linux add
   `--user "$(id -u):$(id -g)"` so the container can write to the mounted folders.

To reproduce the headline, use `configs/insecureshop_scan_v4_perturb.yaml` (3 runs per student arm, lessons from
`lessons/`). The OVAA transfer configs are `configs/ovaa_transfer*.yaml`.

### A new APK (no answer list)

Copy [`configs/example_new_app.yaml`](configs/example_new_app.yaml), set `app.name` and `app.input`, and run it.
Nothing in the code is specific to InsecureShop (a test greps `src/` and `prompts/` for app names). Without a ground
truth, the teacher's verified findings become the reference, so the report shows **agreement with the teacher**, not
recall, and says so at the top. Example: [`results/ovaa-no-ground-truth/report.html`](results/ovaa-no-ground-truth/report.html).

If the app bundles its own library code outside the manifest package, add it with
`ingest.extra_first_party_prefixes`; see `configs/markor.yaml`.

## Run it locally (no Docker)

All tools are installed project-locally into `.tools/`, pinned and checksum-verified; nothing global.

```bash
bash scripts/bootstrap.sh            # JDK 21 + jadx 1.5.6 (+ Maven via the wrapper). macOS arm64 by default;
                                     # other platforms: set JDK_URL and JDK_SHA256 first
source scripts/env.sh
./mvnw -q package                    # build + tests; -DskipTests to skip
scripts/vulnscan run --config configs/insecureshop.yaml
```

`scripts/vulnscan` runs a private copy of the jar and refuses to start if the sources are newer than the jar.

## Commands

| Command | What it does |
|---|---|
| `run --config C [--resume DIR]` | The whole pipeline in a new run folder, or continue one (refused if the config changed). |
| `scan --config C --arm A` | One arm: `teacher`, `student_baseline` or `student_with_lessons`. |
| `evaluate --config C --run DIR [--screen-orders N]` | Re-judge a run's saved answers without rescanning (new matcher, judge or evidence rules). Recorded in the manifest. |
| `compare DIR... --out F.md [--html F.html]` | One table over several run folders. |
| `tokens --config C` | Scan units and token estimates per model, with no model calls. |
| `audit export` / `audit score` | Blind hand audit of the judge, and agreement / kappa. |
| `gt-from-readme` | Convert a README vulnerability list into a ground-truth YAML. |

## Costs

| Model | Billed in | Typical use |
|---|---|---|
| Teacher, claude-sonnet-5-5 ($2 / $10 per M tokens) | Anthropic credit | InsecureShop whole-app scan ≈ $0.13; lessons ≈ $0.06–0.07 |
| Student and judge on Pollinations | Pollen | A few thousandths to a few hundredths of a Pollen per run |

Every model answer is cached on disk by the exact request, so a rerun with the same settings costs nothing. Each run's
usage tables show billed and cached calls separately; `cached_cost` is what a fresh run would pay. Claude's tokenizer
counts about 1.5× the planner's estimate on decompiled Java, so budget teacher costs from `tokens` × 1.5.

## How it works

| Stage | Package | Notes |
|---|---|---|
| Decompile | `ingest` | jadx, cached by APK hash; partial success accepted and counted |
| Ingest | `ingest` | Manifest (effective exported, deep links, permissions), first-party code (allow-list from the manifest), resources, secret patterns, dependency inventory |
| Plan | `planning` | Whole app in one request if it fits, else units: one per entry point plus its one-hop neighbours, the rest grouped by package. Teacher and student get the same units. |
| Scan | `scan` | Structured JSON findings (type, CWE, location, severity, evidence); evidence must be found in the decompiled code; lossless dedup; repair, truncation retry and re-planning |
| Lessons | `learn` | Teacher distils general rules in one pass; a sanitiser removes app-specific names, paths, literals and near-copies of code |
| Match | `matching` | Structured pre-score → judge shortlist (3 orders, union) → pairwise judge (match / partial / no) → one-to-one assignment (Hungarian) |
| Evaluate, report | `evaluate`, `report` | Recall strict / lenient / incl. unverified, precision, per-item grid, robustness check, usage, prompt hashes, limitations |

**Adding a model** is an entry in `configs/models.yaml` (provider, model id, limits, prices, params) and pointing a
role at it. **Adding a provider** is one class implementing `LlmClient` plus one line in `ProviderRegistry`. Retries,
rate limits, caching and usage tracking wrap every client as decorators. Prompts are versioned files in `prompts/`;
each run records the sha256 of every prompt it used.

## Documents

- [`docs/WRITEUP_DRAFT.md`](docs/WRITEUP_DRAFT.md): the write-up (match function, a 1M+ line app, models and why, limitations, held-out testing).
- [`docs/DYNAMIC.md`](docs/DYNAMIC.md): extending this to dynamic analysis, with a diagram.
- [`PLAN.md`](PLAN.md): the plan the build followed. [`BUILD_LOG.md`](BUILD_LOG.md): progress, every deviation from the plan with its reason, and every measurement.

## Main limitations

- One app's lift is in-sample; the cross-app test showed no transfer. InsecureShop and its list are public, so models may have seen them.
- Small N (3 runs per arm), no significance test. The judge is an LLM; its audit labels were produced by a second model.
- README items have no locations, so per-item credit can land on a neighbouring item (totals checked by place).
- Static only: no native code, no React Native or Flutter bundles, no split APKs; the code graph is text-based and one hop.
- Full list: section 8 of each report and `BUILD_LOG.md`.
