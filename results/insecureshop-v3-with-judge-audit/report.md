# Scan report: insecureshop

Run `2026-11-14T1846_insecureshop_9f96bd98` · profile `assignment` · generated from the run folder (every number below is traceable to a file in it).

## 1. Core result

Headline: the student's recall against the README list before and after the lessons, judged strictly, counting verified findings only. The teacher row is the ceiling.

| Arm | Model | Recall (strict, verified) | Lenient | Incl. unverified | Precision (strict) | Findings (verified / total) |
|---|---|---|---|---|---|---|
| teacher (ceiling) | claude-sonnet-5-5 | **15/19 (79%)** | 15/19 (79%) | 15/19 (79%) | 79% | 19 / 19 |
| student baseline | qwen/qwen3.8-27b | **9/19 (47%)** | 10/19 (53%) | 9/19 (47%) | 73% | 15 / 15 |
| student with lessons | qwen/qwen3.8-27b | **14/19 (74%)** | 14/19 (74%) | 14/19 (74%) | 78% | 18 / 18 |

**Lift: +26.3 points of recall (9/19 (47%) → 14/19 (74%), strict, verified). One run per arm: LLM output varies between runs, so a lift of one or two items is within run-to-run noise; small N, not a significance test.**

Strict = the judge said "match"; lenient also counts "partial". One-to-one assignment (Hungarian): each reference item is matched by at most one finding and vice versa. Precision counts findings that match any item (many-to-one) over verified findings; unmatched findings are "unmatched", not necessarily false positives (the README list may be incomplete).

### Per item (README ground truth, 19 items)

✓ = strict match, ◐ = the judge said match but that finding was credited to another item (one finding counts once), ~ = partial only, · = not matched. Static visibility is the author's label: *partial* and *inventory_only* items cannot be fully seen in the client code.

| Item | Title | Static visibility | teacher (ceiling) | student baseline | student with lessons |
|---|---|---|---|---|---|
| GT-01 | Hardcoded Credentials | full | ✓ | ✓ | ✓ |
| GT-02 | Insufficient URL Validation | full | ✓ | ✓ | ✓ |
| GT-03 | Weak Host Validation Check | full | ✓ | ◐ | ✓ |
| GT-04 | Arbitrary Code Execution | full | ✓ | · | ✓ |
| GT-05 | Access to Protected Components | full | ✓ | · | ✓ |
| GT-06 | Unprotected Data URIs | full | ✓ | ◐ | ◐ |
| GT-07 | Theft of Arbitrary | full | ✓ | · | ✓ |
| GT-08 | Using Components with Known Vulnerabilities | inventory_only | · | · | · |
| GT-09 | Insecure Broadcast Receiver | full | ✓ | · | ✓ |
| GT-10 | AWS Cognito Misconfiguration | partial | · | · | · |
| GT-11 | Insecure use of FilePaths in FileProvider | full | ✓ | ✓ | ✓ |
| GT-12 | Use of Implicit intent to send a broadcast with sensitive data | full | ✓ | ✓ | ✓ |
| GT-13 | Intercepting Implicit intent to load arbitrary URL | full | · | ◐ | ◐ |
| GT-14 | Insecure Implementation of SetResult in exported Activity | full | ✓ | · | ✓ |
| GT-15 | Insecure Content Provider | full | ✓ | ✓ | ✓ |
| GT-16 | Lack of SSL Certificate Validation | full | ✓ | ✓ | ✓ |
| GT-17 | Insecure Webview Properties Enabled | full | ◐ | ✓ | ◐ |
| GT-18 | Insecure Data Storage | full | ✓ | ✓ | ✓ |
| GT-19 | Insecure Logging | full | ✓ | ✓ | ✓ |

### Findings (run 1 of each arm)

Every finding after evidence checking and dedup, highest severity first. "Credited" is the reference item it was assigned to (strict); "-" means it matched no item, which does not by itself make it a false positive.

<details><summary>teacher: 19 finding(s)</summary>

