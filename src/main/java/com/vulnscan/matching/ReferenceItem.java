package com.vulnscan.matching;

import java.util.List;

/**
 * One thing a finding can be matched to: a ground-truth entry (or one of its locations) or a teacher finding.
 * Optional fields are null when the source does not have them; the matcher only uses fields that exist.
 */
public record ReferenceItem(String id, String title, String description, List<String> acceptedCwes,
                            List<Place> locations, String kind, String staticVisibility, String owaspCategory) {

    /** A place: any of component, class (fully qualified), method, file may be null. */
    public record Place(String component, String clazz, String method, String file) {
    }

    public boolean hasStructuredFields() {
        return (acceptedCwes != null && !acceptedCwes.isEmpty()) || (locations != null && !locations.isEmpty());
    }
}
