# Scan report: ovaa

Run `2026-11-15T1429_ovaa_f76a7263` · profile `assignment` · generated from the run folder (every number below is traceable to a file in it).

**No ground truth for this app.** The teacher's verified findings are the reference, so every number is *agreement with teacher*, not recall. The lessons were distilled from the same findings used as the reference, so this comparison is circular and favours the with-lessons arm. The teacher ceiling and precision are omitted.

## 1. Core result (single run)

Headline: how many of the teacher's verified findings the student also reports, before and after the lessons.

| Arm | Model | Agreement with teacher (strict, verified) | Lenient | Incl. unverified | Findings (verified / total) |
|---|---|---|---|---|---|
| student baseline | qwen/qwen3.8-27b | **14/21 (67%)** | 15/21 (71%) | 14/21 (67%) | 15 / 15 |
| student with lessons | qwen/qwen3.8-27b | **17/21 (81%)** | 18/21 (86%) | 19/21 (90%) | 18 / 20 |

**Lift: +14.3 points of agreement with teacher (14/21 (67%) → 17/21 (81%), strict, verified). One run per arm: LLM output varies between runs, so a lift of one or two items is within run-to-run noise; small N, not a significance test.**

Strict = the judge said "match"; lenient also counts "partial". One-to-one assignment (Hungarian): each reference item is matched by at most one finding and vice versa. 

### Per item (teacher findings, 21 items)

✓ = strict match, ◐ = the judge said match but that finding was credited to another item (one finding counts once), ~ = partial only, · = not matched. Static visibility is the author's label: *partial* and *inventory_only* items cannot be fully seen in the client code.

| Item | Title | Static visibility | student baseline | student with lessons |
|---|---|---|---|---|
| T-01 | Exported provider opens arbitrary files via an unvalidated path segment, with read/write | full | ✓ | ✓ |
| T-02 | Exported service writes logcat to a caller-supplied path | full | ✓ | ✓ |
| T-03 | Exported LoginActivity starts an attacker-supplied intent | full | ✓ | ✓ |
| T-04 | Credentials sent over cleartext HTTP | full | ✓ | ✓ |
| T-05 | Credentials sent in an implicit broadcast with no permission | full | ✓ | ✓ |
| T-06 | Credentials written to logcat | full | ~ | ~ |
| T-07 | Deeplink can set the login URL to an attacker server | full | ✓ | ✓ |
| T-08 | Hardcoded credentials in a URL resource | full | ✓ | ✓ |
| T-09 | Credential token sent in a URL over HTTP | full | · | · |
| T-10 | Hardcoded AES key | full | ✓ | ✓ |
| T-11 | Parcelable instantiates an attacker-chosen class from a Parcel string | full | · | ✓ |
| T-12 | Plugin code loaded from any package matching a name prefix | full | ✓ | ✓ |
| T-13 | Deeplink WebView URL check bypassable via endsWith suffix | full | · | ✓ |
| T-14 | DexClassLoader loads code from world-writable /sdcard/updater.apk | full | ✓ | ✓ |
| T-15 | Password stored in plaintext SharedPreferences | full | ✓ | ✓ |
| T-16 | AES used with the default ECB mode | full | · | ✓ |
| T-17 | Serializable deletes files named in the stream on deserialization | full | · | ✓ |
| T-18 | FileProvider exposes the entire filesystem root | full | ✓ | · |
| T-19 | Result from an implicit intent is reflected to the caller, leaking URI grants | full | · | ✓ |
| T-20 | WebView with JS and file access from file URLs loads intent URL | full | ✓ | ✓ |
| T-21 | Application is debuggable and allows backup | full | ✓ | · |

## 2. Lessons

15 lessons kept after sanitising, distilled in one teacher call from 21 verified teacher findings; 15 selected for the student (3106 tokens, within the reserved budget).

Guidance block shown to the student (verbatim, identical in every unit):