| # | Finding | Severity | CWE | Where | Evidence | Credited |
|---|---|---|---|---|---|---|
| 1 | Deep link WebViewActivity: unvalidated /web path and weak endsWith host check on /webview path | high | CWE-939 | `com/insecureshop/WebViewActivity.java:91 (WebViewActivity.onCreate)` | verified_exact | GT-03 |
| 2 | Exported ResultActivity returns caller's intent as result, enabling URI permission grants | high | CWE-927 | `com/insecureshop/ResultActivity.java:38 (ResultActivity.onCreate)` | verified_exact | GT-14 |
| 3 | Exported WebView2Activity loads arbitrary URL from intent into JS-enabled WebView with universal file access | high | CWE-79 | `com/insecureshop/WebView2Activity.java:83 (WebView2Activity.onCreate)` | verified_exact | GT-06 |
| 4 | Exported content provider returns username and password, guarded only by a normal-level permission | high | CWE-200 | `AndroidManifest.xml (InsecureShopProvider.query)` | verified_exact | GT-15 |
| 5 | FileProvider exposes entire filesystem root | high | CWE-668 | `resources/res/xml/provider_paths.xml` | verified_exact | GT-11 |
| 6 | Intent redirection via attacker-supplied nested intent in exported WebView2Activity | high | CWE-749 | `com/insecureshop/WebView2Activity.java:53 (WebView2Activity.onCreate)` | verified_exact | GT-05 |
| 7 | Loads and executes code from any installed package whose name starts with com.insecureshopapp | high | CWE-94 | `com/insecureshop/LoginActivity.java:111 (LoginActivity.onLogin)` | verified_exact | GT-04 |
| 8 | WebView ignores SSL errors | high | CWE-295 | `com/insecureshop/util/CustomWebViewClient.java:14 (CustomWebViewClient.onReceivedSslError)` | verified_exact | GT-16 |
| 9 | Application is debuggable and allows backup | medium | CWE-489 | `AndroidManifest.xml` | verified_exact | - |
| 10 | Cleartext HTTP traffic enabled | medium | CWE-319 | `AndroidManifest.xml` | verified_exact | - |
| 11 | Credentials sent in implicit broadcast | medium | CWE-927 | `com/insecureshop/AboutUsActivity.java:86 (AboutUsActivity.onSendData)` | verified_exact | GT-12 |
| 12 | Credentials written to logcat | medium | CWE-532 | `com/insecureshop/LoginActivity.java:87 (LoginActivity.onLogin)` | verified_exact | GT-19 |
| 13 | Dynamically registered exported receiver opens attacker-supplied URL in WebView2Activity | medium | CWE-926 | `com/insecureshop/AboutUsActivity.java:63 (AboutUsActivity.onCreate) +1 more` | verified_exact | GT-09 |
| 14 | Exported ChooserActivity copies attacker-chosen URI to external storage using attacker-controlled filename | medium | CWE-22 | `com/insecureshop/ChooserActivity.java:57 (ChooserActivity.makeTempCopy) +1 more` | verified_exact | GT-07 |
| 15 | Hardcoded AWS Cognito identity pool ID | medium | CWE-798 | `res/values/strings.xml` | verified_exact | - |
| 16 | Hardcoded login credentials with client-side-only authentication | medium | CWE-798 | `com/insecureshop/util/Util.java:25 (Util.getUserCreds)` | verified_exact | GT-01 |
| 17 | Password stored in plaintext SharedPreferences (also backup-enabled) | medium | CWE-312 | `com/insecureshop/util/Prefs.java:100 (Prefs.setPassword)` | verified_exact | GT-18 |
| 18 | PrivateActivity WebView loads intent-provided URL with universal file access | medium | CWE-79 | `com/insecureshop/PrivateActivity.java (PrivateActivity.onCreate)` | verified_fuzzy | GT-02 |
| 19 | UploadService exported without permission | medium | CWE-926 | `AndroidManifest.xml` | verified_exact | - |

</details>

<details><summary>student_baseline: 15 finding(s)</summary>

