package com.vulnscan.planning;

import com.vulnscan.ingest.AppModel.SourceFile;
import com.vulnscan.ingest.CodeBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Edges from text: a file references another first-party class by fully qualified name (imports included) or,
 * within the same package, by its simple name as a whole word (no import needed). Class level, one file = one
 * top-level class. Misses reflection, framework callbacks and string-action intents (documented limitation).
 */
public class TextReferenceGraphBuilder implements DependencyGraphBuilder {

    @Override
    public Map<String, Set<String>> build(List<SourceFile> files, CodeBase code) {
        Map<String, SourceFile> byClass = new HashMap<>();
        files.forEach(f -> byClass.put(f.className(), f));
        Map<String, Set<String>> edges = new TreeMap<>();
        for (SourceFile from : files) {
            String text = code.text(from.path());
            String pkg = pkg(from.className());
            Set<String> out = new TreeSet<>();
            for (SourceFile to : files) {
                if (to == from) {
                    continue;
                }
                String simple = to.className().substring(to.className().lastIndexOf('.') + 1);
                boolean fq = text.contains(to.className());
                boolean samePackageSimple = pkg.equals(pkg(to.className()))
                        && Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(text).find();
                if (fq || samePackageSimple) {
                    out.add(to.path());
                }
            }
            edges.put(from.path(), out);
        }
        return edges;
    }

    private static String pkg(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i < 0 ? "" : fqcn.substring(0, i);
    }
}
