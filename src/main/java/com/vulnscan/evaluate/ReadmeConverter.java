package com.vulnscan.evaluate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a README "Vulnerabilities" section into a README-layer ground truth.
 * Two accepted formats, one item per line, numbered 1..N without gaps:
 * <ul>
 *   <li>{@code N. **Title:** description} (title and description verbatim);</li>
 *   <li>{@code N. description} (no title: the title is the description's first sentence, verbatim).</li>
 * </ul>
 * Prose before the first numbered item is skipped (an introduction). Once the list has started, any other non-blank line
 * inside the section, or a mix of the two formats, fails loudly, so a format change can never
 * silently drop items.
 */
public class ReadmeConverter {

    private static final Pattern ITEM = Pattern.compile("^(\\d+)\\.\\s+\\*\\*(.+?):\\*\\*\\s*(.+)$");
    private static final Pattern PLAIN_ITEM = Pattern.compile("^(\\d+)\\.\\s+(.+)$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");

    public GroundTruth convert(String readme, String source) {
        List<GroundTruth.Item> items = new ArrayList<>();
        Boolean titled = null; // which format this list uses (decided by the first item)
        boolean inSection = false;
        String[] lines = readme.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                if (inSection) {
                    break; // next section starts
                }
                String h = heading.group(1).toLowerCase();
                inSection = h.contains("vulnerabilities") && !h.contains("guidance");
                continue;
            }
            if (!inSection || line.isEmpty()) {
                continue;
            }
            if (line.matches("^([-*_])\\1{2,}$")) {
                break; // a horizontal rule ends the section, like a heading
            }
            Matcher m = ITEM.matcher(line);
            Matcher plain = PLAIN_ITEM.matcher(line);
            boolean isTitled = m.matches();
            if (!isTitled && !plain.matches() && items.isEmpty()) {
                continue; // introduction text before the first numbered item
            }
            if (!isTitled && !plain.matches()) {
                throw new IllegalArgumentException("line " + (i + 1) + " in the Vulnerabilities section is not of the form "
                        + "'N. **Title:** description' or 'N. description': " + line);
            }
            if (titled == null) {
                titled = isTitled;
            } else if (titled != isTitled) {
                throw new IllegalArgumentException("line " + (i + 1) + ": the list mixes 'N. **Title:** description' and 'N. description' items");
            }
            Matcher used = isTitled ? m : plain;
            int number = Integer.parseInt(used.group(1));
            if (number != items.size() + 1) {
                throw new IllegalArgumentException("line " + (i + 1) + ": expected item " + (items.size() + 1) + ", found " + number);
            }
            String title = isTitled ? m.group(2).strip() : firstSentence(plain.group(2).strip());
            String description = isTitled ? m.group(3).strip() : plain.group(2).strip();
            items.add(new GroundTruth.Item("GT-%02d".formatted(number), title, description, null));
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("no 'Vulnerabilities' section with numbered items found");
        }
        return new GroundTruth(1, source, null, items);
    }

    /** Text up to the first ". " (a sentence end), verbatim; the whole text when there is none. */
    static String firstSentence(String text) {
        int end = text.indexOf(". ");
        String s = end < 0 ? text : text.substring(0, end + 1);
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
