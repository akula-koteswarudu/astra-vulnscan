# Scan report: ovaa

Run `2026-11-15T1043_ovaa_d4186f7b` · profile `assignment` · generated from the run folder (every number below is traceable to a file in it).

## 1. Core result

Headline: the student's recall against the README list before and after the lessons, judged strictly, counting verified findings only. The teacher row is the ceiling.

| Arm | Model | Recall (strict, verified) | Lenient | Incl. unverified | Precision (strict) | Findings (verified / total) |
|---|---|---|---|---|---|---|
| teacher (ceiling) | claude-sonnet-5-5 | **15/18 (83%)** | 15/18 (83%) | 15/18 (83%) | 71% | 21 / 21 |
| student baseline | qwen/qwen3.8-27b | **11/18 (61%)** | 11/18 (61%) | 11/18 (61%) | 73% | 15 / 15 |
| student baseline (run 2) | qwen/qwen3.8-27b | **10/18 (56%)** | 10/18 (56%) | 10/18 (56%) | 67% | 15 / 15 |
| student baseline (run 3) | qwen/qwen3.8-27b | **11/18 (61%)** | 11/18 (61%) | 11/18 (61%) | 75% | 16 / 16 |
| student with lessons | qwen/qwen3.8-27b | **12/18 (67%)** | 12/18 (67%) | 12/18 (67%) | 86% | 14 / 16 |
| student with lessons (run 2) | qwen/qwen3.8-27b | **11/18 (61%)** | 11/18 (61%) | 11/18 (61%) | 69% | 16 / 16 |
| student with lessons (run 3) | qwen/qwen3.8-27b | **10/18 (56%)** | 11/18 (61%) | 10/18 (56%) | 63% | 16 / 16 |

**Lift: +1.9 points of recall (11/18 (59%) → 11/18 (61%), strict, verified). Mean of 3 runs per arm; ranges 56%–61% and 56%–67%.**

Strict = the judge said "match"; lenient also counts "partial". One-to-one assignment (Hungarian): each reference item is matched by at most one finding and vice versa. Precision counts findings that match any item (many-to-one) over verified findings; unmatched findings are "unmatched", not necessarily false positives (the README list may be incomplete).

### Per item (README ground truth, 18 items)

✓ = strict match, ◐ = the judge said match but that finding was credited to another item (one finding counts once), ~ = partial only, · = not matched. Static visibility is the author's label: *partial* and *inventory_only* items cannot be fully seen in the client code.

| Item | Title | Static visibility | teacher (ceiling) | student baseline | student with lessons |
|---|---|---|---|---|---|
| GT-01 | Installation of an arbitrary `login_url` via deeplink `oversecured://ovaa/login?url=http://evil.com/` |  | ✓ | ✓ 3/3 | ✓ 1/3 |
| GT-02 | Obtaining access to arbitrary content providers (not exported, but with the attribute `android:grantUriPermissions="true"`) via deeplink `oversecured://ovaa/grant_uri_permissions` |  | ✓ | · | · |
| GT-03 | Vulnerable host validation when processing deeplink `oversecured://ovaa/webview?url=...` |  | ✓ | · | ✓ 3/3 |
| GT-04 | Opening arbitrary URLs via deeplink `oversecured://ovaa/webview?url=http://evilexample.com` |  | ✓ | ✓ 3/3 | ✓ 3/3 |
| GT-05 | Access to arbitrary activities and acquiring access to arbitrary content providers in `LoginActivity` by supplying an arbitrary Intent object to `redirect_intent` |  | ✓ | ✓ 3/3 | ✓ 3/3 |
| GT-06 | Theft of arbitrary files in `MainActivity` by intercepting an activity launch from `Intent.ACTION_PICK` and passing the URI to any file as data |  | · | · | ~ 1/3 |
| GT-07 | Insecure broadcast to `MainActivity` containing credentials |  | ✓ | ✓ 2/3 | ✓ 3/3 |
| GT-08 | Insecure activity launch in `MainActivity` with action `oversecured.ovaa.action.WEBVIEW`, containing the user's encrypted data in the query parameter `token` |  | · | · | · |
| GT-09 | Deletion of arbitrary files via the insecure `DeleteFilesSerializable` deserialization object |  | ✓ | ✓ 1/3 | · |
| GT-10 | Memory corruption via the `MemoryCorruptionParcelable` object |  | ✓ | ✓ 1/3 | · |
| GT-11 | Memory corruption via the `MemoryCorruptionSerializable` object |  | · | · | · |
| GT-12 | Obtaining read/write access to arbitrary files in `TheftOverwriteProvider` via path-traversal in the value `uri.getLastPathSegment()` |  | ✓ | ✓ 3/3 | ✓ 3/3 |
| GT-13 | Obtaining access to app logs via `InsecureLoggerService` |  | ✓ | ✓ 3/3 | ✓ 3/3 |
| GT-14 | Use of the hardcoded AES key in `WeakCrypto` |  | ✓ | ✓ 3/3 | ✓ 3/3 |
| GT-15 | Arbitrary Code Execution in `OversecuredApplication` by launching code from third-party apps with no security checks |  | ✓ | ✓ 3/3 | ✓ 3/3 |
| GT-16 | Use of very wide file sharing declaration for `oversecured.ovaa.fileprovider` content provider in `root` entry |  | ✓ | ✓ 3/3 | ✓ 3/3 |
| GT-17 | Hardcoded credentials to a dev environment endpoint in `strings.xml` in `test_url` entry |  | ✓ | ✓ 1/3 | ✓ 2/3 |
| GT-18 | Arbitrary code execution via a DEX library located in a world-readable/writable directory |  | ✓ | ✓ 3/3 | ✓ 3/3 |

