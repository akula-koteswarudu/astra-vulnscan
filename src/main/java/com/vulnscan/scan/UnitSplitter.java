package com.vulnscan.scan;

import com.vulnscan.planning.ScanUnit;
import java.util.List;

/** Re-plans a unit whose answer did not fit into smaller units (empty list = cannot split further). */
public interface UnitSplitter {
    List<ScanUnit> split(ScanUnit unit);
}