| # | Finding | Severity | CWE | Where | Evidence | Credited |
|---|---|---|---|---|---|---|
| 1 | Exported Content Provider exposes credentials | critical | CWE-926 | `AndroidManifest.xml` | verified_exact | GT-15 |
| 2 | Hardcoded credentials | critical | CWE-798 | `com/insecureshop/util/Util.java:25 (Util.getUserCreds)` | verified_exact | GT-01 |
| 3 | Broadcast sends credentials | high | CWE-927 | `com/insecureshop/AboutUsActivity.java:88 (AboutUsActivity.onSendData)` | verified_exact | GT-12 |
| 4 | Credentials logged | high | CWE-532 | `com/insecureshop/LoginActivity.java:88 (LoginActivity.onLogin)` | verified_exact | GT-19 |
| 5 | Debuggable build | high | CWE-489 | `AndroidManifest.xml` | verified_exact | - |
| 6 | FileProvider exposes root path | high | CWE-22 | `resources/res/xml/provider_paths.xml` | verified_exact | GT-11 |
| 7 | Sensitive data stored in plaintext | high | CWE-312 | `com/insecureshop/util/Prefs.java:100 (Prefs.setPassword)` | verified_exact | GT-18 |
| 8 | WebView allows universal file access | high | CWE-749 | `com/insecureshop/WebView2Activity.java:73 (WebView2Activity.onCreate) +1 more` | verified_exact | GT-17 |
| 9 | WebView allows universal file access | high | CWE-749 | `com/insecureshop/WebViewActivity.java:68 (WebViewActivity.onCreate)` | verified_exact | - |
| 10 | WebView ignores SSL errors | high | CWE-295 | `com/insecureshop/util/CustomWebViewClient.java:16 (CustomWebViewClient.onReceivedSslError)` | verified_exact | GT-16 |
| 11 | WebView loads untrusted URL | high | CWE-601 | `com/insecureshop/WebView2Activity.java:83 (WebView2Activity.onCreate)` | verified_exact | GT-02 |
| 12 | Backup enabled | medium | CWE-921 | `AndroidManifest.xml` | verified_exact | - |
| 13 | Cleartext traffic enabled | medium | CWE-319 | `AndroidManifest.xml` | verified_exact | - |
| 14 | Hardcoded AWS Identity Pool ID | medium | CWE-798 | `res/values/strings.xml` | verified_exact | - |
| 15 | Implicit intent with sensitive data | medium | CWE-927 | `com/insecureshop/ProductAdapter.java:123 (ProductAdapter.onBindViewHolder)` | verified_exact | - |

</details>

<details><summary>student_with_lessons: 18 finding(s)</summary>

| # | Finding | Severity | CWE | Where | Evidence | Credited |
|---|---|---|---|---|---|---|
| 1 | Debuggable build enabled | high | CWE-489 | `AndroidManifest.xml` | verified_exact | - |
| 2 | Deep link URL check bypassable via weak host validation | high | CWE-939 | `com/insecureshop/WebViewActivity.java:91 (WebViewActivity.onCreate)` | verified_exact | GT-03 |
| 3 | Exported Activity reflects caller Intent through setResult | high | CWE-927 | `com/insecureshop/ResultActivity.java:38 (ResultActivity.onCreate)` | verified_exact | GT-14 |
| 4 | Exported ContentProvider leaks credentials with normal-level permission | high | CWE-200 | `AndroidManifest.xml (InsecureShopProvider.query) +1 more` | verified_exact | GT-15 |
| 5 | Exported WebView loads unvalidated URL with file access | high | CWE-79 | `com/insecureshop/WebView2Activity.java:73 (WebView2Activity.onCreate)` | verified_exact | GT-02 |
| 6 | FileProvider configured with overly broad root path | high | CWE-668 | `resources/res/xml/provider_paths.xml` | verified_exact | GT-11 |
| 7 | Hardcoded credentials in client-side authentication | high | CWE-798 | `com/insecureshop/util/Util.java:25 (Util.getUserCreds)` | verified_exact | GT-01 |
| 8 | Intent redirection via nested Intent extra | high | CWE-749 | `com/insecureshop/WebView2Activity.java:53 (WebView2Activity.onCreate)` | verified_exact | GT-05 |
| 9 | Loading code from another package by name prefix | high | CWE-94 | `com/insecureshop/LoginActivity.java:109 (LoginActivity.onLogin)` | verified_exact | GT-04 |
| 10 | WebView ignores SSL errors | high | CWE-295 | `com/insecureshop/util/CustomWebViewClient.java:16 (CustomWebViewClient.onReceivedSslError)` | verified_exact | GT-16 |
| 11 | Cleartext traffic enabled | medium | CWE-319 | `AndroidManifest.xml` | verified_exact | - |
| 12 | Credentials logged to logcat | medium | CWE-532 | `com/insecureshop/LoginActivity.java:87 (LoginActivity.onLogin)` | verified_exact | GT-19 |
| 13 | Dynamic receiver registered without permission triggers actions | medium | CWE-926 | `com/insecureshop/AboutUsActivity.java:63 (AboutUsActivity.onCreate) +1 more` | verified_exact | GT-09 |
| 14 | Exported third-party service without permission | medium | CWE-926 | `AndroidManifest.xml` | verified_exact | - |
| 15 | Hardcoded AWS Identity Pool ID | medium | CWE-798 | `res/values/strings.xml` | verified_exact | - |
| 16 | Plaintext credentials stored in SharedPreferences with backup enabled | medium | CWE-312 | `com/insecureshop/util/Prefs.java:100 (Prefs.setPassword) +1 more` | verified_exact | GT-18 |
| 17 | Sensitive data sent in implicit broadcast | medium | CWE-927 | `com/insecureshop/AboutUsActivity.java:87 (AboutUsActivity.onSendData)` | verified_exact | GT-12 |
| 18 | Untrusted stream URI copied to shared storage | medium | CWE-22 | `com/insecureshop/ChooserActivity.java:57 (ChooserActivity.onCreate)` | verified_exact | GT-07 |

