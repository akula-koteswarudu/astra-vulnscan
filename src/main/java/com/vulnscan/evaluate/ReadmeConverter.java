package com.vulnscan.evaluate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a README "Vulnerabilities" section into a README-layer ground truth.
 * Accepted format (confirmed on the real README): one item per line, {@code N. **Title:** description},
 * numbered 1..N without gaps. Any other non-blank line inside the section fails loudly, so a format change
 * can never silently drop items.
 */
public class ReadmeConverter {

    private static final Pattern ITEM = Pattern.compile("^(\\d+)\\.\\s+\\*\\*(.+?):\\*\\*\\s*(.+)$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");

    public GroundTruth convert(String readme, String source) {
        List<GroundTruth.Item> items = new ArrayList<>();
        boolean inSection = false;
        String[] lines = readme.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                if (inSection) {
                    break; // next section starts
                }
                inSection = heading.group(1).toLowerCase().contains("vulnerabilities");
                continue;
            }
            if (!inSection || line.isEmpty()) {
                continue;
            }
            Matcher m = ITEM.matcher(line);
            if (!m.matches()) {
                throw new IllegalArgumentException("line " + (i + 1) + " in the Vulnerabilities section is not of the form "
                        + "'N. **Title:** description': " + line);
            }
            int number = Integer.parseInt(m.group(1));
            if (number != items.size() + 1) {
                throw new IllegalArgumentException("line " + (i + 1) + ": expected item " + (items.size() + 1) + ", found " + number);
            }
            items.add(new GroundTruth.Item("GT-%02d".formatted(number), m.group(2).strip(), m.group(3).strip(), null));
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("no 'Vulnerabilities' section with numbered items found");
        }
        return new GroundTruth(1, source, null, items);
    }
}
