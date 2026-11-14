package com.vulnscan.matching;

import com.vulnscan.scan.Finding;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference for apps without ground truth: the teacher's verified findings, one item each (title, rationale as the
 * description, one location). Every number computed against it is "agreement with teacher", not recall.
 */
public class TeacherFindingsReference implements ReferenceSource {

    private final List<ReferenceItem> items = new ArrayList<>();

    public TeacherFindingsReference(List<Finding> verifiedTeacherFindings) {
        int i = 1;
        for (Finding f : verifiedTeacherFindings) {
            String clazz = f.location().symbol() == null ? null : f.location().symbol().split("\\.")[0];
            String method = f.location().symbol() != null && f.location().symbol().contains(".")
                    ? f.location().symbol().substring(f.location().symbol().indexOf('.') + 1) : null;
            items.add(new ReferenceItem("T-%02d".formatted(i++), f.title(), f.rationale(),
                    f.cwe() == null ? null : List.of(f.cwe()),
                    List.of(new ReferenceItem.Place(f.location().component(), clazz, method, f.location().file())),
                    f.kind(), "full", f.owaspCategory()));
        }
    }

    @Override
    public String name() {
        return "teacher_findings";
    }

    @Override
    public List<ReferenceItem> items() {
        return items;
    }

    @Override
    public boolean isGroundTruth() {
        return false;
    }
}