</details>

## 2. Lessons

15 lessons kept after sanitising, distilled in one teacher call from 19 verified teacher findings; 15 selected for the student (3583 tokens, within the reserved budget).

Guidance block shown to the student (verbatim, identical in every unit):

```text
- L01 Exported ContentProvider leaks data behind weak permission (CWE-200, M8: Security Misconfiguration)
  Applies to: ContentProvider; APIs: ContentProvider.query, android:readPermission, android:protectionLevel
  Look for: <provider> exported=true with readPermission whose protectionLevel is normal or undeclared; query() returns credentials, tokens or user records; No caller or path checks inside query()
  Why: Normal-level permissions are granted automatically to any app that requests them, so they are no barrier. Any installed app can query the provider and read the sensitive columns.
  Not a finding when: Permission is signature-level or the provider is not exported; Returned columns contain no sensitive data
  Severity: High when credentials or PII are returned.
  Example: <provider exported=true readPermission=<normalPerm>/> query(...) -> Cursor with <secretColumn>

- L02 Intent redirection via nested Intent extra (CWE-749, M8: Security Misconfiguration)
  Applies to: Activity, Service, BroadcastReceiver; APIs: getParcelableExtra, startActivity, startService, sendBroadcast
  Look for: Exported component reads an Intent from an extra and launches it; No check on target component, package or grant flags; Nested Intent passed straight to startActivity
  Why: The app launches the attacker-built Intent with its own identity. This reaches non-exported components and can carry URI grant flags, bypassing the exported=false boundary.
  Not a finding when: Target component is resolved and checked against an allowlist before launch; Component is not exported and only receives intents from trusted code
  Severity: High when private components or URI grants become reachable.

- L03 Exported WebView component loads unvalidated URL with file access (CWE-79, M4: Insufficient Input/Output Validation)
  Applies to: Activity; APIs: WebView.loadUrl, setJavaScriptEnabled, setAllowUniversalAccessFromFileURLs, getDataString
  Look for: loadUrl() argument taken from intent data or extras without validation; JavaScript enabled plus setAllowUniversalAccessFromFileURLs(true) or file access allowed; Component exported or reachable through intent redirection
  Why: An attacker supplies a file:// or remote page URL. Script runs with universal file access, reads private app files such as prefs and exfiltrates them.
  Not a finding when: URL is checked against a strict scheme and host allowlist before loadUrl; File access settings are disabled and JavaScript is off
  Severity: High if exported or reachable by a third party; medium if only internal.
  Example: ws.setAllowUniversalAccessFromFileURLs(true); webView.loadUrl(getIntent().getStringExtra(<urlKey>));

- L04 Deep link URL check bypassable via weak host validation (CWE-939, M4: Insufficient Input/Output Validation)
  Applies to: Activity, Deep link handler; APIs: Uri.getQueryParameter, String.endsWith, String.contains, WebView.loadUrl
  Look for: Browsable intent-filter with custom scheme or host feeding a WebView; Validation uses endsWith/contains/startsWith on the raw URL string; Another deep link path loads the <String> parameter without any check
  Why: String suffix or substring checks are satisfied by attacker URLs that merely include the trusted name in the query or path. Any web link can then load arbitrary content into the privileged WebView.
  Not a finding when: Host parsed with Uri.getHost and compared by exact equality with https scheme; WebView is hardened with no JavaScript or file access
  Severity: High when the target WebView has JS or file access.
  Example: String u = uri.getQueryParameter(<param>); if (u.endsWith(<trustedHost>)) webView.loadUrl(u);

- L05 Exported Activity reflects caller Intent through setResult (CWE-927, M8: Security Misconfiguration)
  Applies to: Activity; APIs: setResult, getIntent, FLAG_GRANT_READ_URI_PERMISSION, FileProvider
  Look for: setResult(RESULT_OK, getIntent()) in an exported Activity; FileProvider or grantUriPermissions provider present in the app; Caller can set data URI and grant flags
  Why: The caller's Intent returns with its grant flags and URI, so the app grants the caller access to its own provider URIs. This leaks private files.
  Not a finding when: Result Intent is newly built with no caller URI or flags; Activity is not exported or no grantable provider exists
  Severity: High when combined with a broad FileProvider.
  Example: // exported Activity setResult(RESULT_OK, getIntent());

- L06 FileProvider configured with overly broad root path (CWE-668, M8: Security Misconfiguration)
  Applies to: ContentProvider, XML resource; APIs: FileProvider, root-path, grantUriPermissions, FileProvider.getUriForFile
  Look for: Paths XML contains <root-path> with path="/" or a broad files-path; FileProvider has grantUriPermissions=true; Intent redirection or reflected results exist that can grant URIs
  Why: Any file readable by the app, including private data, can be exposed through a content URI. An attacker who obtains a grant reads or writes those files.
  Not a finding when: Paths limited to a dedicated shared subdirectory holding non-sensitive files; No grant path is reachable by other apps
  Severity: High, since it amplifies any URI grant primitive.

- L07 Sensitive data sent in implicit broadcast (CWE-927, M8: Security Misconfiguration)
  Applies to: Activity, Service, BroadcastReceiver; APIs: sendBroadcast, Intent.putExtra, registerReceiver
  Look for: sendBroadcast with custom action and no receiver permission; putExtra carries passwords, tokens or PII; No setPackage or explicit component set
  Why: Any app registering a receiver for the action receives the extras, so credentials leak to malicious apps on the device.
  Not a finding when: Broadcast is explicit via setPackage or sent with a signature permission; Extras hold no sensitive data
  Severity: Medium to high depending on data sensitivity.
  Example: i = new Intent(<action>); i.putExtra(<secretKey>, secret); sendBroadcast(i);

- L08 Dynamic receiver registered without permission triggers actions (CWE-926, M8: Security Misconfiguration)
  Applies to: BroadcastReceiver; APIs: registerReceiver, IntentFilter, RECEIVER_EXPORTED, BroadcastReceiver.onReceive
  Look for: registerReceiver(receiver, filter) without a permission argument; onReceive reads extras and starts an Activity or loads a URL; Receiver exported implicitly or by RECEIVER_EXPORTED flag
  Why: Any app can send the action. onReceive then acts on attacker-controlled extras, such as launching a WebView with a malicious URL.
  Not a finding when: Registered with a signature permission or RECEIVER_NOT_EXPORTED; onReceive validates the sender and input
  Severity: Medium; higher if the action reaches a WebView or sensitive operation.

- L09 Exported third-party service without permission (CWE-926, M8: Security Misconfiguration)
  Applies to: Service; APIs: android:exported, Service.onStartCommand, android:permission
  Look for: <service> exported=true from a library with no android:permission; Service accepts Intent extras describing files or URLs; Library service performs uploads or downloads
  Why: Any app can start the service with crafted intents. This can trigger uploads of local files to attacker servers or other library actions.
  Not a finding when: Service is protected by a signature permission or not exported; Service ignores caller-supplied parameters
  Severity: Medium; high if it can exfiltrate files.
  Example: <service exported=true name=<LibService>/> // no android:permission

- L10 Weak transport security: ignored SSL errors and cleartext (CWE-295, M5: Insecure Communication)
  Applies to: WebViewClient, Manifest; APIs: onReceivedSslError, SslErrorHandler.proceed, usesCleartextTraffic, TrustManager
  Look for: onReceivedSslError calls handler.proceed() unconditionally; android:usesCleartextTraffic="true" or permissive network security config; WebView loads remote content over HTTP
  Why: A network attacker can intercept or modify traffic. Injected JavaScript in a WebView runs with its privileges.
  Not a finding when: Handler calls cancel() or is limited to debug builds; Cleartext allowed only for local test domains, not production hosts
  Severity: High for WebView or auth traffic; medium for incidental cleartext.
  Example: onReceivedSslError(v, handler, e) { handler.proceed(); }

- L11 Hardcoded credentials and cloud identifiers in app (CWE-798, M1: Improper Credential Usage)
  Applies to: Class, strings.xml; APIs: HashMap.put, getString, CognitoCachingCredentialsProvider
  Look for: Literal <String> and password compared locally to authenticate; Identity pool IDs, API keys or secrets in resources or code; <Class> logic wholly client-side with no server check
  Why: Anyone can decompile the APK and recover the secrets. They then log in or obtain cloud credentials for the backend role.
  Not a finding when: Value is a public non-secret identifier with restricted server-side permissions; Credential is a test value in a non-release build
  Severity: Medium to high depending on what the credential unlocks.
  Example: <package>(<user>, <literalPassword>); if (<package>(u).equals(p)) <name>();

- L12 Plaintext secrets in storage with backup or debuggable enabled (CWE-312, M9: Insecure Data Storage)
  Applies to: Application, Manifest; APIs: SharedPreferences.Editor.putString, android:allowBackup, android:debuggable
  Look for: putString stores password or token in plain SharedPreferences; allowBackup=true without backup rules excluding the file; debuggable=true in the release manifest
  Why: Unencrypted secrets can be extracted via adb backup or run-as, or by any file-read primitive. Debuggable builds also expose all private data.
  Not a finding when: Value is encrypted with a Keystore-backed key; Backup rules exclude the preferences file and the build is not debuggable
  Severity: Medium; higher when it chains with file read.
  Example: prefs.edit().putString(<prefsKey>, password).apply();

- L13 Credentials written to logcat (CWE-532, M9: Insecure Data Storage)
  Applies to: Activity, Class; APIs: Log.d, Log.i, Log.v
  Look for: Log call with password, token or <String> variable; Logging not stripped in release builds; Sensitive values logged in <name> flow
  Why: Log output is readable via adb or logcat on debuggable devices and by privileged tooling, exposing credentials to anyone with device access.
  Not a finding when: Logs are stripped in release builds via ProGuard or R8 rules; Logged value is a redacted or non-sensitive string
  Severity: Medium; low if release builds strip logs.
  Example: Log.d(<tag>, password);

- L14 Untrusted stream URI copied to shared storage (CWE-22, M4: Insufficient Input/Output Validation)
  Applies to: Activity; APIs: EXTRA_STREAM, Uri.fromFile, ContentResolver.openInputStream, getExternalStorageDirectory
  Look for: Exported Activity reads EXTRA_STREAM URI and copies it to external storage; File name derived from URI path or display name without sanitizing; Uri converted to a File path without scheme checks
  Why: A caller points the URI at private app files or uses traversal in the name. The app copies the data to world-readable storage or writes to arbitrary paths.
  Not a finding when: Scheme restricted and name sanitized, output in app-private directory; Activity not exported and URI source trusted
  Severity: Medium; high if private files leak or arbitrary writes occur.
  Example: Uri u = getIntent().getParcelableExtra(EXTRA_STREAM); copy(new File(u.toString()), <externalDir>);

- L15 Loading code from another package by name prefix (CWE-94, M7: Insufficient Binary Protections)
  Applies to: Activity, Class; APIs: createPackageContext, CONTEXT_INCLUDE_CODE, CONTEXT_IGNORE_SECURITY, ClassLoader.loadClass
  Look for: createPackageContext with CONTEXT_INCLUDE_CODE and CONTEXT_IGNORE_SECURITY; Package chosen by name prefix or from the installed list; loadClass followed by reflective invoke
  Why: Any app with a matching package name is loaded into this process and its code runs with the app's permissions and data access.
  Not a finding when: Package signature is verified against a pinned certificate before loading; Package name is an exact match and signature-checked
  Severity: High; arbitrary code execution in the app process.
  Example: Context c = createPackageContext(<pkgPrefixMatch>, CONTEXT_INCLUDE_CODE); c.getClassLoader().loadClass(<cls>)...invoke(...);
```

