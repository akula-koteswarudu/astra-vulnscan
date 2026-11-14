package com.vulnscan.learn;

import com.vulnscan.config.AppConfig.OnViolation;
import com.vulnscan.ingest.AppModel;
import com.vulnscan.ingest.CodeBase;
import com.vulnscan.ingest.JavaText;
import com.vulnscan.scan.Finding;
import com.vulnscan.scan.TextMatch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic lesson sanitiser (plan 6.6): keeps lessons general by finding app-specific terms and redacting or
 * dropping them. No LLM involved.
 * <ul>
 *   <li>tier 1: app identifiers as whole tokens (package, classes, methods, components, string-resource names, hosts);</li>
 *   <li>tier 2: distinctive identifier parts (camelCase / dot / underscore split, at least 4 characters, not a common
 *       English word, not platform vocabulary), matched inside any word of the lesson;</li>
 *   <li>first-party string literals as exact matches (also short ones such as credentials);</li>
 *   <li>regexes for file names, paths and line references.</li>
 * </ul>
 * Exempt: framework callbacks declared with @Override, platform vocabulary, and names that also occur in the app's
 * bundled library code (androidx and other third-party sources in the same APK: names used there are platform or
 * library API, not this app's); literals that are platform names,
 * bare URI schemes, MIME types, single common words, or shorter than 6 characters and not credential-like.
 * Answer-key check: an abstract_example too similar to its source evidence (after masking names and literals) is
 * removed (redact) or the lesson is dropped (drop).
 */
public class LessonSanitizer {

    static final Pattern FILE_NAME = Pattern.compile("\\b[\\w$-]+\\.(?:java|kt|xml|smali)\\b");
    /** A file path: two or more separators and a last segment with an extension ("a/b/c" alternatives are not paths). */
    static final Pattern PATH = Pattern.compile("(?:[\\w.-]+[/\\\\]){2,}[\\w-]+\\.[A-Za-z]{1,5}\\b");
    /** Names every Android project has; mentioning them is general knowledge, not a leak. */
    static final Set<String> STANDARD_FILES = Set.of("AndroidManifest.xml", "strings.xml", "build.gradle");
    static final Pattern LINE_REF = Pattern.compile("(?i)\\bline\\s+\\d+\\b|\\bL\\d+\\b");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*");
    private static final Pattern URI_SCHEME_ONLY = Pattern.compile("[a-z][a-z0-9+.-]*:(//)?");
    private static final Pattern MIME = Pattern.compile("[a-z]+/[a-z0-9.+*-]+");
    private static final List<String> PLATFORM_PREFIXES = List.of("android.", "androidx.", "java.", "javax.", "kotlin.");
    private static final Pattern STRING_IN_CODE = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    private final Set<String> tier1 = new TreeSet<>();
    private final Set<String> tier2 = new TreeSet<>();
    private final Set<String> literals = new TreeSet<>();
    private final Map<String, Pattern> tier1Patterns = new LinkedHashMap<>();
    private final OnViolation policy;
    private final double exampleSimilarityMax;

    public LessonSanitizer(AppModel app, CodeBase code, Set<String> commonWords, List<String> credentialNamePatterns,
                           OnViolation policy, double exampleSimilarityMax) {
        this.policy = policy;
        this.exampleSimilarityMax = exampleSimilarityMax;
        Set<String> exempt = new HashSet<>(app.frameworkCallbacks());
        exempt.addAll(app.platformVocabulary());
        exempt.addAll(libraryVocabulary(app, code));
        Set<String> platformParts = new HashSet<>();
        for (String v : exempt) {
            platformParts.add(v.toLowerCase(Locale.ROOT));
            platformParts.addAll(parts(v));
        }

        for (String id : app.identifiers()) {
            if (exempt.contains(id) || id.length() < 3) {
                continue;
            }
            List<String> idParts = parts(id);
            boolean singleCommonWord = idParts.size() == 1 && commonWords.contains(idParts.get(0));
            if (!singleCommonWord) {
                tier1.add(id);
            }
            for (String p : idParts) {
                if (p.length() >= 4 && !commonWords.contains(p) && !platformParts.contains(p)) {
                    tier2.add(p);
                }
            }
        }
        // Whole-token patterns, longest first so "com.example.app" wins over "example".
        tier1.stream().sorted((a, b) -> b.length() - a.length())
                .forEach(t -> tier1Patterns.put(t, Pattern.compile("(?<![\\w$])" + Pattern.quote(t) + "(?![\\w$])")));

        Set<String> credentialLike = credentialLikeLiterals(app, code, credentialNamePatterns);
        for (String lit : app.stringLiterals()) {
            if (!exemptLiteral(lit, commonWords, credentialLike)) {
                literals.add(lit);
            }
        }
    }

    public record Result(List<Lesson> lessons, List<Violation> violations, List<Lesson> dropped) {
    }

    public Result sanitize(List<Lesson> lessons, Map<String, Finding> findingsById) {
        List<Lesson> kept = new ArrayList<>();
        List<Lesson> dropped = new ArrayList<>();
        List<Violation> violations = new ArrayList<>();
        for (Lesson lesson : lessons) {
            if (blank(lesson.title()) || blank(lesson.whyExploitable())) {
                violations.add(new Violation(lesson.id(), "lesson", "", "invalid: title and why_exploitable are required", "dropped"));
                dropped.add(lesson);
                continue;
            }
            Lesson current = lesson;
            boolean drop = false;
            if (!blank(lesson.abstractExample()) && answerKeySimilarity(lesson, findingsById) > exampleSimilarityMax) {
                if (policy == OnViolation.DROP) {
                    drop = true;
                    violations.add(new Violation(lesson.id(), "abstract_example", "", "answer_key_similarity", "dropped"));
                } else {
                    current = current.withoutExample();
                    violations.add(new Violation(lesson.id(), "abstract_example", "", "answer_key_similarity", "example_removed"));
                }
            }
            List<Violation> found = findViolations(current);
            if (!found.isEmpty()) {
                if (policy == OnViolation.DROP) {
                    drop = true;
                    found.forEach(v -> violations.add(new Violation(v.lessonId(), v.field(), v.term(), v.rule(), "dropped")));
                } else {
                    found.forEach(v -> violations.add(new Violation(v.lessonId(), v.field(), v.term(), v.rule(), "redacted")));
                    current = redact(current);
                    List<Violation> remaining = findViolations(current);
                    if (!remaining.isEmpty()) {
                        drop = true;
                        remaining.forEach(v -> violations.add(new Violation(v.lessonId(), v.field(), v.term(), v.rule(), "dropped_after_redaction")));
                    }
                }
            }
            if (drop) {
                dropped.add(lesson);
            } else {
                kept.add(current);
            }
        }
        return new Result(kept, violations, dropped);
    }

    // ---- detection -----------------------------------------------------------------------------------------

    List<Violation> findViolations(Lesson l) {
        List<Violation> out = new ArrayList<>();
        forEachField(l, (field, text) -> out.addAll(check(l.id(), field, text)));
        return out;
    }

    List<Violation> check(String lessonId, String field, String text) {
        List<Violation> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, Pattern> e : tier1Patterns.entrySet()) {
            if (e.getValue().matcher(text).find()) {
                out.add(new Violation(lessonId, field, e.getKey(), "identifier", null));
            }
        }
        Matcher w = WORD.matcher(stripPlaceholders(text));
        while (w.find()) {
            String word = w.group();
            if (tier1.contains(word)) {
                continue; // already reported by tier 1
            }
            for (String piece : word.split("\\.")) {
                if (tier2.contains(piece.toLowerCase(Locale.ROOT)) || parts(piece).stream().anyMatch(tier2::contains)) {
                    out.add(new Violation(lessonId, field, piece, "identifier_part", null));
                }
            }
        }
        for (String lit : literals) {
            if (containsLiteral(text, lit)) {
                out.add(new Violation(lessonId, field, lit, "string_literal", null));
            }
        }
        for (Pattern p : List.of(FILE_NAME, PATH, LINE_REF)) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                if (p != LINE_REF && STANDARD_FILES.stream().anyMatch(f -> m.group().endsWith(f))) {
                    continue;
                }
                out.add(new Violation(lessonId, field, m.group(), p == FILE_NAME ? "file_name" : p == PATH ? "path" : "line_reference", null));
            }
        }
        return out;
    }

    private static boolean containsLiteral(String text, String lit) {
        if (lit.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_')) {
            return Pattern.compile("(?<![\\w$])" + Pattern.quote(lit) + "(?![\\w$])").matcher(text).find();
        }
        return text.contains(lit);
    }

    /** Placeholders such as &lt;ExportedActivity&gt; are the intended form and never violations. */
    private static String stripPlaceholders(String text) {
        return text.replaceAll("<[A-Za-z_][\\w ]*>", " ");
    }

    // ---- redaction -----------------------------------------------------------------------------------------

    Lesson redact(Lesson l) {
        UnaryOperator<String> r = this::redactText;
        return new Lesson(l.id(), r.apply(l.title()),
                l.appliesTo() == null ? null : new Lesson.AppliesTo(map(l.appliesTo().componentTypes(), r), map(l.appliesTo().apis(), r)),
                map(l.signals(), r), r.apply(l.whyExploitable()), map(l.falsePositiveGuards(), r), l.cwe(), l.owaspCategory(),
                r.apply(l.severityGuidance()), r.apply(l.abstractExample()), l.sourceFindingIds());
    }

    String redactText(String text) {
        if (text == null) {
            return null;
        }
        String s = text;
        for (Pattern p : List.of(PATH, FILE_NAME)) {
            s = p.matcher(s).replaceAll(m -> STANDARD_FILES.stream().anyMatch(f -> m.group().endsWith(f))
                    ? Matcher.quoteReplacement(m.group()) : "<File>");
        }
        s = LINE_REF.matcher(s).replaceAll("<line>");
        for (String lit : literals.stream().sorted((a, b) -> b.length() - a.length()).toList()) {
            if (containsLiteral(s, lit)) {
                s = s.replace(lit, "<String>");
            }
        }
        for (Map.Entry<String, Pattern> e : tier1Patterns.entrySet()) {
            s = e.getValue().matcher(s).replaceAll(Matcher.quoteReplacement(placeholderFor(e.getKey())));
        }
        Matcher w = WORD.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (w.find()) {
            String word = w.group();
            boolean insidePlaceholder = w.start() > 0 && s.charAt(w.start() - 1) == '<' && w.end() < s.length() && s.charAt(w.end()) == '>';
            boolean flagged = !insidePlaceholder && Arrays.stream(word.split("\\."))
                    .anyMatch(piece -> tier2.contains(piece.toLowerCase(Locale.ROOT)) || parts(piece).stream().anyMatch(tier2::contains));
            w.appendReplacement(sb, Matcher.quoteReplacement(flagged ? placeholderFor(word) : word));
        }
        w.appendTail(sb);
        return sb.toString();
    }

    /** A typed placeholder: &lt;Activity&gt;, &lt;Receiver&gt;, ..., &lt;package&gt;, &lt;method&gt; or &lt;Class&gt;. */
    static String placeholderFor(String term) {
        for (String suffix : List.of("Activity", "Receiver", "Provider", "Service", "Fragment", "Adapter", "Client", "Interface")) {
            if (term.endsWith(suffix)) {
                return "<" + suffix + ">";
            }
        }
        if (term.contains(".")) {
            return "<package>";
        }
        if (term.contains("_") || term.equals(term.toLowerCase(Locale.ROOT))) {
            return "<name>";
        }
        return Character.isUpperCase(term.charAt(0)) ? "<Class>" : "<method>";
    }

    // ---- answer-key check ----------------------------------------------------------------------------------

    double answerKeySimilarity(Lesson l, Map<String, Finding> findingsById) {
        if (l.sourceFindingIds() == null) {
            return 0;
        }
        String example = mask(l.abstractExample());
        double best = 0;
        for (String id : l.sourceFindingIds()) {
            Finding f = findingsById.get(id);
            if (f != null) {
                best = Math.max(best, tokenSortRatio(example, mask(f.evidence())));
            }
        }
        return best;
    }

    /** Names, placeholders and literals become NAME / "S" so that only the code shape is compared. */
    String mask(String text) {
        String s = STRING_IN_CODE.matcher(text == null ? "" : text).replaceAll(" S ");
        s = s.replaceAll("<[A-Za-z_][\\w ]*>", " NAME ");
        Matcher w = WORD.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (w.find()) {
            String word = w.group();
            boolean app = tier1.contains(word) || Arrays.stream(word.split("\\."))
                    .anyMatch(p -> tier2.contains(p.toLowerCase(Locale.ROOT)) || parts(p).stream().anyMatch(tier2::contains));
            w.appendReplacement(sb, app ? "NAME" : Matcher.quoteReplacement(word));
        }
        w.appendTail(sb);
        return sb.toString();
    }

    /**
     * rapidfuzz token_sort_ratio / 100: indel similarity of the two sorted token sequences. (token_set_ratio, named in
     * the plan, scores high whenever the shared tokens dominate, and lessons are meant to share platform API names with
     * the evidence, so it flagged almost every useful example; see BUILD_LOG.)
     */
    static double tokenSortRatio(String a, String b) {
        List<String> ta = tokens(a).stream().sorted().toList();
        List<String> tb = tokens(b).stream().sorted().toList();
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        return TextMatch.ratio(String.join(" ", ta), String.join(" ", tb));
    }

    private static List<String> tokens(String s) {
        return Arrays.stream(s.split("[^\\w]+")).filter(t -> !t.isEmpty()).toList();
    }

    // ---- literal exemptions --------------------------------------------------------------------------------

    static boolean exemptLiteral(String lit, Set<String> commonWords, Set<String> credentialLike) {
        String s = lit.strip();
        if (s.length() < 2) {
            return true;
        }
        if (PLATFORM_PREFIXES.stream().anyMatch(s::startsWith)) {
            return true;
        }
        if (URI_SCHEME_ONLY.matcher(s).matches() || MIME.matcher(s).matches()) {
            return true;
        }
        if (s.chars().allMatch(Character::isLetter) && commonWords.contains(s.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return s.length() < 6 && !credentialLike.contains(lit);
    }

    /**
     * Words declared or used in the app's non-first-party sources (the bundled libraries). Such names are library or
     * platform API (WebView, getData, query, FileProvider) and must not be redacted from lessons.
     */
    static Set<String> libraryVocabulary(AppModel app, CodeBase code) {
        Set<String> words = new HashSet<>();
        List<String> prefixes = app.stats().firstPartyPrefixes();
        for (AppModel.SourceFile f : app.sources()) {
            boolean appPackage = prefixes.stream().anyMatch(p -> f.className().startsWith(p + "."));
            if (f.isFirstParty() || appPackage) {
                continue; // includes the app's own generated R / BuildConfig
            }
            Matcher m = WORD.matcher(com.vulnscan.ingest.CodeView.render(code.text(f.path())));
            while (m.find()) {
                for (String piece : m.group().split("\\.")) {
                    if (piece.length() >= 3) {
                        words.add(piece);
                    }
                }
            }
        }
        return words;
    }

    /** Literals on a code line that also names a credential-like variable, field or key (e.g. password, token). */
    static Set<String> credentialLikeLiterals(AppModel app, CodeBase code, List<String> namePatterns) {
        Set<String> out = new HashSet<>();
        List<List<String>> patterns = namePatterns.stream().map(LessonSanitizer::parts).toList();
        for (AppModel.SourceFile f : app.firstPartySources()) {
            for (String line : code.text(f.path()).split("\n")) {
                List<String> lits = JavaText.stringLiterals(line);
                if (lits.isEmpty()) {
                    continue;
                }
                List<String> words = new ArrayList<>();
                Matcher w = WORD.matcher(line);
                while (w.find()) {
                    for (String piece : w.group().split("\\.")) {
                        words.addAll(parts(piece));
                    }
                }
                lits.forEach(l -> words.addAll(parts(l)));
                if (patterns.stream().anyMatch(p -> containsRun(words, p))) {
                    out.addAll(lits);
                }
            }
        }
        return out;
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** camelCase / dot / underscore / digit split, lower-cased: "ShopCartProvider" -> [shop, cart, provider]. */
    static List<String> parts(String identifier) {
        String spaced = identifier.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        return Arrays.stream(spaced.split("[\\s_.$\\-/0-9]+")).filter(p -> !p.isEmpty()).map(p -> p.toLowerCase(Locale.ROOT)).toList();
    }

    private static boolean containsRun(List<String> haystack, List<String> needle) {
        for (int i = 0; !needle.isEmpty() && i + needle.size() <= haystack.size(); i++) {
            if (haystack.subList(i, i + needle.size()).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> map(List<String> list, UnaryOperator<String> f) {
        return list == null ? null : list.stream().map(f).toList();
    }

    private interface FieldVisitor {
        void visit(String field, String text);
    }

    private static void forEachField(Lesson l, FieldVisitor v) {
        v.visit("title", l.title());
        if (l.appliesTo() != null) {
            list(l.appliesTo().componentTypes()).forEach(t -> v.visit("applies_to.component_types", t));
            list(l.appliesTo().apis()).forEach(t -> v.visit("applies_to.apis", t));
        }
        list(l.signals()).forEach(t -> v.visit("signals", t));
        v.visit("why_exploitable", l.whyExploitable());
        list(l.falsePositiveGuards()).forEach(t -> v.visit("false_positive_guards", t));
        v.visit("severity_guidance", l.severityGuidance());
        v.visit("abstract_example", l.abstractExample());
    }

    private static List<String> list(List<String> l) {
        return l == null ? List.of() : l;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** For the report: sizes of the deny lists. */
    public Map<String, Integer> denyListSizes() {
        return Map.of("identifiers", tier1.size(), "identifier_parts", tier2.size(), "string_literals", literals.size());
    }
}
