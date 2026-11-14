package com.vulnscan.planning;

import java.util.stream.Collectors;

/**
 * The text of a source file as shown to a model. Drops lines that carry no security content and only cost tokens:
 * Kotlin {@code @Metadata(...)} annotations (encoded compiler metadata) and jadx "compiled from" comments.
 * Evidence is always verified against the ORIGINAL file, so nothing a model can quote is lost.
 */
public final class CodeView {

    private CodeView() {
    }

    public static String render(String original) {
        return original.lines()
                .filter(l -> {
                    String t = l.stripLeading();
                    return !t.startsWith("@Metadata(") && !t.startsWith("/* JADX INFO: compiled from");
                })
                .collect(Collectors.joining("\n"));
    }
}