Sanitiser (redact): 8 term(s) redacted, 3 example(s) removed by the answer-key check, 0 lesson(s) dropped; 0 lesson(s) left out by the count/token caps. Full records: 05_learn/rejected_lessons.json.

| Lesson | Field | Rule | Term | Action |
|---|---|---|---|---|
| L02 | abstract_example | answer_key_similarity | `` | example_removed |
| L04 | signals | string_literal | `url` | redacted |
| L06 | abstract_example | answer_key_similarity | `` | example_removed |
| L08 | abstract_example | answer_key_similarity | `` | example_removed |
| L11 | signals | string_literal | `username` | redacted |
| L11 | signals | identifier_part | `Login` | redacted |
| L11 | abstract_example | identifier | `login` | redacted |
| L11 | abstract_example | identifier_part | `creds` | redacted |
| L11 | abstract_example | identifier_part | `creds` | redacted |
| L13 | signals | string_literal | `username` | redacted |
| L13 | signals | identifier | `login` | redacted |

## 3. Robustness check: README-only vs enriched reference

The headline uses only the README text. The enriched reference adds the author's accepted CWEs and locations (written before any scan); it changes what the judge sees, so it needs its own judge calls.

| Arm | readme | enriched |
|---|---|---|
| teacher (ceiling) | 15/19 (79%) | 17/19 (89%) |
| student baseline | 9/19 (47%) | 10/19 (53%) |
| student with lessons | 14/19 (74%) | 16/19 (84%) |

