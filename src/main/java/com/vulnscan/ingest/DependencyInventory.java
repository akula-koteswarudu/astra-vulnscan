package com.vulnscan.ingest;

import com.vulnscan.common.Files2;
import com.vulnscan.ingest.AppModel.Dependency;
import com.vulnscan.ingest.AppModel.SourceFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Third-party components (plan 6.3, Dependency inventory). Library code is never scanned; the inventory is context
 * so the model can flag known-vulnerable components (MASVS-CODE). Sources, in order of preference:
 * META-INF/*.version files (name + version); a library's own generated BuildConfig (VERSION_NAME); else the
 * package prefix with version unknown.
 */
public class DependencyInventory {

    static final Set<String> PLATFORM = Set.of("android", "androidx", "java", "javax", "kotlin", "kotlinx", "dalvik");
    private static final Pattern VERSION_NAME = Pattern.compile("VERSION_NAME\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern LIBRARY_PACKAGE = Pattern.compile("(?:LIBRARY_PACKAGE_NAME|APPLICATION_ID)\\s*=\\s*\"([^\"]+)\"");

    public List<Dependency> collect(Path resourcesRoot, List<SourceFile> sources, CodeBase code, List<String> firstPartyPrefixes) {
        // Library = not first-party AND not in a first-party package (the app's own generated R/BuildConfig are excluded from
        // scanning but are not dependencies).
        sources = sources.stream().filter(s -> !s.isFirstParty()
                && firstPartyPrefixes.stream().noneMatch(p -> s.className().startsWith(p + "."))).toList();
        TreeMap<String, Dependency> byName = new TreeMap<>();
        Path metaInf = resourcesRoot.resolve("META-INF");
        if (Files.isDirectory(metaInf)) {
            try (Stream<Path> list = Files.list(metaInf)) {
                for (Path f : (Iterable<Path>) list.filter(p -> p.toString().endsWith(".version")).sorted()::iterator) {
                    String file = f.getFileName().toString();
                    String name = file.substring(0, file.length() - ".version".length()).replaceFirst("_", ":");
                    byName.put(name, new Dependency(name, Files2.read(f).strip(), "meta_inf_version"));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        // Library BuildConfig classes among excluded (non-first-party) code.
        List<String> coveredPackages = new ArrayList<>();
        for (SourceFile s : sources) {
            if (s.isFirstParty() || !s.className().endsWith(".BuildConfig") || isPlatform(s.className())) {
                continue;
            }
            String text = code.text(s.path());
            String pkg = s.className().substring(0, s.className().lastIndexOf('.'));
            Matcher lib = LIBRARY_PACKAGE.matcher(text);
            String name = lib.find() ? lib.group(1) : pkg;
            Matcher v = VERSION_NAME.matcher(text);
            byName.put(name, new Dependency(name, v.find() ? v.group(1) : null, "library_build_config"));
            coveredPackages.add(pkg);
        }

        // Remaining excluded non-platform packages: prefix (first three segments), version unknown.
        TreeMap<String, Integer> prefixes = new TreeMap<>();
        for (SourceFile s : sources) {
            if (s.isFirstParty() || isPlatform(s.className()) || s.className().indexOf('.') < 0) {
                continue;
            }
            String prefix = prefix(s.className(), 3);
            boolean covered = coveredPackages.stream().anyMatch(p -> prefix.equals(p) || prefix.startsWith(p + ".") || p.startsWith(prefix + "."));
            if (!covered) {
                prefixes.merge(prefix, 1, Integer::sum);
            }
        }
        prefixes.keySet().forEach(p -> byName.putIfAbsent(p, new Dependency(p, null, "package_prefix")));
        return byName.values().stream().sorted(Comparator.comparing(Dependency::name)).toList();
    }

    static boolean isPlatform(String fqcn) {
        return PLATFORM.contains(fqcn.split("\\.")[0]);
    }

    /** Package prefix of at most n segments (never including the class name). */
    static String prefix(String fqcn, int n) {
        String pkg = fqcn.contains(".") ? fqcn.substring(0, fqcn.lastIndexOf('.')) : fqcn;
        String[] parts = pkg.split("\\.");
        return String.join(".", java.util.Arrays.copyOf(parts, Math.min(n, parts.length)));
    }
}
