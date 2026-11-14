package com.vulnscan.scan;

import com.vulnscan.common.Hashing;
import com.vulnscan.config.ModelsConfig.ModelSpec;
import com.vulnscan.models.LlmClient;
import com.vulnscan.models.LlmException;
import com.vulnscan.models.LlmRequest;
import com.vulnscan.models.LlmResponse;
import com.vulnscan.models.RequestFactory;
import com.vulnscan.planning.ScanUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans one unit with one model (plan 6.5).
 * <ul>
 *   <li>refusal -> status refused (category recorded);</li>
 *   <li>max_tokens -> one retry with max_tokens doubled (adaptive thinking may have used the budget); still truncated ->
 *       the unit is re-planned into smaller units which are scanned instead; if it cannot be split -> truncated
 *       (half a JSON answer is never parsed);</li>
 *   <li>invalid JSON -> ONE repair call quoting the error; still invalid -> parse_failed;</li>
 *   <li>a call that fails after retries -> failed (the arm continues with the other units).</li>
 * </ul>
 * Valid findings go through normalise -> split -> verify evidence (dedup happens across units in {@link ArmScanner}).
 */
public class Scanner {

    private static final Logger log = LoggerFactory.getLogger(Scanner.class);
    /** Upper bound for the doubled output budget (largest output current models accept). */
    static final int MAX_OUTPUT_CAP = 128_000;

    private final LlmClient client;
    private final ModelSpec spec;
    private final String modelName;
    private final PromptBuilder prompts;
    private final FindingParser parser;
    private final Normalizer normalizer;
    private final Splitter splitter;
    private final EvidenceVerifier verifier;
    private final UnitSplitter unitSplitter;

    public Scanner(LlmClient client, ModelSpec spec, String modelName, PromptBuilder prompts, FindingParser parser,
                   Normalizer normalizer, Splitter splitter, EvidenceVerifier verifier, UnitSplitter unitSplitter) {
        this.client = client;
        this.spec = spec;
        this.modelName = modelName;
        this.prompts = prompts;
        this.parser = parser;
        this.normalizer = normalizer;
        this.splitter = splitter;
        this.verifier = verifier;
        this.unitSplitter = unitSplitter;
    }

    public ScanResult scan(ScanUnit unit, String guidance, ScanRun run) {
        return scan(unit, guidance, run, 0);
    }

    private ScanResult scan(ScanUnit unit, String guidance, ScanRun run, int depth) {
        List<ScanResult.Exchange> exchanges = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        LlmRequest.Metadata meta = new LlmRequest.Metadata("scan", run.arm(), run.runId(), unit.unitId(), run.repeatIndex());
        LlmRequest request = RequestFactory.forModel(spec, prompts.system(), prompts.user(unit, guidance),
                PromptBuilder.FINDINGS_SCHEMA, meta);

        LlmResponse response;
        try {
            response = call(request, "scan", exchanges);
            int doubled = Math.min(request.maxTokens() * 2, outputCap());
            if (response.stopReason() == LlmResponse.StopReason.MAX_TOKENS && doubled <= request.maxTokens()) {
                errors.add("truncated at " + request.maxTokens() + " tokens; the model's output limit does not allow a larger answer");
            } else if (response.stopReason() == LlmResponse.StopReason.MAX_TOKENS) {
                log.info("unit {} truncated at {} tokens; retrying with {}", unit.unitId(), request.maxTokens(), doubled);
                try {
                    response = call(request.withMaxTokens(doubled), "scan_more_tokens", exchanges);
                } catch (LlmException tooLarge) {
                    if (tooLarge.retryable()) {
                        throw tooLarge;
                    }
                    errors.add("doubled retry not possible: " + tooLarge.getMessage());
                }
            }
        } catch (LlmException e) {
            errors.add(e.getMessage());
            return result(unit, "failed", List.of(), 0, null, errors, exchanges, List.of());
        }

        if (response.stopReason() == LlmResponse.StopReason.REFUSAL) {
            return result(unit, "refused", List.of(), 0, response.refusalCategory(), errors, exchanges, List.of());
        }
        if (response.stopReason() == LlmResponse.StopReason.MAX_TOKENS) {
            return replan(unit, guidance, run, depth, errors, exchanges);
        }

        FindingParser.Parsed parsed;
        try {
            parsed = parser.parse(response.text());
        } catch (FindingParser.InvalidResponseException first) {
            errors.add("invalid response: " + first.getMessage());
            try {
                LlmRequest repair = request.withMessages(List.of(
                        LlmRequest.Message.user(request.messages().get(0).content()),
                        LlmRequest.Message.assistant(response.text().isBlank() ? "(empty)" : response.text()),
                        LlmRequest.Message.user("Your previous answer could not be used: " + first.getMessage()
                                + ". Reply again with only the corrected JSON object {\"findings\": [...]} and no other text.")))
                        .withMetadata(new LlmRequest.Metadata("repair", run.arm(), run.runId(), unit.unitId(), run.repeatIndex()));
                LlmResponse repaired = call(repair, "repair", exchanges);
                parsed = parser.parse(repaired.text());
                response = repaired;
            } catch (FindingParser.InvalidResponseException | LlmException second) {
                errors.add("repair failed: " + second.getMessage());
                return result(unit, "parse_failed", List.of(), 0, null, errors, exchanges, List.of());
            }
        }
        errors.addAll(parsed.problems());
        List<Finding> findings = postProcess(parsed.findings(), unit, run, response.modelUsed());
        return result(unit, "ok", findings, parsed.badFindings(), null, errors, exchanges, List.of());
    }

