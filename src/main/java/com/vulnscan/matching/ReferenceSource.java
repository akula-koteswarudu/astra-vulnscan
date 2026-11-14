package com.vulnscan.matching;

import java.util.List;

/** Where reference items come from (README ground truth, enriched ground truth, or teacher findings). */
public interface ReferenceSource {

    String name();

    List<ReferenceItem> items();

    /** False for teacher-as-reference: numbers are "agreement with teacher", never "recall". */
    boolean isGroundTruth();
}
