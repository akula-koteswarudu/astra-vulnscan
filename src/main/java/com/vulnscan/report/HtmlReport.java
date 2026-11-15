package com.vulnscan.report;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * report.html: the same content as report.md, laid out for reading (recall bars, colour-coded item grid, lesson cards).
 * Self-contained (inline CSS, no scripts), so it opens offline from the run folder. All values are HTML-escaped.
 */
public class HtmlReport {

    private final Template template;

    public HtmlReport() {
        try (InputStream in = HtmlReport.class.getResourceAsStream("/report/report.html.mustache")) {
            template = Mustache.compiler().escapeHTML(true).defaultValue("").compile(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String render(MarkdownReport.Inputs in) {
        return template.execute(MarkdownReport.context(in));
    }
}
