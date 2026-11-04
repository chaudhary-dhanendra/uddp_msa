package gov.uddp.shaping.api;

import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.validation.ValidationIssue;
import gov.uddp.shaping.ShapingEngine;
import java.util.List;

/** The shaping layer as the mapping engines see it: one raw record in, one tidy record out. */
public interface RecordShaper {

    /** A tidy record plus the warnings raised while shaping it. */
    record Shaped(TidyRecord record, List<ValidationIssue> warnings) {
    }

    default TidyRecord shape(RawRecord raw, LoadedStatePack pack, String sourceId) {
        return shapeWithWarnings(raw, pack, sourceId).record();
    }

    Shaped shapeWithWarnings(RawRecord raw, LoadedStatePack pack, String sourceId);

    /** Whether the source declares layout or parse steps (its tidy records are persisted). */
    boolean shaped(LoadedStatePack pack, String sourceId);

    /** Digest of the source's shaping steps: a changed step is a new shaped record. */
    String digest(LoadedStatePack pack, String sourceId);

    /** A shaper without operations: the CSV fields pass through unchanged. */
    static RecordShaper passthrough(RawCsvRecordParser parser) {
        return new ShapingEngine(parser);
    }
}