### Findings (run 1 of each arm)

Every finding after evidence checking and dedup, highest severity first. "Credited" is the reference item it was assigned to (strict); "-" means it matched no item, which does not by itself make it a false positive.

<details><summary>teacher: 21 finding(s)</summary>

| # | Finding | Severity | CWE | Where | Evidence | Credited |
|---|---|---|---|---|---|---|
| 1 | Credentials sent over cleartext HTTP | high | CWE-319 | `oversecured/ovaa/network/RetrofitInstance.java:8 (RetrofitInstance) +1 more` | verified_exact | - |
| 2 | Deeplink can set the login URL to an attacker server | high | CWE-345 | `oversecured/ovaa/activities/DeeplinkActivity.java:37 (DeeplinkActivity.processDeeplink) +1 more` | verified_exact | GT-01 |
| 3 | DexClassLoader loads code from world-writable /sdcard/updater.apk | high | CWE-94 | `oversecured/ovaa/OversecuredApplication.java:38 (OversecuredApplication.updateChecker)` | verified_exact | GT-18 |
| 4 | Exported LoginActivity starts an attacker-supplied intent | high | CWE-926 | `oversecured/ovaa/activities/LoginActivity.java:75 (LoginActivity.onLoginFinished)` | verified_exact | GT-05 |
| 5 | Exported provider opens arbitrary files via an unvalidated path segment, with read/write | high | CWE-22 | `oversecured/ovaa/providers/TheftOverwriteProvider.java:46 (TheftOverwriteProvider.openFile)` | verified_exact | GT-12 |
| 6 | Exported service writes logcat to a caller-supplied path | high | CWE-73 | `oversecured/ovaa/services/InsecureLoggerService.java:29 (InsecureLoggerService.getDumpFile)` | verified_exact | - |
| 7 | FileProvider exposes the entire filesystem root | high | CWE-552 | `resources/res/xml/provider_paths.xml` | verified_exact | GT-16 |
| 8 | Result from an implicit intent is reflected to the caller, leaking URI grants | high | CWE-926 | `oversecured/ovaa/activities/DeeplinkActivity.java:47 (DeeplinkActivity.onActivityResult)` | verified_exact | GT-02 |
| 9 | WebView with JS and file access from file URLs loads intent URL | high | CWE-749 | `oversecured/ovaa/activities/WebViewActivity.java:24 (WebViewActivity.setupWebView)` | verified_exact | GT-04 |
| 10 | AES used with the default ECB mode | medium | CWE-327 | `oversecured/ovaa/utils/WeakCrypto.java:17 (WeakCrypto.encrypt)` | verified_exact | - |
| 11 | Application is debuggable and allows backup | medium | CWE-1188 | `AndroidManifest.xml` | verified_exact | - |
| 12 | Credential token sent in a URL over HTTP | medium | CWE-319 | `oversecured/ovaa/activities/MainActivity.java:50 (MainActivity.onCreate)` | verified_exact | - |
| 13 | Credentials sent in an implicit broadcast with no permission | medium | CWE-927 | `oversecured/ovaa/activities/MainActivity.java:40 (MainActivity.onCreate)` | verified_exact | GT-07 |
| 14 | Credentials written to logcat | medium | CWE-532 | `oversecured/ovaa/activities/LoginActivity.java:59 (LoginActivity.processLogin)` | verified_exact | GT-13 |
| 15 | Deeplink WebView URL check bypassable via endsWith suffix | medium | CWE-601 | `oversecured/ovaa/activities/DeeplinkActivity.java:52 (DeeplinkActivity.processDeeplink)` | verified_exact | GT-03 |
| 16 | Hardcoded AES key | medium | CWE-321 | `oversecured/ovaa/utils/WeakCrypto.java:9 (WeakCrypto)` | verified_exact | GT-14 |
| 17 | Parcelable instantiates an attacker-chosen class from a Parcel string | medium | CWE-502 | `oversecured/ovaa/objects/MemoryCorruptionParcelable.java:26 (MemoryCorruptionParcelable.<init>)` | verified_exact | GT-10 |
| 18 | Password stored in plaintext SharedPreferences | medium | CWE-312 | `oversecured/ovaa/utils/LoginUtils.java:37 (LoginUtils.saveCredentials)` | verified_exact | - |
| 19 | Plugin code loaded from any package matching a name prefix | medium | CWE-470 | `oversecured/ovaa/OversecuredApplication.java:25 (OversecuredApplication.invokePlugins)` | verified_exact | GT-15 |
| 20 | Serializable deletes files named in the stream on deserialization | medium | CWE-502 | `oversecured/ovaa/objects/DeleteFilesSerializable.java:12 (DeleteFilesSerializable.readObject)` | verified_exact | GT-09 |
| 21 | Hardcoded credentials in a URL resource | low | CWE-522 | `res/values/strings.xml` | verified_exact | GT-17 |

