package com.vulnscan.ingest;

import static com.vulnscan.ingest.SecureXml.androidAttr;

import com.vulnscan.ingest.AppModel.Component;
import com.vulnscan.ingest.AppModel.FileProviderPath;
import com.vulnscan.ingest.AppModel.Manifest;
import com.vulnscan.ingest.AppModel.MetaData;
import com.vulnscan.ingest.AppModel.NetworkSecurityConfig;
import com.vulnscan.ingest.AppModel.Resources;
import com.vulnscan.ingest.AppModel.StringEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Small extractors over the decoded resources (plan 6.3, Resources):
 * filtered strings.xml entries, network security config summary, FileProvider paths, resolved meta-data refs.
 */
public class ResourceExtractor {

    private final List<String> namePatterns;
    private final SecretPatterns secrets;
    private final double minEntropy;
    private final int minLength;

    public ResourceExtractor(List<String> namePatterns, SecretPatterns secrets, double minEntropy, int minLength) {
        this.namePatterns = List.copyOf(namePatterns);
        this.secrets = secrets;
        this.minEntropy = minEntropy;
        this.minLength = minLength;
    }

    public Resources extract(Path resourcesRoot, Path layoutRoot, Manifest manifest) {
        Map<String, String> allStrings = readStrings(resourcesRoot.resolve("res/values/strings.xml"));
        List<StringEntry> kept = new ArrayList<>();
        allStrings.forEach((name, value) -> {
            String reason = keepReason(name, value);
            if (reason != null) {
                kept.add(new StringEntry(name, value, reason));
            }
        });
        kept.sort(Comparator.comparing(StringEntry::name));

        NetworkSecurityConfig nsc = null;
        String nscRef = manifest.appFlags().networkSecurityConfigRef();
        Path nscFile = xmlRef(resourcesRoot, nscRef);
        if (nscFile != null && Files.isRegularFile(nscFile)) {
            nsc = summariseNetworkConfig(nscFile);
        }

        List<FileProviderPath> paths = new ArrayList<>();
        List<String> otherRefs = new ArrayList<>();
        for (Component c : manifest.components()) {
            for (MetaData m : c.metaData()) {
                if (m.resource() != null && m.resource().startsWith("@xml/")
                        && m.name() != null && m.name().endsWith("FILE_PROVIDER_PATHS")) {
                    Path f = xmlRef(resourcesRoot, m.resource());
                    if (f != null && Files.isRegularFile(f)) {
                        paths.addAll(fileProviderPaths(c.name(), f, layoutRoot));
                    }
                } else if (m.value() != null && m.value().startsWith("@string/")) {
                    String key = m.value().substring("@string/".length());
                    otherRefs.add(c.name() + " meta-data " + m.name() + " = " + allStrings.getOrDefault(key, "(unresolved " + m.value() + ")"));
                } else if (m.resource() != null) {
                    otherRefs.add(c.name() + " meta-data " + m.name() + " -> " + m.resource());
                }
            }
        }
        paths.sort(Comparator.comparing(FileProviderPath::provider).thenComparing(FileProviderPath::pathType)
                .thenComparing(p -> String.valueOf(p.path())));
        otherRefs.sort(Comparator.naturalOrder());
        return new Resources(kept, nsc, paths, otherRefs);
    }

    /**
     * Why a string entry is kept (null = dropped).
     * Name patterns match whole name tokens ("api" matches api_key and apiKey, not "capital").
     * The entropy test skips values that cannot be secrets: text with spaces (UI copy), resource references
     * ("@android:string/ok") and dotted class names.
     */
    String keepReason(String name, String value) {
        List<String> tokens = tokens(name);
        if (namePatterns.stream().anyMatch(p -> containsRun(tokens, tokens(p)))) {
            return "name_pattern";
        }
        if (!secrets.matches(value).isEmpty()) {
            return "secret_regex";
        }
        boolean notSecretShaped = value.contains(" ") || value.startsWith("@") || DOTTED_NAME.matcher(value).matches();
        if (value.length() >= minLength && !notSecretShaped && SecretPatterns.entropy(value) >= minEntropy) {
            return "entropy";
        }
        return null;
    }

