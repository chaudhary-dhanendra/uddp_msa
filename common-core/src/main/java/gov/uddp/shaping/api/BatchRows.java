package gov.uddp.shaping.api;

import gov.uddp.core.mapping.RawRecord;
import java.util.List;
import java.util.UUID;

/** The raw records of one delivery: the sheet-wide context that layout operations (header-detect, carry-down) need. */
public interface BatchRows {

    List<RawRecord> records(UUID batchId);

    /**
     * The raw records of one sheet of a delivery from one source (for a CSV delivery, {@code file} and {@code sheet}
     * are null: the whole file). The default reads the delivery and filters by source only; the caller still keeps
     * just the rows of the sheet. A store should override it to read only the sheet, so memory stays bounded by the
     * largest sheet.
     */
    default List<RawRecord> sheet(UUID batchId, String sourceSystem, String file, String sheet) {
        return records(batchId).stream().filter(r -> sourceSystem.equals(r.sourceSystem())).toList();
    }
}