```text
- L01 Exported component launches attacker-supplied Intent (intent redirection) (CWE-926, MASVS-PLATFORM)
  Applies to: Activity, Service, BroadcastReceiver; APIs: getParcelableExtra, IntentCompat.getParcelableExtra, startActivity, setResult
  Look for: Exported component reads an Intent parcelable extra and passes it to startActivity; Implicit-intent result passed back unchanged via setResult(resultCode, data); No check of target component or grant flags on the nested intent
  Why: The nested intent runs with the app's identity, reaching non-exported components or granting URI permissions on private providers. Reflecting a foreign result passes grants through to the caller.
  Not a finding when: Nested intent's component is fixed and flags are cleared before launch; Component is not exported and only receives intents from trusted callers
  Severity: High when non-exported components or providers are reachable; medium otherwise.

- L02 FileProvider paths expose root or private storage (CWE-552, MASVS-PLATFORM)
  Applies to: ContentProvider, FileProvider; APIs: FileProvider, root-path, grantUriPermissions, FileProvider.getUriForFile
  Look for: provider_paths XML has <root-path> or path="/" or "."; FileProvider has grantUriPermissions="true"; A redirection or result-reflection primitive can grant its URIs
  Why: A broad path mapping lets any granted content URI address any file the app can read, including private data. Combined with intent redirection, the attacker obtains the grant.
  Not a finding when: Paths limited to a dedicated shared subdirectory holding no sensitive files; No code path lets untrusted callers obtain grants
  Severity: High when a grant path exists; medium as standalone misconfiguration.

- L03 Exported provider path traversal with file access (CWE-22, MASVS-PLATFORM)
  Applies to: ContentProvider; APIs: ContentProvider.openFile, Uri.getLastPathSegment, ParcelFileDescriptor.open, new File
  Look for: Exported provider without permission overrides openFile; File built from Uri.getLastPathSegment or getPath without canonicalization; Open mode permits write or create
  Why: URL-decoded ../ in the URI escapes the intended directory. Any app can read or overwrite files the victim can access, including private data.
  Not a finding when: Provider protected by signature permission or not exported; Canonical path verified to be under an allowed base directory
  Severity: High, especially with write mode or private data reachable.

- L04 Exported service writes to caller-chosen file path (CWE-73, MASVS-PLATFORM)
  Applies to: Service, Activity, BroadcastReceiver; APIs: Intent.getExtras, new File, FileOutputStream, Runtime.exec
  Look for: Exported component takes file path string from intent extra; Component writes logs or data to that path with its own privileges; No permission or path validation
  Why: Caller controls the destination, so it can overwrite private app files or direct sensitive output to a location it can read.
  Not a finding when: Component not exported or guarded by signature permission; Path forced into an app-controlled directory after canonicalization
  Severity: High when overwrite of private files or sensitive dump is possible.
  Example: File out = new File(intent.getStringExtra(<extraKey>)); write(out, <data>);

- L05 Weak <name> URL validation before WebView load (CWE-601, MASVS-PLATFORM)
  Applies to: Activity, WebView; APIs: Uri.getHost, String.endsWith, WebView.loadUrl, getQueryParameter
  Look for: Host check uses endsWith or contains without a leading dot; Browsable <name> parameter passed to WebView.loadUrl; Scheme not restricted to https
  Why: Look-alike domains pass the check, so attacker pages load in a privileged WebView, exposing JS and file-access settings.
  Not a finding when: Exact host equality against an allowlist with https enforced; WebView has JS and file access disabled and no bridges
  Severity: Medium; high if the WebView is privileged.
  Example: if (uri.getHost().endsWith(<domain>)) webView.loadUrl(uri.toString());

- L06 Insecure WebView settings with intent-controlled URL (CWE-749, MASVS-PLATFORM)
  Applies to: Activity, WebView; APIs: setJavaScriptEnabled, setAllowFileAccessFromFileURLs, setAllowUniversalAccessFromFileURLs, WebView.loadUrl
  Look for: JavaScript enabled together with file-URL access flags; URL read from intent extra or <name>; file: scheme not rejected
  Why: A file: page can run script and read other local files, including private app data, then exfiltrate them.
  Not a finding when: Only fixed trusted content is loaded, never intent input; File-access flags are false and scheme is restricted to https
  Severity: High when URL is attacker-reachable.

- L07 <Class> parameter overwrites trusted endpoint setting (CWE-345, MASVS-AUTH)
  Applies to: Activity; APIs: Uri.getQueryParameter, SharedPreferences.Editor.putString, Intent.getData
  Look for: Browsable <name> persists a URL parameter into config or prefs; Stored value later used as a credentials-submission endpoint; No allowlist or user confirmation
  Why: A crafted link redirects subsequent authenticated requests to an attacker server, leaking credentials.
  Not a finding when: URL validated against a fixed allowlist of hosts; Setting is only available in non-release builds
  Severity: High when credentials or tokens are sent to the URL.

- L08 Dynamic code loading from untrusted location or package (CWE-94, MASVS-CODE)
  Applies to: Application, Service, Activity; APIs: DexClassLoader, PathClassLoader, createPackageContext, CONTEXT_INCLUDE_CODE
  Look for: DexClassLoader path on external storage or world-writable directory; createPackageContext with CONTEXT_INCLUDE_CODE chosen by package name prefix; No signature or hash verification before loading
  Why: Another app can plant a file or install a matching package, so its code runs in this app's process and with its permissions.
  Not a finding when: Code is signature-verified or loaded from app-private storage; Package must match a pinned signing certificate
  Severity: High for external storage; medium for name-prefix plugin loading.
  Example: new DexClassLoader(<externalPath>, <optDir>, null, getClassLoader()).loadClass(<name>);

- L09 Unsafe deserialization with side effects or arbitrary classes (CWE-502, MASVS-CODE)
  Applies to: Serializable, Parcelable, Activity, Service; APIs: readObject, Parcelable.Creator, Class.forName, Gson.fromJson
  Look for: readObject performs file deletion or other actions using stream data; Parcel constructor reads class name then Class.forName and fromJson; Serializable or Parcelable extra read from an exported component
  Why: Attacker-crafted extras trigger the side effects or instantiate arbitrary classes during unparcelling, giving file deletion or gadget execution.
  Not a finding when: Class never reachable from untrusted input; Class name checked against a fixed allowlist
  Severity: Medium; high if exported entry reaches a destructive gadget.

- L10 Credentials or tokens sent over cleartext HTTP (CWE-319, MASVS-NETWORK)
  Applies to: Application, Activity; APIs: Retrofit.Builder.baseUrl, HttpURLConnection, usesCleartextTraffic, network-security-config
  Look for: http:// base URL used for <name> or token requests; Token placed in a query string; No network security config blocking cleartext
  Why: A network attacker can read or modify traffic and capture credentials or tokens.
  Not a finding when: Non-sensitive public content only; Cleartext blocked by config and URL is https at runtime
  Severity: High for credentials; medium for derived tokens.
  Example: new Retrofit.Builder().baseUrl("http://<host>/")

- L11 Sensitive data in implicit broadcast or implicit intent (CWE-927, MASVS-PLATFORM)
  Applies to: Activity, BroadcastReceiver; APIs: sendBroadcast, startActivity, Intent.putExtra, setPackage
  Look for: sendBroadcast with credential extras and no receiver permission; Implicit intent carries token or URL data; No setPackage or component pinning
  Why: Any app can register a matching receiver or activity and capture the secrets.
  Not a finding when: Explicit intent or setPackage to own app; Broadcast guarded by a signature permission
  Severity: Medium to high depending on secret sensitivity.

- L12 Secrets stored or logged in plaintext (CWE-312, MASVS-STORAGE)
  Applies to: Activity, Service, Application; APIs: Log.d, SharedPreferences.Editor.putString, toString
  Look for: Log calls on objects whose toString contains password or token; Password saved to SharedPreferences without encryption; Log dumped to files by a service
  Why: Other apps with log access, backups or device access can read the secrets; logs may be dumped to readable files.
  Not a finding when: Value is non-sensitive or redacted; Stored via Keystore-backed encryption
  Severity: Medium; higher if the secret is easily extracted.

- L13 Hardcoded keys and weak cipher modes (CWE-321, MASVS-CRYPTO)
  Applies to: Crypto helper; APIs: Cipher.getInstance, SecretKeySpec
  Look for: SecretKeySpec built from a string constant; Cipher.getInstance("<String>") with no mode; ECB mode, or static IV
  Why: Constant keys can be extracted from the APK to decrypt any protected data. ECB leaks patterns and offers no integrity.
  Not a finding when: Key is generated at runtime in Android Keystore; Constant is unrelated to protecting sensitive data
  Severity: Medium; high if it protects credentials.
  Example: new SecretKeySpec(<CONST>.getBytes(), "<String>"); Cipher.getInstance("<String>");

- L14 Debuggable build with backup enabled (CWE-1188, MASVS-RESILIENCE)
  Applies to: Application manifest; APIs: android:debuggable, android:allowBackup, adb backup, run-as
  Look for: android:debuggable="true" in release manifest; android:allowBackup="true" without backup rules; App stores sensitive data in private storage
  Why: Debugger, run-as or adb backup lets someone with device access extract private data and tamper with app execution.
  Not a finding when: Debug-only build variant not shipped; Backup rules exclude sensitive files
  Severity: Medium; higher when sensitive data is stored unencrypted.

- L15 Hardcoded credentials in resources (CWE-522, MASVS-AUTH)
  Applies to: Resource, Application; APIs: res/values/strings.xml, Resources.getString
  Look for: URL with user:pass@ embedded in resources; Password or API secret strings in strings.xml; Dev or test endpoint credentials
  Why: Anyone can unpack the APK and read the credentials, possibly gaining access to backend or dev systems.
  Not a finding when: Placeholder or revoked credentials; Value is a public non-secret identifier
  Severity: Low to high depending on what the credential grants.
  Example: <string name=<n>>https://<user>:<pass>@<host></string>
```

