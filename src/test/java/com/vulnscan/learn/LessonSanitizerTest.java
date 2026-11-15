package com.vulnscan.learn;

import static org.assertj.core.api.Assertions.assertThat;

import com.vulnscan.common.TokenEstimator;
import com.vulnscan.config.AppConfig.OnViolation;
import com.vulnscan.ingest.AppModel;
import com.vulnscan.ingest.CodeBase;
import com.vulnscan.ingest.DecompileStats;
import com.vulnscan.ingest.DecompiledApp;
import com.vulnscan.ingest.IngestorTest;
import com.vulnscan.ingest.LayoutDetector;
import com.vulnscan.scan.Finding;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LessonSanitizerTest {

    @TempDir
    Path dir;
    AppModel app;
    CodeBase code;
    static final Set<String> COMMON = CommonWords.load();

    @BeforeEach
    void setUp() throws IOException {
        IngestorTest.write(dir, "resources/AndroidManifest.xml", """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.zentrabank.mobile">
                  <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="30"/>
                  <application><activity android:name=".TransferActivity" android:exported="true"/></application>
                </manifest>""");
        IngestorTest.write(dir, "resources/res/values/strings.xml", "<resources><string name=\"vault_url\">x</string></resources>");
        IngestorTest.write(dir, "sources/com/zentrabank/mobile/TransferActivity.java", """
                package com.zentrabank.mobile;

                import android.app.Activity;
                import android.content.Intent;
                import android.os.Bundle;
                import android.webkit.WebView;

                public class TransferActivity extends Activity {
                    @Override // android.app.Activity
                    protected void onCreate(Bundle b) {
                        WebView webView = new WebView(this);
                        webView.loadUrl(getIntent().getStringExtra("target"));
                        Intent i = new Intent("android.intent.action.VIEW");
                        String password = "hunter22";
                        String token = "ab12";
                        String ok = "OK";
                        load("file://", "text/html", "Hello");
                    }

                    private void moveFunds(String accountNumber) {
                    }
                }
                """);
        // bundled library code: names used here are library/platform API, not the app's
        IngestorTest.write(dir, "sources/androidx/webkit/WebViewHelper.java",
                "package androidx.webkit;\npublic class WebViewHelper { Object getData() { return webview.query(); } }\n");
        IngestorTest.write(dir, "sources/com/zentrabank/mobile/Prefs.java", """
                package com.zentrabank.mobile;
                public class Prefs {
                    public String getData() { kotlin.jvm.internal.Intrinsics.checkParameterIsNotNull(this, "onReceivedSslError"); return ""; }
                }
                """);
        IngestorTest.write(dir, "sources/com/zentrabank/mobile/ProductDetail.java",
                "package com.zentrabank.mobile;\npublic class ProductDetail { public void show() {} }\n");
        app = IngestorTest.ingestorForTests().ingest(new DecompiledApp("id", new LayoutDetector().detect(dir), "jadx", "1",
                DecompileStats.forDirectoryInput(0, 0, 0), false));
        code = new CodeBase(dir);
    }

    LessonSanitizer sanitizer(OnViolation policy) {
        return new LessonSanitizer(app, code, COMMON, List.of("password", "token", "key", "secret"), policy, 0.6);
    }

    static Lesson lesson(String id, String why, String example, List<String> sources) {
        return new Lesson(id, "Untrusted URL loaded in WebView", new Lesson.AppliesTo(List.of("exported Activity"), List.of("WebView.loadUrl")),
                List.of("getIntent().getStringExtra(<extraKey>) passed to loadUrl"), why, List.of("URL checked against an allow-list"),
                "CWE-939", "MASVS-PLATFORM", "high when JavaScript is enabled", example, sources);
    }

    private List<String> terms(String text) {
        return sanitizer(OnViolation.REDACT).check("L1", "f", text).stream().map(Violation::term).toList();
    }

    @Test
    void packageNameAndDistinctivePartsAreCaught() {
        assertThat(terms("Seen in com.zentrabank.mobile code")).contains("com.zentrabank.mobile");
        assertThat(terms("the Zentrabank app")).contains("Zentrabank"); // tier 2 part, any case
        assertThat(terms("ZentrabankHelper class")).contains("ZentrabankHelper");
    }

    @Test
    void platformNamesAndCallbacksAreAllowed() {
        assertThat(terms("WebView loads getIntent() data in onCreate via loadUrl")).isEmpty();
    }

    @Test
    void wholeIdentifierCaughtEvenIfItsPartsAreCommonWords() {
        assertThat(terms("ProductDetail shows it")).contains("ProductDetail");
        assertThat(terms("product detail page")).isEmpty(); // the parts alone are ordinary words
        assertThat(terms("call moveFunds")).contains("moveFunds");
        assertThat(terms("TransferActivity is exported")).contains("TransferActivity");
    }

    @Test
    void shortIdentifierPartsAreIgnored() {
        // "show" is a method here, but a single common word: never flagged
        assertThat(terms("show the dialog")).isEmpty();
    }

    @Test
    void credentialLiteralsAreCaughtEvenWhenShort() {
        assertThat(terms("log in with hunter22")).contains("hunter22");
        assertThat(terms("the token ab12 is fixed")).contains("ab12");      // short but credential-like
        assertThat(terms("press OK to continue")).isEmpty();                  // short, not credential-like
    }

    @Test
    void platformLiteralsSchemesAndMimeTypesAreNotRedacted() {
        assertThat(terms("an intent with action android.intent.action.VIEW")).isEmpty();
        assertThat(terms("loads file:// URLs as text/html")).isEmpty();
        assertThat(terms("says Hello")).isEmpty(); // single common word literal
    }

    @Test
    void namesUsedInBundledLibraryCodeAreNotAppSpecific() {
        assertThat(terms("call getData() and pass the result to the webview query")).isEmpty();
        assertThat(terms("ProductDetail")).contains("ProductDetail"); // still caught: only the app uses it
    }

    @Test
    void compilerGeneratedNameStringsAreNotLiterals() {
        assertThat(app.stringLiterals()).doesNotContain("onReceivedSslError");
        assertThat(terms("override onReceivedSslError")).isEmpty();
    }

    @Test
    void slashSeparatedAlternativesAndStandardFilesAreNotPaths() {
        assertThat(terms("use endsWith/contains/startsWith checks")).isEmpty();
        assertThat(terms("a key in res/values/strings.xml or AndroidManifest.xml")).isEmpty();
        assertThat(terms("see src/main/java/Foo.java")).isNotEmpty();
    }

    @Test
    void fileNamePathAndLineReferencesAreCaught() {
        assertThat(sanitizer(OnViolation.REDACT).check("L1", "f", "see Foo.java line 12 in res/xml/paths.xml and L40"))
                .extracting(Violation::rule).contains("file_name", "path", "line_reference");
    }

    @Test
    void redactReplacesTermsWithTypedPlaceholders() {
        Lesson l = lesson("L1", "TransferActivity passes the extra to com.zentrabank.mobile's WebView", null, List.of());
        LessonSanitizer.Result r = sanitizer(OnViolation.REDACT).sanitize(List.of(l), Map.of());
        assertThat(r.lessons()).hasSize(1);
        assertThat(r.lessons().get(0).whyExploitable()).isEqualTo("<Activity> passes the extra to <package>'s WebView");
        assertThat(r.violations()).extracting(Violation::action).containsOnly("redacted");
    }

    @Test
    void dropPolicyDropsTheLesson() {
        Lesson l = lesson("L1", "TransferActivity is the problem", null, List.of());
        LessonSanitizer.Result r = sanitizer(OnViolation.DROP).sanitize(List.of(l), Map.of());
        assertThat(r.lessons()).isEmpty();
        assertThat(r.dropped()).hasSize(1);
    }

    @Test
    void answerKeyCheckRemovesAnExampleCopiedFromTheEvidence() {
        Finding src = new Finding("f1", "t", "c", "CWE-939", null, "high", 0.9, new Finding.Location("a", null, null, 1, 1),
                "webView.loadUrl(getIntent().getStringExtra(\"target\"));", "r", "code", "verified_exact", false,
                new Finding.Provenance("m", "teacher", "r", 0, List.of("u"), "v1"));
        Lesson copied = lesson("L1", "untrusted input reaches loadUrl", "webView.loadUrl(getIntent().getStringExtra(\"<extraKey>\"));", List.of("f1"));
        Lesson general = lesson("L2", "untrusted input reaches loadUrl",
                "String u = intent.getStringExtra(<key>); if (allowed(u)) { view.loadUrl(u); }", List.of("f1"));
        LessonSanitizer.Result r = sanitizer(OnViolation.REDACT).sanitize(List.of(copied, general), Map.of("f1", src));
        assertThat(r.lessons().get(0).abstractExample()).isNull();
        assertThat(r.lessons().get(1).abstractExample()).isNotNull();
        assertThat(r.violations()).anySatisfy(v -> assertThat(v.rule()).isEqualTo("answer_key_similarity"));
    }

    @Test
    void lessonsWithoutRequiredFieldsAreDropped() {
        Lesson empty = new Lesson("L9", "", null, null, null, null, null, null, null, null, null);
        assertThat(sanitizer(OnViolation.REDACT).sanitize(List.of(empty), Map.of()).dropped()).hasSize(1);
    }

    @Test
    void selectorCapsByCountAndTokensAndRendersInIdOrder() {
        Lesson a = lesson("L01", "a", null, List.of());
        Lesson b = lesson("L02", "b", null, List.of());
        Lesson c = lesson("L03", "c", null, List.of());
        Map<String, String> sev = Map.of("L01", "low", "L02", "high", "L03", "high");
        Map<String, Integer> support = Map.of("L01", 5, "L02", 1, "L03", 3);
        LessonSelector.Selection two = new LessonSelector(2, 10_000, new TokenEstimator(4, 0)).select(List.of(a, b, c), sev, support);
        assertThat(two.selected()).extracting(Lesson::id).containsExactly("L02", "L03"); // high first, then support; rendered by id
        assertThat(two.dropped()).extracting(Lesson::id).containsExactly("L01");
        assertThat(two.guidance().indexOf("L02")).isLessThan(two.guidance().indexOf("L03"));
        assertThat(two.guidance()).doesNotContain("source");
        int oneLesson = new TokenEstimator(4, 0).estimate(LessonSelector.render(c));
        LessonSelector.Selection tight = new LessonSelector(15, oneLesson, new TokenEstimator(4, 0)).select(List.of(a, b, c), sev, support);
        assertThat(tight.selected()).extracting(Lesson::id).containsExactly("L03");
    }

    @Test
    void redactionReplacesWholeLiteralsOnly() {
        String out = sanitizer(OnViolation.REDACT).redactText("compare with hunter22 but not hunter22x");
        assertThat(out).contains("<String>").contains("hunter22x");
    }
}
