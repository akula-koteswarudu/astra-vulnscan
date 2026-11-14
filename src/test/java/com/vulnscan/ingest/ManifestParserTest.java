package com.vulnscan.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vulnscan.ingest.AppModel.Component;
import com.vulnscan.ingest.AppModel.Manifest;
import org.junit.jupiter.api.Test;

class ManifestParserTest {

    private final ManifestParser parser = new ManifestParser();

    private static String manifest(int minSdk, int targetSdk, String appAttrs, String body) {
        return """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                  <uses-sdk android:minSdkVersion="%d" android:targetSdkVersion="%d"/>
                  <uses-permission android:name="android.permission.INTERNET"/>
                  <permission android:name="com.example.app.READ" android:protectionLevel="signature"/>
                  <application android:name=".App" %s>%s</application>
                </manifest>
                """.formatted(minSdk, targetSdk, appAttrs, body);
    }

    private Manifest parse(String xml) {
        return parser.parse(SecureXml.parse(xml));
    }

    private static Component get(Manifest m, String name) {
        return m.components().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private static final String COMPONENTS = """
            <activity android:name=".WithFilter"><intent-filter><action android:name="android.intent.action.SEND"/></intent-filter></activity>
            <activity android:name=".NoFilter"/>
            <service android:name="com.example.app.svc.Sync" android:exported="false"><intent-filter><action android:name="x"/></intent-filter></service>
            <receiver android:name="Recv" android:exported="true"/>
            """;

    @Test
    void missingExportedFollowsIntentFilterRuleAtTarget30() {
        Manifest m = parse(manifest(21, 30, "", COMPONENTS));
        assertThat(get(m, "com.example.app.WithFilter").effectiveExported()).isTrue();
        assertThat(get(m, "com.example.app.WithFilter").exportedAttr()).isEqualTo("missing");
        assertThat(get(m, "com.example.app.WithFilter").exportedAnomaly()).isFalse();
        assertThat(get(m, "com.example.app.NoFilter").effectiveExported()).isFalse();
        assertThat(get(m, "com.example.app.svc.Sync").effectiveExported()).isFalse();
        assertThat(get(m, "com.example.app.Recv").effectiveExported()).isTrue();
    }

    @Test
    void missingExportedWithFilterAtTarget31IsAnomaly() {
        Manifest m = parse(manifest(21, 31, "", COMPONENTS));
        assertThat(get(m, "com.example.app.WithFilter").effectiveExported()).isTrue();
        assertThat(get(m, "com.example.app.WithFilter").exportedAnomaly()).isTrue();
        assertThat(get(m, "com.example.app.NoFilter").exportedAnomaly()).isFalse();
    }

    @Test
    void providerDefaults() {
        String body = "<provider android:name=\".P\" android:authorities=\"a\"/>";
        assertThat(get(parse(manifest(14, 16, "", body)), "com.example.app.P").effectiveExported()).isTrue();
        Component modern = get(parse(manifest(14, 29, "", body)), "com.example.app.P");
        assertThat(modern.effectiveExported()).isFalse();
        assertThat(modern.exportedAnomaly()).isTrue(); // min <= 16 < target: depends on the device
        assertThat(get(parse(manifest(21, 29, "", body)), "com.example.app.P").exportedAnomaly()).isFalse();
        assertThat(modern.providerExtras().authorities()).isEqualTo("a");
    }

    @Test
    void relativeNamesAreQualified() {
        Manifest m = parse(manifest(21, 30, "", COMPONENTS));
        assertThat(m.applicationName()).isEqualTo("com.example.app.App");
        assertThat(m.components()).extracting(Component::name).contains("com.example.app.Recv", "com.example.app.svc.Sync");
    }

    @Test
    void deepLinkNeedsViewBrowsableAndScheme() {
        String body = """
                <activity android:name=".Deep"><intent-filter>
                  <action android:name="android.intent.action.VIEW"/>
                  <category android:name="android.intent.category.DEFAULT"/>
                  <category android:name="android.intent.category.BROWSABLE"/>
                  <data android:scheme="myapp" android:host="open"/></intent-filter></activity>
                <activity android:name=".NotDeep"><intent-filter>
                  <action android:name="android.intent.action.VIEW"/>
                  <category android:name="android.intent.category.BROWSABLE"/></intent-filter></activity>
                """;
        Manifest m = parse(manifest(21, 30, "", body));
        assertThat(get(m, "com.example.app.Deep").isDeepLink()).isTrue();
        assertThat(get(m, "com.example.app.Deep").intentFilters().get(0).data().get(0).host()).isEqualTo("open");
        assertThat(get(m, "com.example.app.NotDeep").isDeepLink()).isFalse();
    }

    @Test
    void componentsInheritApplicationPermission() {
        String body = "<activity android:name=\".A\" android:exported=\"true\"/>"
                + "<activity android:name=\".B\" android:exported=\"true\" android:permission=\"own\"/>";
        Manifest m = parse(manifest(21, 30, "android:permission=\"app.perm\"", body));
        assertThat(get(m, "com.example.app.A").permission()).isEqualTo("app.perm");
        assertThat(get(m, "com.example.app.B").permission()).isEqualTo("own");
    }

    @Test
    void attributesAreFoundByNamespaceUriNotPrefix() {
        String xml = """
                <manifest xmlns:a="http://schemas.android.com/apk/res/android" package="com.x">
                  <uses-sdk a:minSdkVersion="21" a:targetSdkVersion="33"/>
                  <application a:debuggable="true" a:allowBackup="false">
                    <activity a:name=".Main" a:exported="true"/>
                  </application>
                </manifest>
                """;
        Manifest m = parse(xml);
        assertThat(m.targetSdk()).isEqualTo(33);
        assertThat(m.appFlags().debuggable()).isTrue();
        assertThat(m.appFlags().allowBackup()).isFalse();
        assertThat(get(m, "com.x.Main").effectiveExported()).isTrue();
    }

    @Test
    void appFlagsPermissionsAndRawXml() {
        Manifest m = parse(manifest(21, 30, "android:usesCleartextTraffic=\"true\"", COMPONENTS));
        assertThat(m.appFlags().usesCleartextTraffic()).isTrue();
        assertThat(m.appFlags().debuggable()).isNull();
        assertThat(m.permissionsRequested()).containsExactly("android.permission.INTERNET");
        assertThat(m.permissionsDeclared().get(0).protectionLevel()).isEqualTo("signature");
        assertThat(get(m, "com.example.app.Recv").rawXml()).contains("android:exported=\"true\"");
        assertThat(m.applicationXml()).contains("usesCleartextTraffic").doesNotContain("<activity");
    }

    @Test
    void xxePayloadIsRejected() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE manifest [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <manifest package="&xxe;"/>
                """;
        assertThatThrownBy(() -> SecureXml.parse(xxe)).hasMessageContaining("DOCTYPE");
    }
}