Lift with the enriched reference: +31.6 points (headline +26.3). A difference means the judge's verdicts depend on the extra detail (CWEs, places) rather than on the README wording.

## 4. Judge audit

368 labelled rows (README mode): raw agreement with the judge 95%, 96% when partial counts as match, Cohen's kappa 0.79; 0 screened-out pair(s) labelled match (matches the quick look missed); 20 disagreement(s), listed in 07_evaluate/audit/score.json. Labels file: runs/2026-11-14T1846_insecureshop_9f96bd98/07_evaluate/audit/audit_labels_claude.csv.

## 5. Betterments

None enabled in this run (profile `assignment`): 1 run(s) per student arm, no checklist control arm.

## 6. Coverage and quality

| Arm | Units | Unit status | Evidence status | Bad findings dropped |
|---|---|---|---|---|
| teacher (ceiling) | 1 | ok 1 | verified_exact 18, verified_fuzzy 1 | 0 |
| student baseline | 1 | ok 1 | verified_exact 15 | 0 |
| student with lessons | 1 | ok 1 | verified_exact 18 | 0 |

- Decompilation: jadx 1.5.6, exit code 3 (partial success: output present); 8 failed-method markers in all code, 0 in first-party code.
- First-party split: 22 of 2179 Java files (prefixes com.insecureshop); 29 build-generated files excluded from the app's own packages. Largest excluded groups: (top 5, 1083 of 2157 excluded files) com.bumptech (312), androidx.core (270), com.google (250), androidx.appcompat (133), kotlin.jvm (118).
- Units: teacher 1 (whole), student 1 (whole); the two models may scan different units because their budgets differ. All student arms use the same units.
- Judge: 117 confirm calls, 0 unparseable answers (counted as "no").

