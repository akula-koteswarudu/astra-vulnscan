# Scan report: markor

**Redacted copy.** One high-severity issue in this app has not been reported publicly yet. Its finding, the lesson distilled from it, and the guidance text derived from it are removed from this copy until the maintainers have been notified. Everything else is unchanged. Severity note: the teacher rated the exported-activity file-path issue high; the author's review confirmed the code issue but not its impact, so it is counted as severity unconfirmed.

Run `2026-11-15T1528_markor_49247051` · profile `assignment` · generated from the run folder (every number below is traceable to a file in it).

**No ground truth for this app.** The teacher's verified findings are the reference, so every number is *agreement with teacher*, not recall. The lessons were distilled from the same findings used as the reference, so this comparison is circular and favours the with-lessons arm. The teacher ceiling and precision are omitted.

## 1. Core result

Headline: how many of the teacher's verified findings the student also reports, before and after the lessons.

| Arm | Model | Agreement with teacher (strict, verified) | Lenient | Incl. unverified | Findings (verified / total) |
|---|---|---|---|---|---|
| student baseline | qwen/qwen3.8-27b | **6/21 (29%)** | 7/21 (33%) | 7/21 (33%) | 10 / 11 |
| student with lessons | qwen/qwen3.8-27b | **15/21 (71%)** | 15/21 (71%) | 16/21 (76%) | 16 / 17 |

**Lift: +42.9 points of agreement with teacher (6/21 (29%) → 15/21 (71%), strict, verified). One run per arm: LLM output varies between runs, so a lift of one or two items is within run-to-run noise; small N, not a significance test.**

Strict = the judge said "match"; lenient also counts "partial". One-to-one assignment (Hungarian): each reference item is matched by at most one finding and vice versa. 

### Per item (teacher findings, 21 items)

✓ = strict match, ◐ = the judge said match but that finding was credited to another item (one finding counts once), ~ = partial only, · = not matched. Static visibility is the author's label: *partial* and *inventory_only* items cannot be fully seen in the client code.

