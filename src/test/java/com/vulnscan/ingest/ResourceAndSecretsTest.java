package com.vulnscan.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceAndSecretsTest {

    @TempDir
    Path dir;

    final SecretPatterns secrets = new SecretPatterns(List.of("MYCO-[0-9]{6}"));
    final ResourceExtractor extractor = new ResourceExtractor(List.of("key", "password", "pool"), secrets, 3.5, 12);

    @Test
    void secretFormatsAreRecognised() {
        assertThat(secrets.matches("AKIAABCDEFGHIJKLMNOP")).containsExactly("aws_access_key_id");
        assertThat(secrets.matches("us-east-1:12345678-abcd-abcd-abcd-1234567890ab")).containsExactly("aws_cognito_identity_pool_id");
        assertThat(secrets.matches("AIzaSyA1234567890abcdefghijklmnopqrstuv")).contains("google_api_key");
        assertThat(secrets.matches("https://myproj-123.firebaseio.com")).containsExactly("firebase_database_url");
        assertThat(secrets.matches("-----BEGIN RSA PRIVATE KEY-----")).containsExactly("private_key_header");
        assertThat(secrets.matches("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop")).contains("jwt");
        assertThat(secrets.matches("MYCO-123456")).containsExactly("extra_1");
        assertThat(secrets.matches("hello world")).isEmpty();
    }

    @Test
    void stringsFilterRecordsReasons() {
        assertThat(extractor.keepReason("api_key", "x")).isEqualTo("name_pattern");
        assertThat(extractor.keepReason("region_id", "us-east-1:12345678-abcd-abcd-abcd-1234567890ab")).isEqualTo("secret_regex");
        assertThat(extractor.keepReason("thing", "q8Zr2Lx9Vb4Nw7Kt")).isEqualTo("entropy");
        assertThat(extractor.keepReason("title", "Welcome to the shop today")).isNull(); // UI text with spaces
        assertThat(extractor.keepReason("app_name", "Shop")).isNull();
    }

    @Test
    void namePatternsMatchWholeTokensOnly() {
        ResourceExtractor e = new ResourceExtractor(List.of("api", "client_id", "pool"), secrets, 3.5, 12);
        assertThat(e.keepReason("api_key", "x")).isEqualTo("name_pattern");
        assertThat(e.keepReason("myApiKey", "x")).isEqualTo("name_pattern");
        assertThat(e.keepReason("aws_Identity_pool_ID", "x")).isEqualTo("name_pattern");
        assertThat(e.keepReason("oauthClientId", "x")).isEqualTo("name_pattern");
        assertThat(e.keepReason("abc_capital_off", "OFF")).isNull();
    }

    @Test
    void entropyTestSkipsReferencesAndClassNames() {
        assertThat(extractor.keepReason("behavior", "com.google.android.material.appbar.AppBarLayout$ScrollingViewBehavior")).isNull();
        assertThat(extractor.keepReason("picker_ok", "@android:string/cancel")).isNull();
    }

    @Test
    void fileProviderPathsFlagBroadEntries() throws IOException {
        Path xml = dir.resolve("res/xml/paths.xml");
        Files.createDirectories(xml.getParent());
        Files.writeString(xml, """
                <paths><root-path name="r" path="/"/><external-path name="e" path="."/>
                <files-path name="f" path="images/"/></paths>""");
        var paths = ResourceExtractor.fileProviderPaths("p.Provider", xml, dir);
        assertThat(paths).extracting(AppModel.FileProviderPath::broad).containsExactly(true, true, false);
        assertThat(paths.get(0).sourceFile()).isEqualTo("res/xml/paths.xml");
    }

    @Test
    void networkSecurityConfigSummary() throws IOException {
        Path xml = dir.resolve("nsc.xml");
        Files.writeString(xml, """
                <network-security-config>
                  <base-config cleartextTrafficPermitted="true"><trust-anchors><certificates src="user"/></trust-anchors></base-config>
                  <domain-config><domain>api.example.com</domain><pin-set><pin digest="SHA-256">abc</pin></pin-set></domain-config>
                </network-security-config>""");
        String summary = ResourceExtractor.summariseNetworkConfig(xml).summary();
        assertThat(summary).contains("cleartextTrafficPermitted=true", "trust=user", "[api.example.com]", "pin-set");
    }
}