Sanitiser (redact): 8 term(s) redacted, 9 example(s) removed by the answer-key check, 0 lesson(s) dropped; 0 lesson(s) left out by the count/token caps. Full records: 05_learn/rejected_lessons.json.

| Lesson | Field | Rule | Term | Action |
|---|---|---|---|---|
| L01 | abstract_example | answer_key_similarity | `` | example_removed |
| L02 | abstract_example | answer_key_similarity | `` | example_removed |
| L03 | abstract_example | answer_key_similarity | `` | example_removed |
| L05 | title | identifier_part | `deeplink` | redacted |
| L05 | signals | identifier_part | `deeplink` | redacted |
| L06 | abstract_example | answer_key_similarity | `` | example_removed |
| L06 | signals | identifier_part | `deeplink` | redacted |
| L07 | abstract_example | answer_key_similarity | `` | example_removed |
| L07 | title | identifier_part | `Deeplink` | redacted |
| L07 | signals | identifier_part | `deeplink` | redacted |
| L09 | abstract_example | answer_key_similarity | `` | example_removed |
| L10 | signals | identifier_part | `login` | redacted |
| L11 | abstract_example | answer_key_similarity | `` | example_removed |
| L12 | abstract_example | answer_key_similarity | `` | example_removed |
| L13 | signals | string_literal | `AES` | redacted |
| L13 | abstract_example | string_literal | `AES` | redacted |
| L14 | abstract_example | answer_key_similarity | `` | example_removed |

