package com.vulnscan.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Everything downstream code knows about the app, independent of the decompiler's folder layout.
 * Saved as 02_ingest/app_model.json. File text is not stored here; it is read on demand through
 * {@link CodeBase} using the relative paths below. All lists are sorted so the file is deterministic.
 */
public record AppModel(
        int schemaVersion,
        String appId,
        @JsonProperty("package") String packageName,
        Manifest manifest,
        List<SourceFile> sources,
        Resources resources,
        List<Dependency> dependencies,
        List<String> identifiers,
        List<String> stringLiterals,
        List<String> platformVocabulary,
        List<String> frameworkCallbacks,
        Stats stats) {

    public static final int SCHEMA_VERSION = 1;

    public List<SourceFile> firstPartySources() {
        return sources.stream().filter(SourceFile::isFirstParty).toList();
    }

    public record Manifest(@JsonProperty("package") String packageName, Integer minSdk, Integer targetSdk,
                           String applicationName, String applicationPermission, AppFlags appFlags,
                           List<String> permissionsRequested, List<PermissionDecl> permissionsDeclared,
                           List<Component> components, String applicationXml) {
    }

    /** Booleans are null when the attribute is absent (platform default applies). */
    public record AppFlags(Boolean debuggable, Boolean allowBackup, Boolean usesCleartextTraffic,
                           String networkSecurityConfigRef) {
    }

    public record PermissionDecl(String name, String protectionLevel) {
    }

    public enum Kind {
        @JsonProperty("activity") ACTIVITY,
        @JsonProperty("activity-alias") ACTIVITY_ALIAS,
        @JsonProperty("service") SERVICE,
        @JsonProperty("receiver") RECEIVER,
        @JsonProperty("provider") PROVIDER
    }

    /**
     * exportedAttr: "true" | "false" | "missing". effectiveExported applies the platform default rules.
     * rawXml is the component's element as written in the manifest (used for manifest slices and evidence checks).
     */
    public record Component(Kind kind, String name, String exportedAttr, boolean effectiveExported,
                            boolean exportedAnomaly, String permission, List<IntentFilter> intentFilters,
                            boolean isDeepLink, ProviderExtras providerExtras, List<MetaData> metaData,
                            String rawXml) {
    }

    public record IntentFilter(List<String> actions, List<String> categories, List<DataSpec> data) {
    }

    public record DataSpec(String scheme, String host, String path, String mimeType) {
    }

    public record ProviderExtras(String authorities, Boolean grantUriPermissions, String readPermission,
                                 String writePermission) {
    }

    public record MetaData(String name, String value, String resource) {
    }

    /** path is relative to the decompiled root with '/' separators; component = manifest component in this file. */
    public record SourceFile(String path, String className, String language, int lineCount, String sha256,
                             boolean isFirstParty, String component) {
    }

    public record Resources(List<StringEntry> strings, NetworkSecurityConfig networkSecurityConfig,
                            List<FileProviderPath> fileProviderPaths, List<String> otherRefs) {
    }

    public record StringEntry(String name, String value, String reason) {
    }

    public record NetworkSecurityConfig(String summary, String raw) {
    }

    /** broad = root-path, or external-path covering the whole external storage. */
    public record FileProviderPath(String provider, String pathType, String name, String path, boolean broad,
                                   String sourceFile) {
    }

    /** source: meta_inf_version | library_build_config | package_prefix. */
    public record Dependency(String name, String version, String source) {
    }

    public record Stats(int filesTotal, int filesFirstParty, int filesExcluded, int filesGeneratedExcluded,
                        List<String> firstPartyPrefixes, List<PackageCount> topIncludedPackages,
                        List<PackageCount> topExcludedPackages, int methodsFailed, double shortNameRatio,
                        String ingestHash) {

        Stats withIngestHash(String hash) {
            return new Stats(filesTotal, filesFirstParty, filesExcluded, filesGeneratedExcluded, firstPartyPrefixes,
                    topIncludedPackages, topExcludedPackages, methodsFailed, shortNameRatio, hash);
        }
    }

    public record PackageCount(String packageName, int files) {
    }

    AppModel withStats(Stats newStats) {
        return new AppModel(schemaVersion, appId, packageName, manifest, sources, resources, dependencies, identifiers,
                stringLiterals, platformVocabulary, frameworkCallbacks, newStats);
    }
}
