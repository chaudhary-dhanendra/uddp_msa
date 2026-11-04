package gov.uddp.core.canonical;

import gov.uddp.core.mapping.RawRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RawRecordRepository {
    Optional<RawRecord> findById(UUID rawRecordId);
    List<RawRecord> findByBatchId(UUID batchId);

    /**
     * Hands the batch's records to {@code consumer} in landing order without holding them all in memory: the
     * Postgres store reads through a cursor of {@code fetchSize} rows. The consumer may use other connections.
     */
    /**
     * The records of one source of a batch whose Excel payload names this file and sheet; with both null, every record
     * of the source (a CSV delivery). The default filters the batch in memory; the Postgres store filters in SQL.
     */
    default List<RawRecord> findSheet(UUID batchId, String sourceSystem, String file, String sheet) {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        return findByBatchId(batchId).stream().filter(r -> sourceSystem.equals(r.sourceSystem())).filter(r -> {
            try {
                var payload = json.readTree(r.rawPayload());
                return java.util.Objects.equals(file, payload.path("file").asText(null))
                        && java.util.Objects.equals(sheet, payload.path("sheet").asText(null));
            } catch (Exception e) {
                return file == null && sheet == null;
            }
        }).toList();
    }

    default void forEachByBatchId(UUID batchId, int fetchSize, java.util.function.Consumer<RawRecord> consumer) {
        findByBatchId(batchId).forEach(consumer);
    }
}
