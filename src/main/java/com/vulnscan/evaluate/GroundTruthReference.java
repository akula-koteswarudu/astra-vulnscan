package com.vulnscan.evaluate;

import com.vulnscan.matching.ReferenceItem;
import com.vulnscan.matching.ReferenceSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Ground truth as reference items. README mode uses only the verbatim title + description (the headline);
 * enriched mode adds the author's accepted CWEs and locations (the robustness check). kind and static_visibility are
 * carried in both modes for the per-item table only; they never influence matching in README mode.
 */
public class GroundTruthReference implements ReferenceSource {

    private final List<ReferenceItem> items = new ArrayList<>();
    private final boolean enriched;

    public GroundTruthReference(GroundTruth gt, boolean enriched) {
        this.enriched = enriched;
        for (GroundTruth.Item i : gt.items()) {
            GroundTruth.Enrichment e = i.enrichment();
            List<String> cwes = enriched && e != null ? e.acceptedCwes() : null;
            List<ReferenceItem.Place> places = null;
            if (enriched && e != null && e.locations() != null) {
                places = e.locations().stream().map(l -> new ReferenceItem.Place(l.component(), l.clazz(), l.method(), l.file())).toList();
            }
            items.add(new ReferenceItem(i.id(), i.title(), i.description(), cwes, places,
                    e == null ? null : e.kind(), e == null ? null : e.staticVisibility(), null));
        }
    }

    @Override
    public String name() {
        return enriched ? "enriched" : "readme";
    }

    @Override
    public List<ReferenceItem> items() {
        return items;
    }

    @Override
    public boolean isGroundTruth() {
        return true;
    }
}
