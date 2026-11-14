package com.vulnscan.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Built-in secret formats, taken from common secret-scanner rule sets (gitleaks / trufflehog style), plus
 * extra regexes from config. Used to keep strings.xml entries whose value looks like a credential or cloud identifier.
 */
public class SecretPatterns {

    static final Map<String, String> BUILT_IN = new LinkedHashMap<>();

    static {
        BUILT_IN.put("aws_access_key_id", "\\b(?:AKIA|ASIA|AGPA|AIDA|AROA)[0-9A-Z]{16}\\b");
        BUILT_IN.put("aws_cognito_identity_pool_id",
                "\\b[a-z]{2}-[a-z]+-\\d:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
        BUILT_IN.put("google_api_key", "\\bAIza[0-9A-Za-z_\\-]{35}\\b");
        BUILT_IN.put("firebase_database_url", "https://[a-z0-9-]+\\.firebaseio\\.com");
        BUILT_IN.put("private_key_header", "-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----");
        BUILT_IN.put("jwt", "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");
        BUILT_IN.put("bearer_token", "(?i)\\bbearer\\s+[A-Za-z0-9\\-._~+/]{20,}=*");
    }

    private final Map<String, Pattern> patterns = new LinkedHashMap<>();

    public SecretPatterns(List<String> extraRegexes) {
        BUILT_IN.forEach((name, regex) -> patterns.put(name, Pattern.compile(regex)));
        for (int i = 0; i < extraRegexes.size(); i++) {
            patterns.put("extra_" + (i + 1), Pattern.compile(extraRegexes.get(i)));
        }
    }

    /** Names of the patterns the value matches (empty when none). */
    public List<String> matches(String value) {
        List<String> out = new ArrayList<>();
        patterns.forEach((name, p) -> {
            if (p.matcher(value).find()) {
                out.add(name);
            }
        });
        return out;
    }

    /** Shannon entropy in bits per character. */
    public static double entropy(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        Map<Character, Integer> counts = new java.util.HashMap<>();
        for (char c : s.toCharArray()) {
            counts.merge(c, 1, Integer::sum);
        }
        double h = 0;
        for (int n : counts.values()) {
            double p = (double) n / s.length();
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }
}
