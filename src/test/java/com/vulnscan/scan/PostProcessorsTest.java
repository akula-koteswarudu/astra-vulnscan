package com.vulnscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.vulnscan.ingest.CodeBase;
import com.vulnscan.planning.ScanUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PostProcessorsTest {

    @TempDir
    Path dir;
    EvidenceVerifier verifier;
    ScanUnit unit;

    static final String A = "sources/a/A.java";
    static final String B = "sources/a/B.java";

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(dir.resolve("sources/a"));
        Files.writeString(dir.resolve(A), "class A {\n    void go() {\n        webView.loadUrl(getIntent().getStringExtra(\"url\"));\n    }\n}\n");
        Files.writeString(dir.resolve(B), "class B {\n    void log(String password) {\n        Log.d(\"password\", password);\n    }\n}\n");
        verifier = new EvidenceVerifier(new CodeBase(dir), 90, 25);
        unit = new ScanUnit("u", "component", "A", List.of(A, B),
                "<application android:debuggable=\"true\">\n</application>",
                "resources/res/xml/paths.xml (FileProvider p.FP): <root-path name=\"root\" path=\"/\"/>",
                "androidx.core:core 1.3.1\nnet.lib.upload 3.2.3\ncom.lib.other (version unknown)", 100, 1);
    }

    static RawFinding f(String file, String evidence, String kind) {
        return new RawFinding("t", "c", "CWE-79", null, "high", 0.5, List.of(new RawFinding.RawLocation(file, null, null)), evidence, "r", kind);
    }

    @Test
    void exactAndReindentedEvidenceIsVerifiedWithLine() {
        var c = verifier.verify(f(A, "webView.loadUrl(getIntent().getStringExtra(\"url\"));", "code"), unit);
        assertThat(c.status()).isEqualTo("verified_exact");
        assertThat(c.line()).isEqualTo(3);
        var reindented = verifier.verify(f(A, "void go() {\n  webView.loadUrl(getIntent().getStringExtra(\"url\"));", "code"), unit);
        assertThat(reindented.status()).isEqualTo("verified_exact");
        assertThat(reindented.line()).isEqualTo(2);
        assertThat(reindented.endLine()).isEqualTo(3);
    }

    @Test
    void wrongFileIsRelocated() {
        var c = verifier.verify(f(A, "Log.d(\"password\", password);", "code"), unit);
        assertThat(c.status()).isEqualTo("relocated");
        assertThat(c.file()).isEqualTo(B);
    }

    @Test
    void shortSnippetIsNotRelocatedOrFuzzy() {
        assertThat(verifier.verify(f(A, "Log.d(", "code"), unit).status()).isEqualTo("unverified");
        assertThat(verifier.verify(f(A, "loadUrl(x)", "code"), unit).status()).isEqualTo("unverified");
    }

    @Test
    void nearMissIsFuzzyAndInventedIsUnverified() {
        assertThat(verifier.verify(f(A, "webView.loadUrl(getIntent().getStringExtra(\"uri\"));", "code"), unit).status())
                .isEqualTo("verified_fuzzy");
        assertThat(verifier.verify(f(A, "Runtime.getRuntime().exec(userSuppliedCommand);", "code"), unit).status())
                .isEqualTo("unverified");
    }

    @Test
    void quoteThatSkipsADecompilerLineIsVerifiedButInventedLinesAreNot() throws IOException {
        Files.writeString(dir.resolve(A), "class A {\n    void go() {\n        Uri uri = Uri.fromFile(new File(extraPath));\n"
                + "        Intrinsics.checkExpressionValueIsNotNull(uri, \"x\");\n        makeTempCopy(uri, getFilename(uri));\n    }\n}\n");
        var ok = verifier.verify(f(A, "Uri uri = Uri.fromFile(new File(extraPath));\nmakeTempCopy(uri, getFilename(uri));", "code"), unit);
        assertThat(ok.status()).isEqualTo("verified_fuzzy");
        assertThat(ok.line()).isEqualTo(3);
        assertThat(ok.endLine()).isEqualTo(5);
        var invented = verifier.verify(f(A, "Uri uri = Uri.fromFile(new File(extraPath));\nRuntime.getRuntime().exec(cmdFromIntent);", "code"), unit);
        assertThat(invented.status()).isEqualTo("unverified");
        // real lines in a different order (e.g. "setting, then the call") are accepted within a small span
        var reversed = verifier.verify(f(A, "makeTempCopy(uri, getFilename(uri));\nUri uri = Uri.fromFile(new File(extraPath));", "code"), unit);
        assertThat(reversed.status()).isEqualTo("verified_fuzzy");
        assertThat(reversed.line()).isEqualTo(3);
        assertThat(reversed.endLine()).isEqualTo(5);
    }

    @Test
    void rearrangedLinesFarApartAreNotAccepted() throws IOException {
        StringBuilder far = new StringBuilder("class A {\n    void go() { settings.setAllowFileAccessFromFileURLs(true); }\n");
        for (int i = 0; i < 40; i++) {
            far.append("    int filler").append(i).append(" = 0;\n");
        }
        far.append("    void later() { webView.loadUrl(getIntent().getStringExtra(\"url\")); }\n}\n");
        Files.writeString(dir.resolve(A), far.toString());
        var c = verifier.verify(f(A, "webView.loadUrl(getIntent().getStringExtra(\"url\"));\nsettings.setAllowFileAccessFromFileURLs(true);", "code"), unit);
        assertThat(c.status()).isEqualTo("unverified");
    }

    @Test
    void abbreviatedQuoteWithEllipsisIsFuzzy() {
        var c = verifier.verify(f(A, "void go() { ... webView.loadUrl(getIntent()", "code"), unit);
        assertThat(c.status()).isEqualTo("verified_fuzzy");
    }

    @Test
    void manifestAndResourceEvidenceIsCheckedAgainstContext() {
        assertThat(verifier.verify(f("AndroidManifest.xml", "android:debuggable=\"true\"", "manifest"), unit).status()).isEqualTo("verified_exact");
        var res = verifier.verify(f("res/xml/paths.xml", "<root-path name=\"root\" path=\"/\"/>", "resource"), unit);
        assertThat(res.status()).isEqualTo("verified_exact");
        assertThat(res.file()).isEqualTo("resources/res/xml/paths.xml");
    }

    @Test
    void dependencyEvidenceMustMatchInventory() {
        var match = verifier.verify(f("x", "net.lib.upload 3.2.3", "dependency"), unit);
        assertThat(match.status()).isEqualTo("inventory_match");
        assertThat(match.needsAdvisoryCheck()).isTrue();
        assertThat(match.file()).isEqualTo("dependency:net.lib.upload");
        assertThat(verifier.verify(f("x", "net.lib.upload:3.2.3", "dependency"), unit).status()).isEqualTo("inventory_match");
        assertThat(verifier.verify(f("x", "net.lib.upload 9.9.9", "dependency"), unit).status()).isEqualTo("inventory_unmatched");
        assertThat(verifier.verify(f("x", "okhttp 2.0", "dependency"), unit).status()).isEqualTo("inventory_unmatched");
    }

    @Test
    void normalizerCanonicalises() {
        RawFinding raw = new RawFinding("t", null, "cwe 0079", "masvs-platform", "HIGH", 1.7,
                List.of(new RawFinding.RawLocation("/sources/a/A.java", "null", "A$1.go()")), "e", null, null);
        RawFinding n = new Normalizer().normalize(raw);
        assertThat(n.cwe()).isEqualTo("CWE-79");
        assertThat(n.severity()).isEqualTo("high");
        assertThat(n.kind()).isEqualTo("code");
        assertThat(n.confidence()).isEqualTo(1.0);
        assertThat(n.owaspCategory()).isEqualTo("MASVS-PLATFORM");
        assertThat(n.locations().get(0).file()).isEqualTo("sources/a/A.java");
        assertThat(n.locations().get(0).component()).isNull();
        assertThat(n.locations().get(0).symbol()).isEqualTo("A.go");
        assertThat(n.vulnClass()).isEqualTo("t");
    }

    @Test
    void splitterMakesOneFindingPerLocation() {
        RawFinding raw = new RawFinding("t", "c", "CWE-1", null, "low", 0.5, List.of(new RawFinding.RawLocation(A, null, "A"),
                new RawFinding.RawLocation(B, null, "B")), "e", "r", "code");
        assertThat(new Splitter().split(raw)).extracting(x -> x.locations().get(0).file()).containsExactly(A, B);
    }

    static Finding finding(String id, String file, String symbol, String cwe, Integer line, double conf, String unitId, String status) {
        return new Finding(id, "t", "c", cwe, null, "high", conf, new Finding.Location(file, null, symbol, line, line == null ? null : line + 1),
                "e", "r", "code", status, false, new Finding.Provenance("m", "arm", "r", 0, List.of(unitId), "v1"));
    }

    @Test
    void dedupMergesAcrossOverlappingUnits() {
        Deduplicator d = new Deduplicator(Map.of("webview", List.of("CWE-79", "CWE-749")));
        List<Finding> out = d.dedup(List.of(
                finding("1", A, "A.go", "CWE-79", 3, 0.6, "u1", "verified_exact"),
                finding("2", A, "A.go", "CWE-79", 3, 0.9, "u2", "verified_exact"),     // same cwe+file+symbol
                finding("3", A, "A.other", "CWE-749", 4, 0.5, "u3", "verified_exact"), // overlapping lines, same group
                finding("4", B, "B.log", "CWE-532", 3, 0.5, "u1", "verified_exact")));
        assertThat(out).hasSize(2);
        Finding merged = out.stream().filter(x -> x.location().file().equals(A)).findFirst().orElseThrow();
        assertThat(merged.confidence()).isEqualTo(0.9);
        assertThat(merged.provenance().unitIds()).containsExactly("u1", "u2", "u3");
    }

    @Test
    void dedupKeepsTheSameCopyWhateverTheIds() {
        Deduplicator d = new Deduplicator(Map.of());
        // same finding at two locations, same confidence: exact evidence must win, independent of id order
        Finding exactA = finding("zz", A, "A.go", "CWE-79", 3, 0.5, "u", "verified_exact");
        Finding relocB = new Finding("aa", "t", "c", "CWE-79", null, "high", 0.5, new Finding.Location(A, null, "A.go", 3, 4), "e", "r", "code",
                "relocated", false, new Finding.Provenance("m", "arm", "r", 0, List.of("u"), "v1"));
        assertThat(d.dedup(List.of(exactA, relocB))).singleElement().satisfies(x -> assertThat(x.evidenceStatus()).isEqualTo("verified_exact"));
        Finding exactA2 = finding("aa", A, "A.go", "CWE-79", 3, 0.5, "u", "verified_exact");
        Finding relocB2 = new Finding("zz", "t", "c", "CWE-79", null, "high", 0.5, new Finding.Location(A, null, "A.go", 3, 4), "e", "r", "code",
                "relocated", false, new Finding.Provenance("m", "arm", "r", 0, List.of("u"), "v1"));
        assertThat(d.dedup(List.of(relocB2, exactA2))).singleElement().satisfies(x -> assertThat(x.evidenceStatus()).isEqualTo("verified_exact"));
    }

    @Test
    void mergeIsLosslessAndCanBeSwitchedOff() {
        Finding a = finding("1", A, "A.onCreate", "CWE-926", 3, 0.6, "u1", "verified_exact");
        Finding b = new Finding("2", "t", "c", "CWE-926", null, "high", 0.6, new Finding.Location(A, null, "Recv.onReceive", 3, 4), "e", "r",
                "code", "relocated", false, new Finding.Provenance("m", "arm", "r", 0, List.of("u1"), "v1"));
        Finding merged = new Deduplicator(Map.of()).dedup(List.of(b, a)).get(0);
        assertThat(merged.location().symbol()).isEqualTo("A.onCreate");
        assertThat(merged.alsoAt()).extracting(Finding.Location::symbol).containsExactly("Recv.onReceive");
        assertThat(merged.allLocations()).hasSize(2);
        assertThat(new Deduplicator(Map.of(), false).dedup(List.of(b, a))).hasSize(2);
    }

    @Test
    void dedupPrefersTheVerifiedCopy() {
        Deduplicator d = new Deduplicator(Map.of());
        List<Finding> out = d.dedup(List.of(
                finding("1", A, "A.go", "CWE-79", null, 0.99, "u1", "unverified"),
                finding("2", A, "A.go", "CWE-79", 3, 0.5, "u2", "verified_exact")));
        assertThat(out).singleElement().satisfies(x -> assertThat(x.evidenceStatus()).isEqualTo("verified_exact"));
    }

    static Finding quoted(String id, String file, String symbol, String cwe, String evidence, String kind) {
        return new Finding(id, "t" + id, "c", cwe, null, "low", 0.5, new Finding.Location(file, null, symbol, null, null),
                evidence, "r", kind, "verified_exact", false, new Finding.Provenance("m", "arm", "r", 0, List.of("u" + id), "v1"));
    }

    @Test
    void sameManifestSettingFromManyUnitsIsOneFindingWhateverTheCwe() {
        Deduplicator d = new Deduplicator(Map.of());
        List<Finding> out = d.dedup(List.of(
                quoted("1", "AndroidManifest.xml", null, "CWE-312", "android:allowBackup=\"true\"", "manifest"),
                quoted("2", "AndroidManifest.xml", null, "CWE-922", "android:allowBackup=\"true\"", "manifest"),
                quoted("3", "AndroidManifest.xml", "Net.call", null, "android:allowBackup=\"true\"", "manifest")));
        assertThat(out).singleElement().satisfies(x -> assertThat(x.provenance().unitIds()).containsExactly("u1", "u2", "u3"));
    }

    @Test
    void sameQuotedCodeWithoutCweMergesButDisagreeingCwesStayApart() {
        Deduplicator d = new Deduplicator(Map.of("storage", List.of("CWE-312", "CWE-922")));
        String line = "File f = new File(param);";
        // no CWE on either copy, different symbols: same quoted line in the same file is the same issue
        assertThat(d.dedup(List.of(quoted("1", A, "X.a", null, line, "code"), quoted("2", A, "X.b", null, "File  f = new File(param);", "code"))))
                .hasSize(1);
        // same line quoted for two unrelated weaknesses: kept apart
        assertThat(d.dedup(List.of(quoted("1", A, "X.a", "CWE-22", line, "code"), quoted("2", A, "X.b", "CWE-312", line, "code"))))
                .hasSize(2);
    }

    @Test
    void findingsWithoutAFileMergeOnlyForTheSameComponent() {
        Deduplicator d = new Deduplicator(Map.of());
        Finding a = new Finding("1", "t", "c", "CWE-926", null, "low", 0.5, new Finding.Location(null, "x.A", null, null, null),
                "e1", "r", "manifest", "verified_exact", false, new Finding.Provenance("m", "arm", "r", 0, List.of("u1"), "v1"));
        Finding b = new Finding("2", "t", "c", "CWE-926", null, "low", 0.5, new Finding.Location(null, "x.B", null, null, null),
                "e2", "r", "manifest", "verified_exact", false, new Finding.Provenance("m", "arm", "r", 0, List.of("u2"), "v1"));
        assertThat(d.dedup(List.of(a, b))).hasSize(2);
    }

    @Test
    void wholeManifestTagNeedsAgreeingCwes() {
        Deduplicator d = new Deduplicator(Map.of());
        String tag = "<provider android:authorities=\"a.b\" android:exported=\"true\">";
        assertThat(d.dedup(List.of(quoted("1", "AndroidManifest.xml", null, "CWE-200", tag, "manifest"),
                quoted("2", "AndroidManifest.xml", "P", "CWE-200", tag, "manifest")))).hasSize(1);
        assertThat(d.dedup(List.of(quoted("1", "AndroidManifest.xml", null, "CWE-200", tag, "manifest"),
                quoted("2", "AndroidManifest.xml", null, "CWE-732", tag, "manifest")))).hasSize(2);
    }

    @Test
    void partialRatioBehavesLikeRapidfuzz() {
        assertThat(TextMatch.partialRatio("hello world", "say hello world!")).isEqualTo(100.0);
        assertThat(TextMatch.partialRatio("hello wurld", "say hello world!")).isGreaterThan(85.0).isLessThan(100.0);
        assertThat(TextMatch.partialRatio("abc", "xyz xyz")).isLessThan(50.0);
    }
}