</details>

<details><summary>student_baseline: 15 finding(s)</summary>

| # | Finding | Severity | CWE | Where | Evidence | Credited |
|---|---|---|---|---|---|---|
| 1 | Debuggable build allows code injection and memory dump | critical | CWE-489 | `AndroidManifest.xml` | verified_exact | - |
| 2 | Arbitrary code execution via DexClassLoader | high | CWE-925 | `oversecured/ovaa/OversecuredApplication.java:38 (OversecuredApplication.updateChecker)` | verified_exact | GT-18 |
| 3 | Arbitrary file read via exported ContentProvider | high | CWE-22 | `oversecured/ovaa/providers/TheftOverwriteProvider.java:46 (TheftOverwriteProvider.openFile)` | verified_exact | GT-12 |
| 4 | Arbitrary file write via exported Service | high | CWE-22 | `oversecured/ovaa/services/InsecureLoggerService.java:29 (InsecureLoggerService.getDumpFile)` | verified_exact | - |
| 5 | Cleartext HTTP traffic for login | high | CWE-319 | `oversecured/ovaa/network/RetrofitInstance.java:8 (RetrofitInstance)` | verified_exact | - |
| 6 | Intent Redirection in LoginActivity | high | CWE-926 | `oversecured/ovaa/activities/LoginActivity.java:75 (LoginActivity.onLoginFinished)` | verified_exact | GT-05 |
| 7 | Plugin loading from other packages | high | CWE-925 | `oversecured/ovaa/OversecuredApplication.java:27 (OversecuredApplication.invokePlugins)` | verified_exact | GT-15 |
| 8 | Sensitive data broadcast without protection | high | CWE-319 | `oversecured/ovaa/activities/MainActivity.java:39 (MainActivity.onCreate)` | verified_exact | GT-07 |
| 9 | Weak encryption with hardcoded key | high | CWE-321 | `oversecured/ovaa/utils/WeakCrypto.java:9 (WeakCrypto)` | verified_exact | GT-14 |
| 10 | Credentials stored in plaintext SharedPreferences | medium | CWE-312 | `oversecured/ovaa/utils/LoginUtils.java:36 (LoginUtils.saveCredentials)` | verified_exact | - |
| 11 | Deeplink allows setting arbitrary login URL | medium | CWE-20 | `oversecured/ovaa/activities/DeeplinkActivity.java:36 (DeeplinkActivity.processDeeplink)` | verified_exact | GT-01 |
| 12 | FileProvider exposes entire filesystem | medium | CWE-22 | `resources/res/xml/provider_paths.xml` | verified_exact | GT-16 |
| 13 | Hardcoded credentials in resources | medium | CWE-798 | `res/values/strings.xml` | verified_exact | GT-17 |
| 14 | Log output contains sensitive data | medium | CWE-532 | `oversecured/ovaa/activities/LoginActivity.java:59 (LoginActivity.processLogin)` | verified_exact | GT-13 |
| 15 | WebView allows file access from file URLs | medium | CWE-749 | `oversecured/ovaa/activities/WebViewActivity.java:24 (WebViewActivity.setupWebView)` | verified_exact | GT-04 |

