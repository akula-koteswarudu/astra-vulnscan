package com.vulnscan.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Decides which classes are the app's own code (allow-list derived from the manifest, plan 6.3):
 * the full manifest package, plus the package of each manifest-declared class (components, application) that is
 * not a known library AND shares its first two segments with the manifest package, plus configured extras.
 * Merged manifests contain library components (e.g. androidx.startup, an upload-service library); the
 * two-segment rule keeps those namespaces out. Build-generated classes (R, BuildConfig, data binding) are excluded.
 */
public class FirstPartyClassifier {

    private final List<String> prefixes;
    private final List<String> excludeGlobs;
    private final List<Pattern> excludes;

    public FirstPartyClassifier(String manifestPackage, List<String> declaredClassNames, List<String> libraryPrefixes,
                                List<String> extraPrefixes, List<String> excludeClassPatterns) {
        TreeSet<String> set = new TreeSet<>();
        set.add(manifestPackage);
        String[] pkgSegments = manifestPackage.split("\\.");
        for (String cls : declaredClassNames) {
            if (cls == null || !cls.contains(".")) {
                continue;
            }
            String pkg = cls.substring(0, cls.lastIndexOf('.'));
            boolean library = libraryPrefixes.stream().anyMatch(lib -> (pkg + ".").startsWith(lib));
            if (!library && sharesFirstTwoSegments(pkg.split("\\."), pkgSegments)) {
                set.add(pkg);
            }
        }
        set.addAll(extraPrefixes);
        this.prefixes = new ArrayList<>(removeNested(set));
        this.excludeGlobs = List.copyOf(excludeClassPatterns);
        this.excludes = excludeClassPatterns.stream().map(FirstPartyClassifier::globToRegex).toList();
    }

    public List<String> prefixes() {
        return prefixes;
    }

    /** True when the class is in a first-party package. Generated classes still count here; see {@link #isGenerated}. */
    public boolean inFirstPartyPackage(String fqcn) {
        return prefixes.stream().anyMatch(p -> fqcn.equals(p) || fqcn.startsWith(p + "."));
    }

    /**
     * Patterns without a dot match the simple class name (e.g. "R", "R$*", "BuildConfig");
     * patterns with a dot match the fully qualified name (e.g. "*.databinding.*").
     */
    public boolean isGenerated(String fqcn) {
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        for (int i = 0; i < excludes.size(); i++) {
            Pattern p = excludes.get(i);
            boolean qualified = excludeGlobs.get(i).contains(".");
            if (p.matcher(qualified ? fqcn : simple).matches()) {
                return true;
            }
        }
        return false;
    }

    public boolean isFirstParty(String fqcn) {
        return inFirstPartyPackage(fqcn) && !isGenerated(fqcn);
    }

    private static boolean sharesFirstTwoSegments(String[] a, String[] b) {
        return a.length >= 2 && b.length >= 2 && a[0].equals(b[0]) && a[1].equals(b[1]);
    }

    /** "com.a" and "com.a.b" -> keep only "com.a" (the longer one is already covered). */
    private static List<String> removeNested(TreeSet<String> set) {
        List<String> out = new ArrayList<>();
        for (String p : set) {
            if (out.stream().noneMatch(q -> p.startsWith(q + "."))) {
                out.add(p);
            }
        }
        return out;
    }

    static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString());
    }
}
