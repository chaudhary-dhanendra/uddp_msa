package gov.uddp.core.geography;

import java.time.LocalDate;
import java.util.List;

/** A complete LGD snapshot fetched by an {@link LgdSourceClient}: where it came from, its as-of date and its rows. */
public record LgdSnapshotData(String sourceLabel, LocalDate asOf, List<LgdRow> rows) {
    public LgdSnapshotData {
        rows = List.copyOf(rows);
    }
}
