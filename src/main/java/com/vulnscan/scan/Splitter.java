package com.vulnscan.scan;

import java.util.ArrayList;
import java.util.List;

/** Post-processor 2: one finding per location (a model may list several places for one flaw). */
public class Splitter {

    public List<RawFinding> split(RawFinding f) {
        List<RawFinding> out = new ArrayList<>();
        for (RawFinding.RawLocation l : f.locations()) {
            out.add(new RawFinding(f.title(), f.vulnClass(), f.cwe(), f.owaspCategory(), f.severity(), f.confidence(),
                    List.of(l), f.evidence(), f.rationale(), f.kind()));
        }
        return out;
    }
}
