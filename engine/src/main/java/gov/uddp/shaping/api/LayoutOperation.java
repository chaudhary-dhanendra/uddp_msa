package gov.uddp.shaping.api;

import java.util.List;
import java.util.Map;

/** A named, versioned step that reshapes raw rows (skip banners, unmerge, carry down, ...). */
public interface LayoutOperation {

    String name();

    String version();

    List<RawRow> apply(List<RawRow> rows, Map<String, Object> config);
}