    /** Largest max_tokens this model accepts: the per-minute output limit when there is one. */
    private int outputCap() {
        Integer otpm = spec.rateLimit().otpm();
        return otpm == null ? MAX_OUTPUT_CAP : Math.min(MAX_OUTPUT_CAP, otpm);
    }

    private ScanResult replan(ScanUnit unit, String guidance, ScanRun run, int depth, List<String> errors,
                              List<ScanResult.Exchange> exchanges) {
        List<ScanUnit> parts = depth == 0 ? unitSplitter.split(unit) : List.of();
        if (parts.isEmpty()) {
            errors.add("answer still truncated after doubling max_tokens and the unit cannot be split further");
            return result(unit, "truncated", List.of(), 0, null, errors, exchanges, List.of());
        }
        log.info("unit {} still truncated; re-planned into {} smaller units", unit.unitId(), parts.size());
        List<ScanResult> subs = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        int bad = 0;
        for (ScanUnit part : parts) {
            ScanResult r = scan(part, guidance, run, depth + 1);
            subs.add(r);
            findings.addAll(r.findings());
            bad += r.badFindings();
        }
        boolean allOk = subs.stream().allMatch(r -> r.status().equals("ok"));
        errors.add("re-planned into " + parts.size() + " units after truncation");
        return result(unit, allOk ? "ok" : "partial", findings, bad, null, errors, exchanges, subs);
    }

    private LlmResponse call(LlmRequest request, String purpose, List<ScanResult.Exchange> exchanges) {
        LlmResponse r = client.complete(request);
        exchanges.add(new ScanResult.Exchange(purpose, request.maxTokens(), r.stopReason().name(), r.modelUsed(),
                r.inputTokens(), r.outputTokens(), r.cached(), r.text()));
        return r;
    }

    List<Finding> postProcess(List<RawFinding> raw, ScanUnit unit, ScanRun run, String modelUsed) {
        List<Finding> out = new ArrayList<>();
        for (RawFinding f : raw) {
            for (RawFinding one : splitter.split(normalizer.normalize(f))) {
                EvidenceVerifier.Check check = verifier.verify(one, unit);
                RawFinding.RawLocation loc = one.locations().get(0);
                String id = Hashing.sha256(String.join("|", run.arm(), run.runId(), String.valueOf(run.repeatIndex()),
                        unit.unitId(), one.title(), String.valueOf(check.file()), String.valueOf(loc.symbol()),
                        one.evidence(), String.valueOf(one.cwe()))).substring(0, 16);
                out.add(new Finding(id, one.title(), one.vulnClass(), one.cwe(), one.owaspCategory(), one.severity(),
                        one.confidence(), new Finding.Location(check.file(), loc.component(), loc.symbol(), check.line(), check.endLine()),
                        one.evidence(), one.rationale(), one.kind(), check.status(), check.needsAdvisoryCheck(),
                        new Finding.Provenance(modelUsed == null ? spec.model() : modelUsed, run.arm(), run.runId(),
                                run.repeatIndex(), List.of(unit.unitId()), prompts.version())));
            }
        }
        return out;
    }

    private static ScanResult result(ScanUnit unit, String status, List<Finding> findings, int bad, String refusal,
                                     List<String> errors, List<ScanResult.Exchange> exchanges, List<ScanResult> subs) {
        long in = exchanges.stream().mapToLong(ScanResult.Exchange::inputTokens).sum();
        long out = exchanges.stream().mapToLong(ScanResult.Exchange::outputTokens).sum();
        boolean cached = exchanges.stream().anyMatch(ScanResult.Exchange::cached);
        return new ScanResult(unit.unitId(), status, findings, bad, in, out, cached, refusal, errors, exchanges, subs);
    }

    public String modelName() {
        return modelName;
    }

    public int concurrency() {
        return spec.concurrency();
    }
}