## 3. Robustness check: README-only vs enriched reference

Not computed (no enriched ground truth for this app).

## 4. Judge audit

Not run yet for this run: `vulnscan audit export` writes blind rows to label, `vulnscan audit score` measures agreement.

## 5. Betterments

None enabled in this run (profile `assignment`): single run per arm, no checklist control arm.

## 6. Coverage and quality

| Arm | Units | Unit status | Evidence status | Bad findings dropped |
|---|---|---|---|---|
| teacher (ceiling) | 1 | ok 1 | verified_exact 21 | 0 |
| student baseline | 1 | ok 1 | verified_exact 15 | 0 |
| student with lessons | 1 | ok 1 | unverified 2, verified_exact 18 | 0 |

- Decompilation: jadx 1.5.6, exit code 3 (partial success: output present); 42 failed-method markers in all code, 0 in first-party code.
- First-party split: 21 of 4119 Java files (prefixes oversecured.ovaa); 1 build-generated files excluded from the app's own packages. Largest excluded groups: kotlinx.coroutines (503), androidx.core (400), org.apache (286), androidx.constraintlayout (202), kotlinx.serialization (186).
- Units: teacher 1 (whole), student 1 (whole); the two models may scan different units because their budgets differ. All student arms use the same units.
- Judge: 38 confirm calls, 0 unparseable answers (counted as "no").