| Item | Title | Static visibility | student baseline | student with lessons |
|---|---|---|---|---|
| T-01 | REDACTED: high-severity issue withheld until the maintainers are notified | full | · | · |
| T-02 | Settings backup written as plaintext JSON to caller-chosen file | full | · | ✓ |
| T-03 | Unescaped URL-decoded image parameters and href injected into HTML in wikitext preview | full | · | ✓ |
| T-04 | Application data can be backed up (allowBackup=true) | full | ✓ | ✓ |
| T-05 | Encryption password kept in preferences while backups are enabled | full | ◐ | ✓ |
| T-06 | Exported DocumentActivity opens arbitrary file:// and content:// URIs from other apps | full | ✓ | · |
| T-07 | Preview WebView has JavaScript enabled, a JS bridge and file/content access while rendering untrusted documents | full | ✓ | ✓ |
| T-08 | Untrusted document HTML rendered in WebView with file:// base URL | full | ✓ | ✓ |
| T-09 | Exported DocumentActivity opens and writes arbitrary file paths supplied by caller or browser deep link | full | ◐ | · |
| T-10 | Untrusted Markdown rendered to HTML without escaping raw HTML, with a JS bridge in the page | full | ◐ | ✓ |
| T-11 | Unescaped M3U playlist name/URL concatenated into JavaScript executed in preview WebView | full | · | ✓ |
| T-12 | Exported MainActivity accepts file path from intent without caller validation | full | · | ✓ |
| T-13 | File path taken from intent extra or markor://...?path= URI without validation | full | ✓ | ✓ |
| T-14 | Markdown parser emits raw HTML and unvalidated URLs into href/src attributes | full | · | ✓ |
| T-15 | Org-mode content rendered via innerHTML in WebView preview (possible script injection) | full | ◐ | ✓ |
| T-16 | Undo/redo history with note text stored in plaintext SharedPreferences | full | · | ✓ |
| T-17 | Untrusted AsciiDoc content rendered into WebView via innerHTML (script injection) | full | · | · |
| T-18 | Application allows cleartext HTTP traffic | full | ✓ | ✓ |
| T-19 | FileProvider exposes the entire filesystem root and external storage | full | ~ | · |
| T-20 | Exported widget receiver reachable by any app | full | · | ✓ |
| T-21 | Exported DocumentActivity trusts caller-supplied file paths (EXTRA_FILE, EXTRA_FILEPATH, file:// and markor://open?path=) | full | ◐ | ~ |

### Findings (run 1 of each arm)

Every finding after evidence checking and dedup, highest severity first. "Credited" is the reference item it was assigned to (strict); "-" means it matched no item, which does not by itself make it a false positive.

<details><summary>teacher: 21 finding(s)</summary>

| # | Finding | Severity | CWE | Where | Evidence | Credited |
|---|---|---|---|---|---|---|
| 1 | Exported DocumentActivity opens and writes arbitrary file paths supplied by caller or browser deep link | high |  | `net/gsantner/markor/util/MarkorContextUtils.java:46 (MarkorContextUtils.getIntentFile) +2 more` | verified_exact | - |
| 2 | REDACTED: high-severity issue withheld until the maintainers are notified | high | | | | - |
| 3 | Application allows cleartext HTTP traffic | medium | CWE-319 | `AndroidManifest.xml +1 more` | verified_exact | - |
| 4 | Exported DocumentActivity trusts caller-supplied file paths (EXTRA_FILE, EXTRA_FILEPATH, file:// and markor://open?path=) | medium | CWE-73 | `net/gsantner/markor/util/MarkorContextUtils.java:38 (MarkorContextUtils.getIntentFile) +2 more` | verified_exact | - |
| 5 | Exported MainActivity accepts file path from intent without caller validation | medium | CWE-200 | `AndroidManifest.xml (MainActivity.onCreate)` | relocated | - |
| 6 | File path taken from intent extra or markor://...?path= URI without validation | medium | CWE-22 | `net/gsantner/markor/util/MarkorContextUtils.java:46 (MarkorContextUtils.getIntentFile) +1 more` | verified_exact | - |
| 7 | FileProvider exposes the entire filesystem root and external storage | medium | CWE-732 | `resources/res/xml/provider_paths.xml` | verified_exact | - |
| 8 | Org-mode content rendered via innerHTML in WebView preview (possible script injection) | medium | CWE-79 | `net/gsantner/markor/format/orgmode/OrgmodeTextConverter.java:19 (OrgmodeTextConverter.convertMarkup)` | verified_exact | - |
| 9 | Preview WebView has JavaScript enabled, a JS bridge and file/content access while rendering untrusted documents | medium | CWE-749 | `net/gsantner/markor/activity/DocumentEditAndViewFragment.java:947 (DocumentEditAndViewFragment.setViewModeVisibility) +1 more` | verified_exact | - |
| 10 | Unescaped M3U playlist name/URL concatenated into JavaScript executed in preview WebView | medium | CWE-79 | `net/gsantner/markor/format/binary/EmbedBinaryTextConverter.java:93 (EmbedBinaryTextConverter.convertMarkup) +1 more` | verified_exact | - |
| 11 | Unescaped URL-decoded image parameters and href injected into HTML in wikitext preview | medium | CWE-79 | `net/gsantner/markor/format/wikitext/WikitextTextConverter$$ExternalSyntheticLambda5.java:70 (WikitextTextConverter.apply)` | verified_exact | - |
| 12 | Untrusted AsciiDoc content rendered into WebView via innerHTML (script injection) | medium |  | `net/gsantner/markor/format/asciidoc/AsciidocTextConverter.java:25 (AsciidocTextConverter.convertMarkup)` | verified_exact | - |
| 13 | Untrusted Markdown rendered to HTML without escaping raw HTML, with a JS bridge in the page | medium | CWE-79 | `net/gsantner/markor/format/markdown/MarkdownTextConverter.java:272 (MarkdownTextConverter.convertMarkup) +1 more` | verified_exact | - |
| 14 | Untrusted document HTML rendered in WebView with file:// base URL | medium | CWE-79 | `net/gsantner/markor/format/TextConverterBase.java:75 (TextConverterBase.convertMarkupShowInWebView)` | verified_exact | - |
| 15 | Application data can be backed up (allowBackup=true) | low | CWE-922 | `AndroidManifest.xml` | verified_exact | - |
| 16 | Encryption password kept in preferences while backups are enabled | low | CWE-312 | `res/values/strings.xml` | verified_exact | - |
| 17 | Exported DocumentActivity opens arbitrary file:// and content:// URIs from other apps | low | CWE-927 | `AndroidManifest.xml` | verified_exact | - |
| 18 | Exported widget receiver reachable by any app | low | CWE-926 | `AndroidManifest.xml (TodoWidgetProvider)` | verified_exact | - |
| 19 | Markdown parser emits raw HTML and unvalidated URLs into href/src attributes | low | CWE-79 | `net/gsantner/opoc/format/GsSimpleMarkdownParser.java:42 (GsSimpleMarkdownParser.AnonymousClass2.filter)` | verified_exact | - |
| 20 | Settings backup written as plaintext JSON to caller-chosen file | low | CWE-312 | `net/gsantner/opoc/util/GsBackupUtils.java:155 (GsBackupUtils.makeBackup)` | verified_exact | - |
| 21 | Undo/redo history with note text stored in plaintext SharedPreferences | low | CWE-312 | `net/gsantner/opoc/frontend/textview/TextViewUndoRedo.java:411 (TextViewUndoRedo.storePersistentState)` | verified_exact | - |

</details>

<details><summary>student_baseline: 11 finding(s)</summary>

| # | Finding | Severity | CWE | Where | Evidence | Credited |
|---|---|---|---|---|---|---|
| 1 | Exported Activity accepts arbitrary file URIs | high | CWE-926 | `AndroidManifest.xml +1 more` | verified_exact | T-06 |
| 2 | Exported Activity accepts arbitrary file paths via deep link | high | CWE-22 | `net/gsantner/markor/util/MarkorContextUtils.java:44 (MarkorContextUtils.getIntentFile) +2 more` | verified_exact | T-13 |
| 3 | FileProvider exposes entire external storage | high | CWE-22 | `resources/res/xml/provider_paths.xml` | verified_exact | - |
| 4 | WebView allows file access from content URLs | high | CWE-749 | `net/gsantner/markor/activity/DocumentEditAndViewFragment.java:958 (DocumentEditAndViewFragment.setViewModeVisibility) +2 more` | verified_exact | T-07 |
| 5 | WebView loads untrusted content with JavaScript enabled | high | CWE-79 | `net/gsantner/markor/format/TextConverterBase.java:75 (TextConverterBase.convertMarkupShowInWebView)` | verified_exact | T-08 |
| 6 | Backup enabled without encryption | medium | CWE-922 | `AndroidManifest.xml` | verified_exact | T-04 |
| 7 | Backup includes sensitive data without encryption | medium | CWE-312 | `AndroidManifest.xml` | unverified | - |
| 8 | Cleartext traffic allowed | medium | CWE-319 | `AndroidManifest.xml` | verified_exact | T-18 |
| 9 | Exported SettingsActivity allows configuration tampering | medium | CWE-668 | `AndroidManifest.xml` | verified_exact | - |
| 10 | Sensitive data included in debug logs | medium | CWE-532 | `net/gsantner/markor/activity/DocumentEditAndViewFragment.java:626 (DocumentEditAndViewFragment.onOptionsItemSelected)` | verified_exact | - |
| 11 | Legacy external storage access | low | CWE-923 | `AndroidManifest.xml` | verified_exact | - |

</details>

<details><summary>student_with_lessons: 17 finding(s)</summary>

| # | Finding | Severity | CWE | Where | Evidence | Credited |
|---|---|---|---|---|---|---|
| 1 | Default password stored in plaintext SharedPreferences | high | CWE-312 | `net/gsantner/markor/frontend/MarkorDialogFactory$$ExternalSyntheticLambda49.java:36 (MarkorDialogFactory.callback)` | verified_exact | T-05 |
| 2 | Exported Activity accepts unvalidated file path from Intent | high | CWE-73 | `net/gsantner/markor/util/MarkorContextUtils.java:46 (MarkorContextUtils.getIntentFile)` | verified_exact | T-13 |
| 3 | FileProvider exposes entire filesystem root | high | CWE-732 | `resources/res/xml/provider_paths.xml` | unverified | - |
| 4 | Unsanitized Markdown content rendered in WebView | high | CWE-79 | `net/gsantner/markor/format/markdown/MarkdownTextConverter.java:272 (MarkdownTextConverter.convertMarkup)` | verified_exact | T-10 |
| 5 | Unsanitized user content rendered in WebView with JavaScript enabled | high | CWE-79 | `net/gsantner/markor/format/orgmode/OrgmodeTextConverter.java (OrgmodeTextConverter.convertMarkup)` | verified_fuzzy | T-15 |
| 6 | Unvalidated file path from deep link | high | CWE-22 | `net/gsantner/markor/util/MarkorContextUtils.java:46 (MarkorContextUtils.getIntentFile) +2 more` | verified_exact | T-12 |
| 7 | WebView with JavaScript and File Access enabled for untrusted content | high | CWE-79 | `net/gsantner/markor/activity/DocumentEditAndViewFragment.java:947 (DocumentEditAndViewFragment.setViewModeVisibility)` | verified_fuzzy | - |
| 8 | Cleartext HTTP traffic permitted | medium | CWE-319 | `AndroidManifest.xml` | verified_exact | T-18 |
| 9 | Plaintext settings export to caller-chosen location | medium | CWE-312 | `net/gsantner/opoc/util/GsBackupUtils.java:154 (GsBackupUtils.makeBackup)` | verified_exact | T-02 |
| 10 | Sensitive data stored in plaintext SharedPreferences | medium | CWE-312 | `net/gsantner/opoc/frontend/textview/TextViewUndoRedo.java:411 (TextViewUndoRedo.storePersistentState)` | verified_exact | T-16 |
| 11 | Unsanitized HTML injection in Wikitext image conversion | medium | CWE-79 | `net/gsantner/markor/format/wikitext/WikitextTextConverter$$ExternalSyntheticLambda5.java:77 (WikitextTextConverter.apply)` | verified_exact | T-03 |
| 12 | Unsanitized Markdown to HTML conversion | medium | CWE-79 | `net/gsantner/opoc/format/GsSimpleMarkdownParser.java:30 (GsSimpleMarkdownParser.AnonymousClass2.filter)` | verified_fuzzy | T-14 |
| 13 | Untrusted HTML loaded with file:// base URL in WebView | medium | CWE-79 | `net/gsantner/markor/format/TextConverterBase.java:75 (TextConverterBase.convertMarkupShowInWebView)` | verified_exact | T-08 |
| 14 | Untrusted file name and playlist URLs injected into JavaScript string literals in generated HTML | medium | CWE-79 | `net/gsantner/markor/format/binary/EmbedBinaryTextConverter.java:93 (net.gsantner.markor.format.binary.EmbedBinaryTextConverter.convertMarkup)` | verified_exact | T-11 |
| 15 | WebView allows file access and content access with JavaScript enabled | medium | CWE-749 | `net/gsantner/markor/activity/DocumentEditAndViewFragment.java:956 (DocumentEditAndViewFragment.setViewModeVisibility)` | verified_fuzzy | T-07 |
| 16 | Backup enabled with potentially sensitive data | low | CWE-921 | `AndroidManifest.xml` | verified_exact | T-04 |
| 17 | Exported AppWidgetProvider receiver without permission | low | CWE-926 | `AndroidManifest.xml` | verified_exact | T-20 |

</details>

## 2. Lessons

12 lessons kept after sanitising, distilled in one teacher call from 31 verified teacher findings; 12 selected for the student (3099 tokens, within the reserved budget).

Guidance block shown to the student (verbatim, identical in every unit):

```text
- L01 Cleartext HTTP permitted via manifest flag (CWE-319, MASVS-NETWORK)
  Applies to: Application; APIs: android:usesCleartextTraffic, HttpURLConnection, networkSecurityConfig, WebView.loadUrl
  Look for: usesCleartextTraffic="true" on <application>; Network or WebView loads of URLs with no https enforcement; Downloaded bytes written to disk or rendered without integrity checks
  Why: A network-position attacker can read or modify any HTTP response the app loads, such as rendered content or downloaded files, because the platform does not block plain HTTP.
  Not a finding when: Cleartext is limited to localhost or specific dev domains in network security config; App makes no network requests of user-influenced URLs
  Severity: Medium if a real HTTP call site or WebView load exists; otherwise low.
  Example: <application android:usesCleartextTraffic="true"> new URL(<url>).openConnection().getInputStream()

- L02 allowBackup enabled without restrictive backup rules (CWE-921, MASVS-STORAGE)
  Applies to: Application; APIs: android:allowBackup, android:fullBackupContent, android:dataExtractionRules
  Look for: allowBackup="true" explicit or defaulted; Backup rules absent or without exclude entries for prefs and files; App stores notes, tokens or secrets in internal storage
  Why: With adb backup or cloud backup, someone with device or account access can extract shared preferences and internal files that should stay private.
  Not a finding when: Backup rules exclude all sensitive files and preference domains; App stores no sensitive data locally
  Severity: Low alone; raise when secrets or private user content are in backed-up files.
  Example: <application android:allowBackup="true" android:fullBackupContent="@xml/<rules>">

- L03 Secrets or user content in plaintext SharedPreferences (CWE-312, MASVS-STORAGE)
  Applies to: Application, Service; APIs: SharedPreferences.Editor.putString, getSharedPreferences, EncryptedSharedPreferences, android:allowBackup
  Look for: putString of passwords or user-typed text into default preferences; Preference key naming a password or encryption key; Backups enabled, so prefs leave the sandbox
  Why: Preferences are stored unencrypted. Backups, a rooted device or another weakness can expose them, including a password that protects other user data.
  Not a finding when: Value is encrypted with a Keystore-backed key before storage; Prefs file is excluded from backup and holds only non-sensitive settings
  Severity: Low to medium; higher for passwords or keys that unlock other data.
  Example: prefs.edit().putString(<prefsKey>, <secretOrUserText>).apply()

- L04 Exported activity opens caller-supplied file path unvalidated (CWE-73, MASVS-PLATFORM)
  Applies to: Activity (exported), Browsable deep link; APIs: getSerializableExtra, Intent.getData, Uri.getQueryParameter, new File
  Look for: Exported or BROWSABLE activity builds File from extra, URI path or query parameter; No canonicalization, allow-listed directory or caller check before open or save; App holds broad storage access or opens its own private files
  Why: Any app or web page can steer the component to read, display, share or overwrite files the app can access, including private data, using the app's own UID and permissions.
  Not a finding when: Path is canonicalized and confined to an allow-listed directory before use; Component is not exported and has no browsable filter
  Severity: High if file write or overwrite is reachable; medium for read-only exposure.
  Example: File f = new File(intent.getData().getQueryParameter("<pathKey>")); open(f);

- L05 REDACTED (the lesson distilled from the withheld high-severity issue)

- L06 Exported widget or update receiver without permission (CWE-926, MASVS-PLATFORM)
  Applies to: BroadcastReceiver (exported), AppWidgetProvider; APIs: android:exported, AppWidgetProvider.onReceive, PendingIntent.getActivity, android:permission
  Look for: Receiver exported="true" with no android:permission; onReceive acts on extras such as ids without sender checks; Action handling goes beyond standard widget update
  Why: Any app can send spoofed broadcasts to trigger refreshes or actions; impact depends on what onReceive does with attacker-supplied extras.
  Not a finding when: Only the system sends APPWIDGET_UPDATE and handler ignores untrusted extras; Receiver protected by signature permission
  Severity: Low when it only refreshes UI; higher if it performs sensitive actions.

- L07 FileProvider paths expose root or whole storage (CWE-732, MASVS-PLATFORM)
  Applies to: FileProvider, Provider; APIs: FileProvider.getUriForFile, root-path, external-path, grantUriPermissions
  Look for: provider paths XML with root-path path="/" or broad external-path; grantUriPermissions="true" on the provider; Code adds FLAG_GRANT_READ_URI_PERMISSION to an intent carrying a caller-influenced URI
  Why: A broad path map lets getUriForFile build URIs for private files. If an attacker steers a grant, another app reads shared_prefs or databases.
  Not a finding when: Paths are limited to a dedicated subdirectory and no private data is mapped; No code path grants or returns URIs from attacker-influenced input
  Severity: Medium; high if a caller-controlled file feeds getUriForFile with a grant.
  Example: <root-path name="<n>" path="/"/> FileProvider.getUriForFile(ctx, <auth>, <anyFile>)

- L08 WebView with JS, bridge and file access renders untrusted content (CWE-749, MASVS-PLATFORM)
  Applies to: Activity, Fragment; APIs: WebView.addJavascriptInterface, WebSettings.setJavaScriptEnabled, WebSettings.setAllowFileAccess, WebSettings.setAllowContentAccess
  Look for: setJavaScriptEnabled(true) with addJavascriptInterface on a content-rendering WebView; File or content access enabled in a WebView showing opened documents; Document reaches the WebView from an exported VIEW, EDIT or SEND filter
  Why: Script embedded in an attacker-supplied document runs with the bridge and can call @JavascriptInterface methods or read local files through file access.
  Not a finding when: WebView loads only trusted bundled content with JS and file access disabled; Bridge exposes no sensitive methods and the content is sanitized
  Severity: Medium to high when untrusted documents reach the WebView; check the bridge methods.
  Example: ws.setJavaScriptEnabled(true); ws.setAllowFileAccess(true); wv.addJavascriptInterface(<obj>, "<name>");

- L09 Untrusted HTML loaded with file:// base URL (CWE-79, MASVS-PLATFORM)
  Applies to: Activity, Fragment; APIs: WebView.loadDataWithBaseURL, WebView.loadData, WebSettings.setAllowFileAccessFromFileURLs, WebSettings.setAllowUniversalAccessFromFileURLs
  Look for: loadDataWithBaseURL with file:// base and HTML derived from user documents; Base URL built from the opened file's parent directory; File-URL access settings enabled or left at defaults for old API levels
  Why: Script in the crafted document runs in a file origin and may read sibling local files or other file:// resources, then leak them.
  Not a finding when: JavaScript is disabled and file-URL access is off; Base URL is null or https and the content is sanitized
  Severity: Medium; depends on WebView JS and file-access settings.
  Example: wv.loadDataWithBaseURL("file://" + <dir> + "/", <docHtml>, "text/html", "<String>", null);

- L10 Unsanitized markup-to-HTML conversion for WebView preview (CWE-79, MASVS-PLATFORM)
  Applies to: Activity, Fragment, Converter/Renderer; APIs: WebView.loadDataWithBaseURL, Element.innerHTML, HtmlRenderer.render, String.replaceAll
  Look for: Markdown, org or asciidoc output assigned to innerHTML or loaded without sanitizing; Parser configured without HTML escape; link or image targets inserted into href or src; Raw HTML or javascript: links pass through from opened files
  Why: A crafted or shared document injects script or HTML into the preview. With JS enabled it can reach bridges or local files.
  Not a finding when: Output passes through an HTML sanitizer or the parser escapes raw HTML; Preview WebView has JavaScript disabled
  Severity: Medium with JS-enabled WebView; low if rendering config is unconfirmed.
  Example: el.innerHTML = <<String>>(<untrustedDoc>);

- L11 Untrusted values concatenated into JS or HTML attributes (CWE-79, MASVS-PLATFORM)
  Applies to: Activity, Converter/Renderer; APIs: WebView.evaluateJavascript, WebView.loadUrl, URLDecoder.decode, StringBuilder.append
  Look for: File-derived names or URLs concatenated inside quoted JS string literals; URL-decoded values written into HTML attributes without quote escaping; javascript: scheme allowed in generated href
  Why: A quote or decoded %22 breaks out of the literal or attribute, so attacker data becomes script or event handlers in the WebView.
  Not a finding when: Values are JSON-encoded or HTML-attribute escaped before insertion; Input is a validated whitelist, such as numeric width
  Severity: Medium; high if the WebView has a JS bridge or file access.
  Example: js.append("fn('").append(<itemName>).append("')"); wv.evaluateJavascript(js, null);

- L12 Plaintext settings export to caller-chosen location (CWE-312, MASVS-STORAGE)
  Applies to: Activity, Utility; APIs: SharedPreferences.getAll, FileWriter.write, JSONObject.toString, Environment.getExternalStorageDirectory
  Look for: All preferences serialized to JSON in shared storage; Weak key-name filter that excludes only 'password'; Restore reads preference values from file without integrity check
  Why: Other apps or anyone with storage access can read exported values. A tampered backup file silently changes settings on restore.
  Not a finding when: Export is encrypted, signed and written to app-private storage; Only an explicit allow-list of non-sensitive keys is exported
  Severity: Low to medium depending on sensitive values and storage location.
  Example: w.write(new JSONObject(prefs.getAll()).toString()); // to <userChosenFile>
```

Sanitiser (redact): 4 term(s) redacted, 2 example(s) removed by the answer-key check, 0 lesson(s) dropped; 0 lesson(s) left out by the count/token caps. Full records: 05_learn/rejected_lessons.json.

| Lesson | Field | Rule | Term | Action |
|---|---|---|---|---|
| L05 | abstract_example | answer_key_similarity | `` | example_removed |
| L05 | signals | string_literal | `..` | redacted |
| L05 | why_exploitable | string_literal | `..` | redacted |
| L06 | abstract_example | answer_key_similarity | `` | example_removed |
| L09 | abstract_example | string_literal | `UTF-8` | redacted |
| L10 | abstract_example | string_literal | `converter` | redacted |

## 3. Robustness check: README-only vs enriched reference

Not computed (no enriched ground truth for this app).

## 4. Judge audit

Not run on this run folder (an audit labels one run's judge decisions). The same judge model and prompts may have been audited on another run; see that run's report. To audit this run: `vulnscan audit export` writes blind rows to label, `vulnscan audit score` measures agreement.

## 5. Betterments

None enabled in this run (profile `assignment`): 1 run(s) per student arm, no checklist control arm.

## 6. Coverage and quality

| Arm | Units | Unit status | Evidence status | Bad findings dropped |
|---|---|---|---|---|
| teacher (ceiling) | 39 | ok 39 | relocated 1, verified_exact 20 | 0 |
| student baseline | 39 | ok 39 | unverified 1, verified_exact 10 | 0 |
| student with lessons | 39 | ok 39 | unverified 1, verified_exact 12, verified_fuzzy 4 | 0 |

- Decompilation: jadx 1.5.6, exit code 3 (partial success: output present); 77 failed-method markers in all code, 3 in first-party code.
- First-party split: 196 of 4984 Java files (prefixes net.gsantner.markor, net.gsantner.opoc); 2 build-generated files excluded from the app's own packages. Largest excluded groups: (top 5, 2698 of 4788 excluded files) com.vladsch (700), com.google (554), org.apache (534), kotlinx.coroutines (517), androidx.core (393).
- Units: teacher 39 (chunked), student 39 (chunked); the two models may scan different units because their budgets differ. All student arms use the same units.
- Judge: 64 confirm calls, 0 unparseable answers (counted as "no").

## 7. Usage

| Model | Purpose | Arm | Calls billed / cached | Input tokens | Output tokens | Cost (billed) |
|---|---|---|---|---|---|---|
| student_pollinations | repair | student_baseline | 1 / 0 | 83134 | 966 | $0.0000 |
| student_pollinations | scan | student_baseline | 38 / 1 | 674068 | 8266 | $0.0000 |
| student_pollinations | scan | student_with_lessons | 39 / 0 | 770632 | 15934 | $0.0000 |
| teacher | scan | teacher | 39 / 0 | 1219873 | 45642 | $2.8962 |
| teacher | distill | distill | 1 / 0 | 9871 | 5373 | $0.0735 |
| judge_pollinations | judge_confirm | teacher_findings:student_baseline | 0 / 34 | 18258 | 3661 | $0.0000 |
| judge_pollinations | judge_confirm | teacher_findings:student_with_lessons | 0 / 30 | 16093 | 3133 | $0.0000 |
| judge_pollinations | judge_screen | teacher_findings:student_baseline | 0 / 3 | 10794 | 784 | $0.0000 |
| judge_pollinations | judge_screen | teacher_findings:student_with_lessons | 0 / 3 | 12852 | 969 | $0.0000 |

Billed in this run: $2.9696. Including calls served from the response cache at their original price: $2.9696. Free-tier models are shown at $0 (tokens are still counted).

## 8. Prompt versions

| Prompt | sha256 |
|---|---|
| `distill/v2` | `8d23a5746d60` |
| `judge_confirm/v1` | `cbbc6bf53622` |
| `judge_screen/v1` | `0d8f84acdbc7` |
| `scan/v4` | `7cd4f30371a6` |

Prompts were written from the OWASP MASVS categories, not from the ground-truth list, but they were developed and debugged on this app. The scores in section 1 are for exactly these versions.

## 9. Limitations

- The lift is in-sample: lessons are distilled from the same app the student is rescanned on. One app cannot show that the lessons generalise (cross-app transfer is the partial answer). Next: leave-one-app-out over several apps, with lessons distilled from several apps and merged.
- Contamination: the evaluation app and its vulnerability list are public; the models may have memorised them. Next: renamed or obfuscated copies, or private apps with planted bugs.
- Some ingest features (dependency inventory, cloud-identifier patterns) were added after reading the ground-truth list; they are generic, but not chosen blind.
- Prompts were developed on this app (versions and scores are recorded in section 8).
- Ground-truth enrichment is the author's own labelling (mitigated: written and committed before any scan, README-only headline, robustness check).
- Some items are only partly visible statically (server-side cloud configuration; known-vulnerable components rely on the model's advisory knowledge).
- The teacher's temperature cannot be set (Claude Sonnet 5.5 rejects non-default sampling); LLM output varies between runs; small N; no significance test. Next: more repeats and bootstrap confidence intervals.
- Teacher refusals can reduce coverage (counted; the model that answered is recorded when a fallback was used).
- Teacher and student may scan different units because of different budgets; the student's free tier (1,000 output tokens per minute) forces small units, and units whose answer does not fit are re-planned into smaller ones.
- The judge is an LLM; the hand audit measures its agreement with the author, it does not remove bias. Next: a second human labeller, a stronger or reasoning judge, and an all-pairs check on a sample.
- One-to-one matching is conservative.
- Unmatched findings are not automatically false positives (the reference list may be incomplete).
- Teacher-as-reference (apps without ground truth) inherits the teacher's misses and mistakes and is circular with the lessons.
- Decompilation is lossy (jadx failures are counted). Next: a smali fallback for methods jadx cannot decompile.
- Obfuscated apps: first-party code is derived from the manifest, but library detection is not fingerprint-based. Next: fingerprint libraries by code hashes.
- Native code, assets (including React Native / Flutter bundles) and split APKs / app bundles are not analysed. Next: bundle and native-library readers; bundletool to merge splits.
- Text-based one-hop graph: reflection, framework callbacks and string-action intents are missed; oversized files are skipped; no hub/skeleton handling in v0.1. Next: a bytecode call graph (Androguard / SootUp) with entry-point-to-sink slices and summaries of shared classes.
- strings.xml filtering can miss innocently named, low-entropy secrets.
- Prompt injection via app code or strings can still influence the model.
- Model confidence is uncalibrated (used for ranking only).
- The sanitiser is lexical plus a similarity check; paraphrased references can pass. Next: a model-based leak check per lesson.
- Lessons cover only the vulnerability classes the teacher found in the source app.
- Line numbers refer to decompiled code.
- Static only: runtime-only issues are out of scope. Next: a dynamic agent that confirms these findings on a running app (see the dynamic-analysis write-up).
