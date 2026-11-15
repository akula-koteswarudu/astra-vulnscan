package com.vulnscan.pipeline;

import com.vulnscan.config.AppConfig;
import com.vulnscan.ingest.AppModel;
import com.vulnscan.planning.ScanUnit;
import com.vulnscan.planning.UnitContextBuilder;
import com.vulnscan.planning.UnitPlan;
import com.vulnscan.scan.EvidenceVerifier;
import com.vulnscan.scan.Finding;
import com.vulnscan.scan.RawFinding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-checks the evidence of saved findings that are still "unverified" with the current verifier (e.g. after a verifier
 * fix), without rescanning. A finding is checked against its scan unit when the plan still has it; otherwise (units that
 * were re-planned after truncation) against the whole first-party app. The original file is kept as findings.scan.json.
 */
public class Reverifier {

    public void reverify(Experiment e) {
        AppConfig.Evidence ev = e.config().app().scan().evidence();
        PreparedApp app = e.app();
        EvidenceVerifier verifier = new EvidenceVerifier(app.code(), ev.fuzzyThreshold(), ev.minEvidenceChars());
        ScanUnit wholeApp = wholeApp(app.model());
        int changed = 0;
        for (Arm arm : Arm.values()) {
            Map<String, ScanUnit> units = new HashMap<>();
            String planFile = "03_plan/units_" + (arm.isStudent() ? "student" : "teacher") + ".json";
            if (e.store().exists(planFile)) {
                e.store().readJson(planFile, UnitPlan.class).units().forEach(u -> units.put(u.unitId(), u));
            }
            for (int run : e.runsOf(arm)) {
                String dir = Experiment.armDir(arm, run);
                ArmOutput out = e.store().readJson(dir + "findings.json", ArmOutput.class);
                List<Finding> updated = new ArrayList<>();
                int here = 0;
                for (Finding f : out.findings()) {
                    if (!"unverified".equals(f.evidenceStatus())) {
                        updated.add(f);
                        continue;
                    }
                    ScanUnit unit = f.provenance().unitIds().stream().map(units::get).filter(java.util.Objects::nonNull)
                            .findFirst().orElse(wholeApp);
                    RawFinding raw = new RawFinding(f.title(), f.vulnClass(), f.cwe(), f.owaspCategory(), f.severity(), f.confidence(),
                            List.of(new RawFinding.RawLocation(f.location().file(), f.location().component(), f.location().symbol())),
                            f.evidence(), f.rationale(), f.kind());
                    EvidenceVerifier.Check c = verifier.verify(raw, unit);
                    if (c.status().equals("unverified")) {
                        updated.add(f);
                        continue;
                    }
                    here++;
                    updated.add(new Finding(f.id(), f.title(), f.vulnClass(), f.cwe(), f.owaspCategory(), f.severity(), f.confidence(),
                            new Finding.Location(c.file(), f.location().component(), f.location().symbol(), c.line(), c.endLine()),
                            f.evidence(), f.rationale(), f.kind(), c.status(), c.needsAdvisoryCheck(), f.provenance()));
                }
                if (here > 0) {
                    if (!e.store().exists(dir + "findings.scan.json")) {
                        e.store().writeJson(dir + "findings.scan.json", out);
                    }
                    e.store().writeJson(dir + "findings.json", new ArmOutput(out.schemaVersion(), out.arm(), out.run(), out.model(), updated));
                    e.progress().accept("reverify " + arm.id + " run " + run + ": " + here + " finding(s) now verified");
                }
                changed += here;
            }
        }
        e.manifest().putIn("reverify", java.time.Instant.now().toString(), Map.of("newly_verified", changed));
    }

    private static ScanUnit wholeApp(AppModel app) {
        UnitContextBuilder ctx = new UnitContextBuilder();
        List<String> files = app.firstPartySources().stream().map(AppModel.SourceFile::path).sorted().toList();
        return new ScanUnit("reverify-whole-app", "whole", "app", files, ctx.fullManifestSlice(app), ctx.resources(app),
                ctx.dependencies(app), 0, 0);
    }
}