## 7. Usage

| Model | Purpose | Arm | Calls billed / cached | Input tokens | Output tokens | Cost (billed) |
|---|---|---|---|---|---|---|
| student_pollinations | scan | student_baseline | 0 / 1 | 9579 | 3589 | $0.0000 |
| student_pollinations | scan | student_with_lessons | 1 / 0 | 12055 | 4665 | $0.0000 |
| teacher | scan | teacher | 0 / 1 | 17541 | 6132 | $0.0000 |
| teacher | distill | distill | 1 / 0 | 6098 | 5236 | $0.0646 |
| judge_pollinations | judge_confirm | teacher_findings:student_baseline | 17 / 0 | 7498 | 1566 | $0.0000 |
| judge_pollinations | judge_confirm | teacher_findings:student_with_lessons | 21 / 0 | 9082 | 1853 | $0.0000 |
| judge_pollinations | judge_screen | teacher_findings:student_baseline | 1 / 0 | 3115 | 256 | $0.0000 |
| judge_pollinations | judge_screen | teacher_findings:student_with_lessons | 1 / 0 | 3555 | 331 | $0.0000 |

Billed in this run: $0.0646. Including calls served from the response cache at their original price: $0.1610. Free-tier models are shown at $0 (tokens are still counted).

## 8. Prompt versions

| Prompt | sha256 |
|---|---|
| `distill/v2` | `8d23a5746d60` |
| `judge_confirm/v1` | `cbbc6bf53622` |
| `judge_screen/v1` | `0d8f84acdbc7` |
| `scan/v4` | `7cd4f30371a6` |

Prompts were written from the OWASP MASVS categories, not from the ground-truth list, but they were developed and debugged on this app. The scores in section 1 are for exactly these versions.

## 9. Limitations

- The lift is in-sample: lessons are distilled from the same app the student is rescanned on. One app cannot show that the lessons generalise (cross-app transfer is the partial answer).
- Contamination: the evaluation app and its vulnerability list are public; the models may have memorised them.
- Some ingest features (dependency inventory, cloud-identifier patterns) were added after reading the ground-truth list; they are generic, but not chosen blind.
- Prompts were developed on this app (versions and scores are recorded in section 8).
- Ground-truth enrichment is the author's own labelling (mitigated: written and committed before any scan, README-only headline, robustness check).
- Some items are only partly visible statically (server-side cloud configuration; known-vulnerable components rely on the model's advisory knowledge).
- The teacher's temperature cannot be set (Claude Sonnet 5.5 rejects non-default sampling); LLM output varies between runs; small N; no significance test.
- Teacher refusals can reduce coverage (counted; the model that answered is recorded when a fallback was used).
- Teacher and student may scan different units because of different budgets; the student's free tier (1,000 output tokens per minute) forces small units, and units whose answer does not fit are re-planned into smaller ones.
- The judge is an LLM; the hand audit measures its agreement with the author, it does not remove bias.
- One-to-one matching is conservative.
- Unmatched findings are not automatically false positives (the reference list may be incomplete).
- Teacher-as-reference (apps without ground truth) inherits the teacher's misses and mistakes and is circular with the lessons.
- Decompilation is lossy (jadx failures are counted); the smali fallback is not built in v0.1.
- Obfuscated apps: first-party code is derived from the manifest, but library detection is not fingerprint-based.
- Native code, assets and split APKs / app bundles are not analysed.
- Text-based one-hop graph: reflection, framework callbacks and string-action intents are missed; oversized files are skipped; no hub/skeleton handling in v0.1.
- strings.xml filtering can miss innocently named, low-entropy secrets.
- Prompt injection via app code or strings can still influence the model.
- Model confidence is uncalibrated (used for ranking only).
- The sanitiser is lexical plus a similarity check; paraphrased references can pass.
- Lessons cover only the vulnerability classes the teacher found in the source app.
- Line numbers refer to decompiled code.
- Static only: runtime-only issues are out of scope (see the dynamic-analysis write-up).