</details>

<details><summary>student_with_lessons: 16 finding(s)</summary>

| # | Finding | Severity | CWE | Where | Evidence | Credited |
|---|---|---|---|---|---|---|
| 1 | DexClassLoader loads code from World-Readable Path | critical | CWE-94 | `oversecured/ovaa/OversecuredApplication.java:38 (OversecuredApplication.updateChecker)` | verified_fuzzy | GT-18 |
| 2 | Dynamic Code Loading from Untrusted Package | critical | CWE-94 | `oversecured/ovaa/OversecuredApplication.java:27 (OversecuredApplication.invokePlugins)` | verified_fuzzy | GT-15 |
| 3 | Cleartext HTTP Traffic | high | CWE-319 | `oversecured/ovaa/network/RetrofitInstance.java:8 (RetrofitInstance)` | verified_exact | - |
| 4 | Credentials stored in plaintext SharedPreferences | high | CWE-312 | `oversecured/ovaa/utils/LoginUtils.java:37 (LoginUtils.saveCredentials)` | verified_exact | - |
| 5 | Debuggable build with backup enabled | high | CWE-489 | `AndroidManifest.xml` | unverified | - |
| 6 | DeepLink allows arbitrary Login URL injection | high | CWE-20 | `oversecured/ovaa/activities/DeeplinkActivity.java:37 (DeeplinkActivity.processDeeplink)` | verified_exact | GT-01 |
| 7 | Exported ContentProvider allows Arbitrary File Read/Write | high | CWE-926 | `oversecured/ovaa/providers/TheftOverwriteProvider.java:46 (TheftOverwriteProvider.openFile)` | verified_exact | GT-12 |
| 8 | Exported Service allows Arbitrary File Write | high | CWE-926 | `oversecured/ovaa/services/InsecureLoggerService.java (InsecureLoggerService.onHandleIntent)` | unverified | - |
| 9 | FileProvider exposes root filesystem | high | CWE-668 | `resources/res/xml/provider_paths.xml` | verified_exact | GT-16 |
| 10 | Hardcoded AES Key and Weak Encryption | high | CWE-798 | `oversecured/ovaa/utils/WeakCrypto.java:9 (WeakCrypto)` | verified_exact | GT-14 |
| 11 | Intent Redirection in LoginActivity | high | CWE-927 | `oversecured/ovaa/activities/LoginActivity.java:75 (LoginActivity.onLoginFinished)` | verified_fuzzy | GT-05 |
| 12 | Sensitive Data in Implicit Broadcast | high | CWE-927 | `oversecured/ovaa/activities/MainActivity.java:39 (MainActivity.onCreate)` | verified_exact | GT-07 |
| 13 | WebView loads unvalidated URL with File Access | high | CWE-79 | `oversecured/ovaa/activities/WebViewActivity.java:18 (WebViewActivity.onCreate)` | verified_fuzzy | GT-04 |
| 14 | Credentials Logged to Logcat | medium | CWE-532 | `oversecured/ovaa/activities/LoginActivity.java:59 (LoginActivity.processLogin)` | verified_exact | GT-13 |
| 15 | DeepLink WebView URL validation bypass | medium | CWE-939 | `oversecured/ovaa/activities/DeeplinkActivity.java:52 (DeeplinkActivity.processDeeplink)` | verified_exact | GT-03 |
| 16 | Hardcoded Credentials in Resources | medium | CWE-798 | `res/values/strings.xml` | verified_exact | GT-17 |

