# Results

Copies of the key run folders' reports, so they can be read without running anything. Each run folder under `runs/`
(gitignored) holds everything else: raw model answers, plans, usage and the manifest with config and prompt hashes.
Open the `report.html` files in a browser.

| Folder | What it shows |
|---|---|
| `insecureshop-v4-headline/` | **Headline.** Scan prompt v4, file order shuffled per repeat, 3 runs per student arm. Student recall 9.7 → 14.0 of 19 (+4.3 items, +22.8 points). |
| `insecureshop-v3-with-judge-audit/` | Earlier deterministic run (prompt v3) with the judge audit: 95% agreement, Cohen's kappa 0.79. The audit labels were drafted by a second model (Claude) and reviewed by the author. |
| `ovaa-transfer-v4/` | Cross-app check: InsecureShop's lessons given to the student on OVAA, an app it was never tuned on. +0.3 items: the lessons did not transfer. |
| `ovaa-no-ground-truth/` | The unseen-app path: OVAA with no answer list. The teacher's findings are the reference, so the numbers are "agreement with teacher", not recall. |
| `markor-real-apk/` | The optional real-APK run: Markor, an open-source app from F-Droid, no answer list. **Redacted copy:** one high-severity issue and the lesson derived from it are removed until the maintainers have been notified; everything else is as generated. Only the report is included (the raw findings would contain the withheld issue). |
| `comparison.html` / `.md` | All 11 experiments in one table (prompt versions, sampling vs shuffled order, both references). |

Each folder has `report.html` / `report.md`, `metrics.json`, the lessons the student saw (`lessons.json`) and every
arm's findings (`findings/`).