## 7. Usage

| Model | Purpose | Arm | Calls billed / cached | Input tokens | Output tokens | Cost (billed) |
|---|---|---|---|---|---|---|
| student_pollinations | scan | student_baseline | 1 / 0 | 20598 | 2885 | $0.0000 |
| student_pollinations | scan | student_with_lessons | 1 / 0 | 23390 | 3972 | $0.0000 |
| teacher | scan | teacher | 1 / 0 | 36014 | 5491 | $0.1269 |
| teacher | distill | distill | 0 / 1 | 5782 | 1247 | $0.0000 |
| teacher | repair | distill | 1 / 0 | 6301 | 6547 | $0.0781 |
| judge_pollinations | judge_confirm | enriched:student_baseline | 0 / 14 | 5863 | 1252 | $0.0000 |
| judge_pollinations | judge_confirm | enriched:student_with_lessons | 0 / 18 | 7805 | 1650 | $0.0000 |
| judge_pollinations | judge_confirm | enriched:teacher | 0 / 19 | 8605 | 1628 | $0.0000 |
| judge_pollinations | judge_confirm | readme:student_baseline | 0 / 20 | 7525 | 1604 | $0.0000 |
| judge_pollinations | judge_confirm | readme:student_with_lessons | 0 / 21 | 8308 | 1808 | $0.0000 |
| judge_pollinations | judge_confirm | readme:teacher | 0 / 25 | 10182 | 2101 | $0.0000 |
| judge_pollinations | judge_screen | enriched:student_baseline | 0 / 3 | 8403 | 740 | $0.0000 |
| judge_pollinations | judge_screen | enriched:student_with_lessons | 0 / 3 | 9861 | 893 | $0.0000 |
| judge_pollinations | judge_screen | enriched:teacher | 0 / 3 | 10527 | 961 | $0.0000 |
| judge_pollinations | judge_screen | readme:student_baseline | 0 / 3 | 5880 | 757 | $0.0000 |
| judge_pollinations | judge_screen | readme:student_with_lessons | 0 / 3 | 7338 | 898 | $0.0000 |
| judge_pollinations | judge_screen | readme:teacher | 0 / 3 | 8004 | 984 | $0.0000 |
| student_pollinations | scan | student_with_lessons | 1 / 0 | 20800 | 3120 | $0.0000 |
| teacher | distill | distill | 1 / 0 | 5782 | 1247 | $0.0240 |
| judge_pollinations | judge_confirm | enriched:student_baseline | 14 / 0 | 5854 | 1263 | $0.0000 |
| judge_pollinations | judge_confirm | enriched:student_with_lessons | 12 / 0 | 5213 | 1077 | $0.0000 |
| judge_pollinations | judge_confirm | enriched:teacher | 19 / 0 | 8589 | 1645 | $0.0000 |
| judge_pollinations | judge_confirm | readme:student_baseline | 20 / 0 | 7516 | 1610 | $0.0000 |
| judge_pollinations | judge_confirm | readme:student_with_lessons | 15 / 0 | 5889 | 1129 | $0.0000 |
| judge_pollinations | judge_confirm | readme:teacher | 22 / 0 | 8967 | 1835 | $0.0000 |
| judge_pollinations | judge_screen | enriched:student_baseline | 1 / 0 | 2791 | 244 | $0.0000 |
| judge_pollinations | judge_screen | enriched:student_with_lessons | 1 / 0 | 2912 | 248 | $0.0000 |
| judge_pollinations | judge_screen | enriched:teacher | 2 / 0 | 5044 | 318 | $0.0000 |
| judge_pollinations | judge_screen | readme:student_baseline | 1 / 0 | 1950 | 271 | $0.0000 |
| judge_pollinations | judge_screen | readme:student_with_lessons | 1 / 0 | 2071 | 248 | $0.0000 |
| judge_pollinations | judge_screen | readme:teacher | 2 / 0 | 3362 | 332 | $0.0000 |

Billed in this run: $0.2290. Including calls served from the response cache at their original price: $0.2531. Free-tier models are shown at $0 (tokens are still counted).

## 8. Prompt versions

| Prompt | sha256 |
|---|---|
| `distill/v2` | `8d23a5746d60` |
| `judge_confirm/v1` | `cbbc6bf53622` |
| `judge_screen/v1` | `0d8f84acdbc7` |
| `scan/v3` | `500b9aa551c7` |

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
