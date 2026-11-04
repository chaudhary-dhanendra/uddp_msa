package gov.uddp.core.canonical;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.mapping.RawRecord;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresRawRecordRepository implements RawRecordRepository {
    private static final String COLUMNS = "raw_record_id, batch_id, state_code, source_system, ingested_at, raw_payload, source_metadata::text, state_pack_version, source_schema_version, pipeline_template";
    private final PostgresConnectionFactory connections; private final ObjectMapper json;
    public PostgresRawRecordRepository(PostgresConnectionFactory connections, ObjectMapper json) { this.connections = connections; this.json = json; }
    public Optional<RawRecord> findById(UUID id) { return query("select " + COLUMNS + " from raw_record where raw_record_id = ?", id).stream().findFirst(); }
    public List<RawRecord> findByBatchId(UUID id) { return query("select " + COLUMNS + " from raw_record where batch_id = ? order by ingested_at, raw_record_id", id); }
    /** One sheet of a delivery, read in SQL so only that sheet's rows leave the database (xlsx shaping). */
    @Override
    public List<RawRecord> findSheet(UUID batchId, String sourceSystem, String file, String sheet) {
        String sql = "select " + COLUMNS + " from raw_record where batch_id = ? and source_system = ?"
                + " and (raw_payload::jsonb ->> 'file') is not distinct from ?"
                + " and (raw_payload::jsonb ->> 'sheet') is not distinct from ? order by ingested_at, raw_record_id";
        try (var connection = connections.open(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, batchId);
            statement.setString(2, sourceSystem);
            statement.setString(3, file);
            statement.setString(4, sheet);
            try (var result = statement.executeQuery()) {
                var records = new java.util.ArrayList<RawRecord>();
                while (result.next()) {
                    records.add(read(result));
                }
                return List.copyOf(records);
            }
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot read raw records of a sheet", error);
        }
    }
    @Override
    public void forEachByBatchId(UUID id, int fetchSize, java.util.function.Consumer<RawRecord> consumer) {
        // A cursor needs auto-commit off; this connection only reads, and is opened outside any chunk transaction.
        try (var connection = connections.open()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select " + COLUMNS
                    + " from raw_record where batch_id = ? order by ingested_at, raw_record_id")) {
                statement.setFetchSize(fetchSize);
                statement.setObject(1, id);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        consumer.accept(read(result));
                    }
                }
            } finally {
                connection.rollback();
            }
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot read raw records", error);
        }
    }
    private List<RawRecord> query(String sql, UUID id) {
        try (var connection = connections.open(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id); try (var result = statement.executeQuery()) { var records = new java.util.ArrayList<RawRecord>(); while (result.next()) records.add(read(result)); return List.copyOf(records); }
        } catch (Exception error) { throw new IllegalStateException("Cannot read raw records", error); }
    }
    private RawRecord read(ResultSet r) throws Exception { return new RawRecord(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getString(4), r.getObject(5, OffsetDateTime.class), r.getString(6), json.readValue(r.getString(7), new TypeReference<Map<String, Object>>() {}), r.getString(8), r.getString(9), r.getString(10)); }
}
