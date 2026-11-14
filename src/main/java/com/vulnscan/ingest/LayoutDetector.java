package com.vulnscan.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds the manifest and source roots of a decompiled directory by searching, so the input can be a jadx output
 * folder, a jadx Gradle export or an apktool folder. Fails with a message naming what is missing.
 */
public class LayoutDetector {

    private static final int MAX_DEPTH = 6;

    public AppLayout detect(Path root) {
        if (!Files.isDirectory(root)) {
            throw new DecompileException("input directory not found: " + root);
        }
        Path manifest = findManifest(root);
        Path manifestDir = manifest.getParent();

        // jadx: <root>/resources/AndroidManifest.xml + <root>/sources
        if (manifestDir.getFileName() != null && manifestDir.getFileName().toString().equals("resources")
                && Files.isDirectory(manifestDir.resolveSibling("sources"))) {
            return new AppLayout(AppLayout.Kind.JADX, root, manifest, List.of(manifestDir.resolveSibling("sources")), manifestDir);
        }
        // jadx Gradle export: <module>/src/main/AndroidManifest.xml + src/main/java (+ src/main/res)
        if (Files.isDirectory(manifestDir.resolve("java"))) {
            return new AppLayout(AppLayout.Kind.JADX_GRADLE, root, manifest, List.of(manifestDir.resolve("java")), manifestDir);
        }
        // apktool: <root>/AndroidManifest.xml + smali, smali_classes2, ...
        List<Path> smali = smaliDirs(manifestDir);
        if (!smali.isEmpty()) {
            return new AppLayout(AppLayout.Kind.APKTOOL, root, manifest, smali, manifestDir);
        }
        throw new DecompileException("found " + root.relativize(manifest) + " but no source folder next to it: expected "
                + "'sources/' (jadx), 'java/' (jadx Gradle export) or 'smali*/' (apktool)");
    }

    private Path findManifest(Path root) {
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            return walk.filter(p -> p.getFileName().toString().equals("AndroidManifest.xml"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .min(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString))
                    .orElseThrow(() -> new DecompileException("no AndroidManifest.xml found under " + root
                            + " (searched " + MAX_DEPTH + " levels deep)"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Path> smaliDirs(Path dir) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("smali"))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