    private static final java.util.regex.Pattern DOTTED_NAME =
            java.util.regex.Pattern.compile("[A-Za-z_][\\w$]*(\\.[A-Za-z_][\\w$]*)+");

    /** Splits on _ . - and camelCase boundaries, lower-cased: "aws_IdentityPoolID" -> [aws, identity, pool, id]. */
    static List<String> tokens(String name) {
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        return java.util.Arrays.stream(spaced.split("[\\s_.\\-]+"))
                .filter(t -> !t.isEmpty()).map(t -> t.toLowerCase(Locale.ROOT)).toList();
    }

    /** True when needle occurs as a contiguous run inside haystack. */
    static boolean containsRun(List<String> haystack, List<String> needle) {
        if (needle.isEmpty()) {
            return false;
        }
        for (int i = 0; i + needle.size() <= haystack.size(); i++) {
            if (haystack.subList(i, i + needle.size()).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    static Map<String, String> readStrings(Path stringsXml) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(stringsXml)) {
            return out;
        }
        Document doc = SecureXml.parse(stringsXml);
        for (Element e : SecureXml.children(doc.getDocumentElement(), "string")) {
            out.put(e.getAttribute("name"), e.getTextContent());
        }
        return out;
    }

    private static Path xmlRef(Path resourcesRoot, String ref) {
        if (ref == null || !ref.startsWith("@xml/")) {
            return null;
        }
        return resourcesRoot.resolve("res/xml/" + ref.substring("@xml/".length()) + ".xml");
    }

    static List<FileProviderPath> fileProviderPaths(String provider, Path file, Path layoutRoot) {
        List<FileProviderPath> out = new ArrayList<>();
        Document doc = SecureXml.parse(file);
        String rel = layoutRoot.relativize(file).toString().replace('\\', '/');
        for (Element e : SecureXml.childElements(doc.getDocumentElement())) {
            String type = SecureXml.localName(e);
            String path = e.getAttribute("path");
            String name = e.getAttribute("name");
            boolean wholeTree = path.isEmpty() || path.equals("/") || path.equals(".");
            boolean broad = type.equals("root-path") || (type.equals("external-path") && wholeTree);
            out.add(new FileProviderPath(provider, type, name, path, broad, rel));
        }
        return out;
    }

    static NetworkSecurityConfig summariseNetworkConfig(Path file) {
        Document doc = SecureXml.parse(file);
        Element root = doc.getDocumentElement();
        List<String> notes = new ArrayList<>();
        for (Element section : SecureXml.childElements(root)) {
            String tag = SecureXml.localName(section);
            String cleartext = section.getAttribute("cleartextTrafficPermitted");
            String domains = SecureXml.children(section, "domain").stream().map(Element::getTextContent)
                    .reduce((a, b) -> a + "," + b).orElse("");
            StringBuilder s = new StringBuilder(tag);
            if (!domains.isEmpty()) {
                s.append(" [").append(domains).append(']');
            }
            if (!cleartext.isEmpty()) {
                s.append(" cleartextTrafficPermitted=").append(cleartext);
            }
            for (Element anchors : SecureXml.children(section, "trust-anchors")) {
                for (Element cert : SecureXml.children(anchors, "certificates")) {
                    s.append(" trust=").append(cert.getAttribute("src"));
                }
            }
            if (!SecureXml.children(section, "pin-set").isEmpty()) {
                s.append(" pin-set");
            }
            notes.add(s.toString());
        }
        return new NetworkSecurityConfig(String.join("; ", notes), com.vulnscan.common.Files2.read(file));
    }

    /** Exposed for tests: attribute helper re-export. */
    static String attr(Element e, String name) {
        return androidAttr(e, name);
    }
}
