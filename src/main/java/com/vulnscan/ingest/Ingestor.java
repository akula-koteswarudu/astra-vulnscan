package com.vulnscan.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.vulnscan.common.Hashing;
import com.vulnscan.common.Json;
import com.vulnscan.config.AppConfig;
import com.vulnscan.ingest.AppModel.Component;
import com.vulnscan.ingest.AppModel.Dependency;
import com.vulnscan.ingest.AppModel.Manifest;
import com.vulnscan.ingest.AppModel.PackageCount;
import com.vulnscan.ingest.AppModel.Resources;
import com.vulnscan.ingest.AppModel.SourceFile;
import com.vulnscan.ingest.AppModel.Stats;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Builds the {@link AppModel} from a decompiled app. Deterministic: sorted walks and lists, no timestamps,
 * relative paths; ingest_hash = sha256 of the model serialised with an empty hash field.
 */
public class Ingestor {

    private static final List<String> PLATFORM_IMPORT_PREFIXES = List.of("android.", "androidx.", "java.", "javax.", "kotlin.");

    private final AppConfig.Ingest config;
    private final ManifestParser manifestParser;
    private final ResourceExtractor resourceExtractor;
    private final DependencyInventory dependencyInventory;

    public Ingestor(AppConfig.Ingest config, ManifestParser manifestParser, ResourceExtractor resourceExtractor,
                    DependencyInventory dependencyInventory) {
        this.config = config;
        this.manifestParser = manifestParser;
        this.resourceExtractor = resourceExtractor;
        this.dependencyInventory = dependencyInventory;
    }

    public AppModel ingest(DecompiledApp app) {
        AppLayout layout = app.layout();
        CodeBase code = new CodeBase(layout.root());
        Manifest manifest = manifestParser.parse(layout.manifest());

        List<String> declared = new ArrayList<>(manifest.components().stream().map(Component::name).toList());
        declared.add(manifest.applicationName());
        FirstPartyClassifier classifier = new FirstPartyClassifier(manifest.packageName(), declared,
                config.libraryPrefixes(), config.extraFirstPartyPrefixes(), config.excludeClassPatterns());

        Map<String, String> componentByClass = new HashMap<>();
        manifest.components().forEach(c -> componentByClass.putIfAbsent(c.name(), c.name()));

        List<SourceFile> sources = collectSources(layout, classifier, componentByClass);
        List<SourceFile> firstParty = sources.stream().filter(SourceFile::isFirstParty).toList();
        if (firstParty.isEmpty()) {
            throw new DecompileException("no first-party Java sources found (prefixes " + classifier.prefixes()
                    + "). For an apktool (smali-only) input, decompile with jadx first; or set ingest.extra_first_party_prefixes.");
        }

        Resources resources = resourceExtractor.extract(layout.resourcesRoot(), layout.root(), manifest);
        List<Dependency> dependencies = config.dependencyInventory()
                ? dependencyInventory.collect(layout.resourcesRoot(), sources, code, classifier.prefixes()) : List.of();

        TreeSet<String> identifiers = new TreeSet<>();
        TreeSet<String> literals = new TreeSet<>();
        TreeSet<String> platformVocabulary = new TreeSet<>();
        TreeSet<String> callbacks = new TreeSet<>();
        int names = 0;
        int shortNames = 0;
        int methodsFailed = 0;

        identifiers.add(manifest.packageName());
        identifiers.addAll(List.of(manifest.packageName().split("\\.")));
        for (Component c : manifest.components()) {
            identifiers.add(c.name());
            identifiers.add(simpleName(c.name()));
        }
        ResourceExtractor.readStrings(layout.resourcesRoot().resolve("res/values/strings.xml")).keySet().forEach(identifiers::add);
        resources.strings().forEach(s -> identifiers.addAll(JavaText.hostnames(s.value())));

        for (SourceFile f : firstParty) {
            String text = code.text(f.path());
            identifiers.add(f.className());
            for (String type : JavaText.typeNames(text)) {
                identifiers.add(type);
                names++;
                shortNames += type.length() <= 2 ? 1 : 0;
            }
            for (String method : JavaText.methodNames(text)) {
                identifiers.add(method);
                names++;
                shortNames += method.length() <= 2 ? 1 : 0;
            }
            identifiers.addAll(JavaText.hostnames(text));
            literals.addAll(JavaText.stringLiterals(text));
            callbacks.addAll(JavaText.overrideMethodNames(text));
            for (String imp : JavaText.imports(text)) {
                if (PLATFORM_IMPORT_PREFIXES.stream().anyMatch(imp::startsWith)) {
                    platformVocabulary.add(simpleName(imp));
                }
            }
            methodsFailed += JadxDecompiler.count(text, "Code decompiled incorrectly") + JadxDecompiler.count(text, "Method dump skipped");
        }
        literals.remove("");

        int generatedExcluded = (int) sources.stream()
                .filter(s -> !s.isFirstParty() && classifier.inFirstPartyPackage(s.className())).count();
        Stats stats = new Stats(sources.size(), firstParty.size(), sources.size() - firstParty.size(), generatedExcluded,
                classifier.prefixes(), topPackages(sources, true), topPackages(sources, false), methodsFailed,
                names == 0 ? 0 : Math.round(1000.0 * shortNames / names) / 1000.0, null);

        AppModel model = new AppModel(AppModel.SCHEMA_VERSION, app.appId(), manifest.packageName(), manifest, sources,
                resources, dependencies, List.copyOf(identifiers), List.copyOf(literals), List.copyOf(platformVocabulary),
                List.copyOf(callbacks), stats);
        return model.withStats(stats.withIngestHash(hash(model)));
    }

    private List<SourceFile> collectSources(AppLayout layout, FirstPartyClassifier classifier, Map<String, String> componentByClass) {
        List<SourceFile> out = new ArrayList<>();
        for (Path root : layout.sourceRoots()) {
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
                for (Path p : files) {
                    String rel = layout.root().relativize(p).toString().replace('\\', '/');
                    String cls = root.relativize(p).toString().replace('\\', '/');
                    cls = cls.substring(0, cls.length() - ".java".length()).replace('/', '.');
                    String text = com.vulnscan.common.Files2.read(p);
                    int lines = text.isEmpty() ? 0 : (int) text.lines().count();
                    out.add(new SourceFile(rel, cls, "java", lines, Hashing.sha256(text), classifier.isFirstParty(cls),
                            componentByClass.get(cls)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        out.sort(Comparator.comparing(SourceFile::path));
        return out;
    }

    private static List<PackageCount> topPackages(List<SourceFile> sources, boolean firstParty) {
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (SourceFile s : sources) {
            if (s.isFirstParty() == firstParty) {
                String cls = s.className();
                String pkg = firstParty ? (cls.contains(".") ? cls.substring(0, cls.lastIndexOf('.')) : "")
                        : DependencyInventory.prefix(cls, 2);
                counts.merge(pkg, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(10)
                .map(e -> new PackageCount(e.getKey(), e.getValue()))
                .toList();
    }

    static String hash(AppModel model) {
        try {
            return Hashing.sha256(Json.json().writeValueAsString(model.withStats(model.stats().withIngestHash(null))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String simpleName(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }
}
