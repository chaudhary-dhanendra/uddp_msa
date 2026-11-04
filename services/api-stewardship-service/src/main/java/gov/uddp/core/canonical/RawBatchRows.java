package gov.uddp.core.canonical;

import gov.uddp.core.mapping.RawRecord;
import gov.uddp.shaping.api.BatchRows;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Gives the shaping layer the raw records of a delivery from the Raw landing repository. */
@Component
public class RawBatchRows implements BatchRows {
    private final RawRecordRepository raws;

    public RawBatchRows(RawRecordRepository raws) {
        this.raws = raws;
    }

    @Override
    public List<RawRecord> records(UUID batchId) {
        return raws.findByBatchId(batchId);
    }

    @Override
    public List<RawRecord> sheet(UUID batchId, String sourceSystem, String file, String sheet) {
        return raws.findSheet(batchId, sourceSystem, file, sheet);
    }
}
