package com.vulnscan.ingest;

import static com.vulnscan.ingest.SecureXml.androidAttr;
import static com.vulnscan.ingest.SecureXml.children;

import com.vulnscan.ingest.AppModel.AppFlags;
import com.vulnscan.ingest.AppModel.Component;
import com.vulnscan.ingest.AppModel.DataSpec;
import com.vulnscan.ingest.AppModel.IntentFilter;
import com.vulnscan.ingest.AppModel.Kind;
import com.vulnscan.ingest.AppModel.Manifest;
import com.vulnscan.ingest.AppModel.MetaData;
import com.vulnscan.ingest.AppModel.PermissionDecl;
import com.vulnscan.ingest.AppModel.ProviderExtras;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Parses a decoded AndroidManifest.xml into {@link Manifest}.
 * Exported rules (Android docs):
 * <ul>
 *   <li>activity / activity-alias / service / receiver: the attribute if present; if missing, exported iff the component
 *       has at least one intent filter. Missing attribute + intent filter + targetSdk &gt;= 31 is an anomaly
 *       (such an app cannot install on Android 12+).</li>
 *   <li>provider: the attribute if present; if missing, exported when targetSdk &lt;= 16. Anomaly when the attribute
 *       is missing and minSdk &lt;= 16 &lt; targetSdk (the effective value depends on the device).</li>
 * </ul>
 * Components without their own permission inherit {@code <application android:permission>}.
 */
public class ManifestParser {

    private static final Map<String, Kind> KINDS = Map.of(
            "activity", Kind.ACTIVITY, "activity-alias", Kind.ACTIVITY_ALIAS, "service", Kind.SERVICE,
            "receiver", Kind.RECEIVER, "provider", Kind.PROVIDER);

    public Manifest parse(Path manifestFile) {
        return parse(SecureXml.parse(manifestFile));
    }

    public Manifest parse(Document doc) {
        Element root = doc.getDocumentElement();
        String pkg = root.getAttribute("package");
        Integer minSdk = null;
        Integer targetSdk = null;
        for (Element usesSdk : children(root, "uses-sdk")) {
            minSdk = parseInt(androidAttr(usesSdk, "minSdkVersion"));
            targetSdk = parseInt(androidAttr(usesSdk, "targetSdkVersion"));
        }
        if (targetSdk == null) {
            targetSdk = minSdk; // platform rule: targetSdkVersion defaults to minSdkVersion
        }

        List<String> requested = new ArrayList<>();
        for (String tag : List.of("uses-permission", "uses-permission-sdk-23")) {
            children(root, tag).forEach(e -> requested.add(androidAttr(e, "name")));
        }
        List<PermissionDecl> declared = new ArrayList<>();
        for (Element p : children(root, "permission")) {
            String level = androidAttr(p, "protectionLevel");
            declared.add(new PermissionDecl(androidAttr(p, "name"), level == null ? "normal" : level));
        }

        Element app = children(root, "application").stream().findFirst().orElse(null);
        String appPermission = app == null ? null : androidAttr(app, "permission");
        AppFlags flags = app == null ? new AppFlags(null, null, null, null) : new AppFlags(
                parseBool(androidAttr(app, "debuggable")), parseBool(androidAttr(app, "allowBackup")),
                parseBool(androidAttr(app, "usesCleartextTraffic")), androidAttr(app, "networkSecurityConfig"));
        String appName = app == null ? null : qualify(pkg, androidAttr(app, "name"));

        List<Component> components = new ArrayList<>();
        if (app != null) {
            for (Element e : SecureXml.childElements(app)) {
                Kind kind = KINDS.get(SecureXml.localName(e));
                if (kind != null) {
                    components.add(component(kind, e, pkg, minSdk, targetSdk, appPermission));
                }
            }
        }
        components.sort(Comparator.comparing(Component::name).thenComparing(c -> c.kind().name()));
        requested.sort(Comparator.naturalOrder());
        declared.sort(Comparator.comparing(PermissionDecl::name));
        return new Manifest(pkg, minSdk, targetSdk, appName, appPermission, flags, requested, declared, components,
                app == null ? null : applicationOpenTag(app));
    }

    private Component component(Kind kind, Element e, String pkg, Integer minSdk, Integer targetSdk, String appPermission) {
        String name = qualify(pkg, androidAttr(e, "name"));
        String exportedRaw = androidAttr(e, "exported");
        String exportedAttr = exportedRaw == null ? "missing" : String.valueOf(Boolean.parseBoolean(exportedRaw));

        List<IntentFilter> filters = new ArrayList<>();
        for (Element f : children(e, "intent-filter")) {
            List<String> actions = children(f, "action").stream().map(a -> androidAttr(a, "name")).sorted().toList();
            List<String> categories = children(f, "category").stream().map(c -> androidAttr(c, "name")).sorted().toList();
            List<DataSpec> data = children(f, "data").stream().map(d -> new DataSpec(androidAttr(d, "scheme"),
                    androidAttr(d, "host"), firstNonNull(androidAttr(d, "path"), androidAttr(d, "pathPrefix"),
                    androidAttr(d, "pathPattern")), androidAttr(d, "mimeType"))).toList();
            filters.add(new IntentFilter(actions, categories, data));
        }
        boolean hasFilter = !filters.isEmpty();

        boolean effective;
        boolean anomaly;
        int target = targetSdk == null ? 1 : targetSdk;
        int min = minSdk == null ? 1 : minSdk;
        if (kind == Kind.PROVIDER) {
            effective = exportedRaw != null ? Boolean.parseBoolean(exportedRaw) : target <= 16;
            anomaly = exportedRaw == null && min <= 16 && target > 16;
        } else {
            effective = exportedRaw != null ? Boolean.parseBoolean(exportedRaw) : hasFilter;
            anomaly = exportedRaw == null && hasFilter && target >= 31;
        }

        String permission = androidAttr(e, "permission");
        if (permission == null) {
            permission = appPermission;
        }
        boolean deepLink = filters.stream().anyMatch(f -> f.actions().contains("android.intent.action.VIEW")
                && f.categories().contains("android.intent.category.BROWSABLE")
                && f.data().stream().anyMatch(d -> d.scheme() != null));

        ProviderExtras extras = kind != Kind.PROVIDER ? null : new ProviderExtras(androidAttr(e, "authorities"),
                parseBool(androidAttr(e, "grantUriPermissions")), androidAttr(e, "readPermission"),
                androidAttr(e, "writePermission"));
        List<MetaData> meta = children(e, "meta-data").stream().map(m -> new MetaData(androidAttr(m, "name"),
                androidAttr(m, "value"), androidAttr(m, "resource"))).toList();
        return new Component(kind, name, exportedAttr, effective, anomaly, permission, filters, deepLink, extras, meta,
                SecureXml.toXml(e));
    }

    /** ".Foo" and "Foo" (no dot) are relative to the manifest package; anything else is fully qualified. */
    static String qualify(String pkg, String name) {
        if (name == null) {
            return null;
        }
        if (name.startsWith(".")) {
            return pkg + name;
        }
        if (!name.contains(".")) {
            return pkg + "." + name;
        }
        return name;
    }

    /** The &lt;application ...&gt; start tag only (its flags), without children. */
    private static String applicationOpenTag(Element app) {
        Element copy = (Element) app.cloneNode(false);
        String xml = SecureXml.toXml(copy);
        return xml.endsWith("/>") ? xml.substring(0, xml.length() - 2).strip() + ">" : xml;
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return null; // codename such as "Q": leave unknown
        }
    }

    private static Boolean parseBool(String s) {
        return s == null ? null : Boolean.parseBoolean(s.strip());
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
