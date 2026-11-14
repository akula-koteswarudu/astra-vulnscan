package com.vulnscan.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FirstPartyClassifierTest {

    static final List<String> LIBS = List.of("androidx.", "android.", "com.google.android.", "com.google.firebase.");
    static final List<String> EXCLUDES = List.of("R", "R$*", "BuildConfig", "BR", "DataBinderMapperImpl", "*.databinding.*");

    @Test
    void libraryComponentsInManifestDoNotPullInLibraryNamespaces() {
        FirstPartyClassifier c = new FirstPartyClassifier("com.shop.app",
                List.of("com.shop.app.Main", "androidx.startup.InitializationProvider", "net.upload.service.UploadService",
                        "com.shop.other.Helper", "com.google.firebase.provider.FirebaseInitProvider"),
                LIBS, List.of(), EXCLUDES);
        assertThat(c.prefixes()).containsExactly("com.shop.app", "com.shop.other");
        assertThat(c.isFirstParty("com.shop.app.ui.Screen")).isTrue();
        assertThat(c.isFirstParty("com.shop.other.Helper")).isTrue();
        assertThat(c.isFirstParty("net.upload.service.UploadService")).isFalse();
        assertThat(c.isFirstParty("androidx.startup.InitializationProvider")).isFalse();
        assertThat(c.isFirstParty("com.shopping.Thing")).isFalse(); // prefix match is per package segment
    }

    @Test
    void googleNamespacedAppIsStillFirstParty() {
        FirstPartyClassifier c = new FirstPartyClassifier("com.google.android.apps.notes",
                List.of("com.google.android.apps.notes.Main"), LIBS, List.of(), EXCLUDES);
        assertThat(c.isFirstParty("com.google.android.apps.notes.Main")).isTrue();
        assertThat(c.isFirstParty("com.google.android.gms.Thing")).isFalse();
    }

    @Test
    void generatedClassesAreExcluded() {
        FirstPartyClassifier c = new FirstPartyClassifier("com.shop.app", List.of(), LIBS, List.of(), EXCLUDES);
        assertThat(c.isFirstParty("com.shop.app.R")).isFalse();
        assertThat(c.isFirstParty("com.shop.app.R$id")).isFalse();
        assertThat(c.isFirstParty("com.shop.app.BuildConfig")).isFalse();
        assertThat(c.isFirstParty("com.shop.app.databinding.MainBinding")).isFalse();
        assertThat(c.isFirstParty("com.shop.app.Receiver")).isTrue(); // starts with R but is not R
        assertThat(c.inFirstPartyPackage("com.shop.app.R")).isTrue();
    }

    @Test
    void extraPrefixesAreAdded() {
        FirstPartyClassifier c = new FirstPartyClassifier("com.shop.app", List.of(), LIBS, List.of("org.shopcore"), EXCLUDES);
        assertThat(c.isFirstParty("org.shopcore.Net")).isTrue();
    }
}
