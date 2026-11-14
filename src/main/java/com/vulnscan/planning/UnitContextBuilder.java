package com.vulnscan.planning;

import com.vulnscan.ingest.AppModel;
import com.vulnscan.ingest.AppModel.Component;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/** Renders the context text shown with code: manifest slice, filtered resources, dependency inventory. */
public class UnitContextBuilder {

    /** App flags + declared permissions + the given components' manifest elements. */
    public String manifestSlice(AppModel app, Collection<String> componentNames) {
        AppModel.Manifest m = app.manifest();
        List<String> lines = new ArrayList<>();
        for (AppModel.PermissionDecl p : m.permissionsDeclared()) {
            lines.add("<permission android:name=\"" + p.name() + "\" android:protectionLevel=\"" + p.protectionLevel() + "\"/>");
        }
        if (m.applicationXml() != null) {
            lines.add(m.applicationXml());
        }
        for (Component c : m.components()) {
            if (componentNames.contains(c.name())) {
                lines.add("  " + c.rawXml() + (c.exportedAttr().equals("missing")
                        ? "  <!-- exported attribute missing; effective exported=" + c.effectiveExported() + " -->" : ""));
            }
        }
        lines.add("</application>");
        return String.join("\n", lines);
    }

    public String fullManifestSlice(AppModel app) {
        String perms = app.manifest().permissionsRequested().stream()
                .map(p -> "<uses-permission android:name=\"" + p + "\"/>").collect(Collectors.joining("\n"));
        return (perms.isEmpty() ? "" : perms + "\n")
                + manifestSlice(app, app.manifest().components().stream().map(Component::name).toList());
    }

    /** Kept strings, network security config summary and FileProvider paths (null when there is nothing). */
    public String resources(AppModel app) {
        AppModel.Resources r = app.resources();
        List<String> lines = new ArrayList<>();
        for (AppModel.StringEntry s : r.strings()) {
            lines.add("res/values/strings.xml: <string name=\"" + s.name() + "\">" + s.value() + "</string>");
        }
        if (r.networkSecurityConfig() != null) {
            lines.add("network_security_config: " + r.networkSecurityConfig().summary());
        }
        for (AppModel.FileProviderPath p : r.fileProviderPaths()) {
            lines.add(p.sourceFile() + " (FileProvider " + p.provider() + "): <" + p.pathType() + " name=\"" + p.name()
                    + "\" path=\"" + p.path() + "\"/>");
        }
        r.otherRefs().forEach(lines::add);
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    /** One "name version" line per third-party library (null when the inventory is empty). */
    public String dependencies(AppModel app) {
        if (app.dependencies().isEmpty()) {
            return null;
        }
        return app.dependencies().stream()
                .map(d -> d.name() + " " + (d.version() == null ? "(version unknown)" : d.version()))
                .collect(Collectors.joining("\n"));
    }
}