</details>

## 2. Lessons

15 lessons kept after sanitising (lessons_from:runs/2026-11-14T1846_insecureshop_9f96bd98/05_learn/lessons.json); 15 selected for the student (3583 tokens, within the reserved budget).

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

Sanitiser (redact): 0 term(s) redacted, 0 example(s) removed by the answer-key check, 0 lesson(s) dropped; 0 lesson(s) left out by the count/token caps. Full records: 05_learn/rejected_lessons.json.

## 3. Robustness check: README-only vs enriched reference

Not computed (no enriched ground truth for this app).

## 4. Judge audit

Not run on this run folder (an audit labels one run's judge decisions). The same judge model and prompts may have been audited on another run; see that run's report. To audit this run: `vulnscan audit export` writes blind rows to label, `vulnscan audit score` measures agreement.

## 5. Betterments

None enabled in this run (profile `assignment`): 3 run(s) per student arm, no checklist control arm.

## 6. Coverage and quality

| Arm | Units | Unit status | Evidence status | Bad findings dropped |
|---|---|---|---|---|
| teacher (ceiling) | 1 | ok 1 | verified_exact 21 | 0 |
| student baseline | 1 | ok 1 | verified_exact 15 | 0 |
| student with lessons | 1 | ok 1 | unverified 2, verified_exact 10, verified_fuzzy 4 | 0 |

- Decompilation: jadx 1.5.6, exit code 3 (partial success: output present); 42 failed-method markers in all code, 0 in first-party code.
- First-party split: 21 of 4119 Java files (prefixes oversecured.ovaa); 1 build-generated files excluded from the app's own packages. Largest excluded groups: (top 5, 1577 of 4098 excluded files) kotlinx.coroutines (503), androidx.core (400), org.apache (286), androidx.constraintlayout (202), kotlinx.serialization (186).
- Units: teacher 1 (whole), student 1 (whole); the two models may scan different units because their budgets differ. All student arms use the same units.
- Judge: 107 confirm calls, 0 unparseable answers (counted as "no").

## 7. Usage

| Model | Purpose | Arm | Calls billed / cached | Input tokens | Output tokens | Cost (billed) |
|---|---|---|---|---|---|---|
| student_pollinations | scan | student_baseline | 0 / 1 | 9579 | 3589 | $0.0000 |
| student_pollinations | scan | student_baseline | 0 / 1 | 9579 | 3310 | $0.0000 |
| student_pollinations | scan | student_baseline | 0 / 1 | 9579 | 3777 | $0.0000 |
| student_pollinations | scan | student_with_lessons | 1 / 0 | 12421 | 3675 | $0.0000 |
| student_pollinations | scan | student_with_lessons | 1 / 0 | 12421 | 3878 | $0.0000 |
| student_pollinations | scan | student_with_lessons | 1 / 0 | 12421 | 4157 | $0.0000 |
| teacher | scan | teacher | 0 / 1 | 17541 | 6132 | $0.0000 |
| judge_pollinations | judge_confirm | readme:student_baseline | 0 / 41 | 17697 | 3375 | $0.0000 |
| judge_pollinations | judge_confirm | readme:student_with_lessons | 0 / 44 | 19656 | 3807 | $0.0000 |
| judge_pollinations | judge_confirm | readme:teacher | 0 / 22 | 9548 | 2063 | $0.0000 |
| judge_pollinations | judge_screen | readme:student_baseline | 0 / 9 | 26130 | 2177 | $0.0000 |
| judge_pollinations | judge_screen | readme:student_with_lessons | 0 / 9 | 26880 | 2278 | $0.0000 |
| judge_pollinations | judge_screen | readme:teacher | 0 / 3 | 10473 | 1015 | $0.0000 |

Billed in this run: $0.0000. Including calls served from the response cache at their original price: $0.0964. Free-tier models are shown at $0 (tokens are still counted).

## 8. Prompt versions

| Prompt | sha256 |
|---|---|
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
