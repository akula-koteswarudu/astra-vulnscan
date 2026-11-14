package com.vulnscan.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexical helpers over decompiled Java text. Regex-based on purpose: jadx output is regular, and these results
 * feed only the lesson sanitiser's deny/allow lists and the planner's reference graph, not security decisions.
 */
public final class JavaText {

    private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+)(?:\\.\\*)?\\s*;");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^\\s*(?:@\\w+\\s+)*(?:(?:public|protected|private|static|final|synchronized|abstract|native|default)\\s+)+"
                    + "(?:<[^>]+>\\s+)?[\\w.<>\\[\\],? ]+\\s+(\\w+)\\s*\\(");
    private static final Pattern OVERRIDE_DECL = Pattern.compile(
            "@Override(?:\\s*//[^\\n]*)?\\s+(?:(?:public|protected|private|final|synchronized)\\s+)*[\\w.<>\\[\\],? ]+\\s+(\\w+)\\s*\\(");
    private static final Pattern TYPE_DECL = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern HOST = Pattern.compile("\\b[a-z][a-z0-9+.-]*://([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern FIELD_DECL = Pattern.compile(
            "(?m)^\\s*(?:(?:public|protected|private|static|final|volatile|transient)\\s+)+[\\w.<>\\[\\],? ]+\\s+(\\w+)\\s*[=;]");

    private JavaText() {
    }

    public static List<String> imports(String text) {
        return all(IMPORT, text);
    }

    /** String literal contents with simple escapes undone. */
    public static List<String> stringLiterals(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : all(STRING_LITERAL, text)) {
            out.add(raw.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\"));
        }
        return out;
    }

    public static List<String> methodNames(String text) {
        return all(METHOD_DECL, text);
    }

    public static List<String> overrideMethodNames(String text) {
        return all(OVERRIDE_DECL, text);
    }

    public static List<String> typeNames(String text) {
        return all(TYPE_DECL, text);
    }

    public static List<String> fieldNames(String text) {
        return all(FIELD_DECL, text);
    }

    public static List<String> hostnames(String text) {
        return all(HOST, text);
    }

    private static List<String> all(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
