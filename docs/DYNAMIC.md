# Dynamic analysis: extending the static scanner (DRAFT for review)

```text
static scan --> verified findings --> hypothesis queue
                                      (component, entry point, input, sink, oracle)
                                              |
                                              v
   orchestrator --> emulator pool: clean snapshot per job
        |           (app installed, test account logged in, proxy CA, hooks)
        v
   agent loop: observe (UI tree, logcat, proxy) -> act (tap, type, intent, request)
               -> check oracle
        |
        v
   confirmed finding + evidence (canary hit, request/response, screenshot)
        |-----------------> report
        '-----------------> lessons (confirmed vs refuted feeds the teacher->student loop)
```

**Extending this system.** The static pipeline already produces verified findings with a component, entry point and sink. Each one becomes a hypothesis to prove at runtime. The agent reuses the model layer, finding schema and evaluator; only its tools are new (device control, proxy, instrumentation). Code the static scan never flagged gets open exploration.

**Confirming, not crashing.** Each bug class has an oracle that fires only if the attack worked, built on canaries: a unique URL the agent injected appears in the proxy, loaded by the app's WebView; a canary file planted in app-private storage comes back through a provider; a helper "attacker" app's receiver gets a marker value; a request made as user A returns user B's data. A crash is a lead, saved with its stack trace, never a confirmation.

**Reaching the right screen.** The agent reads the accessibility tree rather than pixels, and gets seeded test accounts and data so logins and forms can be completed. Where static analysis found exported components or deep links, it jumps straight there with an intent. Paths that worked are saved as replayable scripts. Coverage: activities reached over activities declared, hypotheses exercised over total, backend endpoints seen in traffic over endpoints found statically. Published LLM GUI agents report roughly 60-75% activity coverage on benchmark apps and far less on real ones, so the list of unreached screens is itself an output.

**Scale.** Headless emulators on KVM hosts. Each job starts from a snapshot with the app installed, logged in, CA and hooks in place; reset means reloading the snapshot in seconds, not reinstalling. A queue feeds a fixed pool; every step has a timeout; a job that fails for infrastructure reasons is retried on another device, and devices that keep failing are quarantined. Infrastructure failures are reported apart from "not vulnerable". Each run gets its own backend test tenant. The key metric is cost and minutes per confirmed finding.

**SSL pinning.** Route traffic through an intercepting proxy, trust its CA as a system CA on a rooted test image, and disable pinning at runtime with hooks on the common checks (OkHttp pinner, TrustManager, network security config). Most likely to break: (1) from Android 14, system CAs load from the read-only Conscrypt APEX, so the CA has to be mounted into each app's mount namespace; (2) apps with their own TLS stack: Flutter ignores the system proxy and CA store, and native pinning needs build-specific native hooks; (3) root, emulator or tamper detection, mutual TLS, and non-HTTP protocols such as gRPC or QUIC.

**Only dynamic catches it:** broken object-level authorisation (IDOR) on the backend. Changing an order id returns another user's order. The check lives on the server, so the client code a static scan reads looks the same whether the server checks or not.
