package gov.uddp.core.mapping;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Minimal JDBC reader used for replaying source-faithful landing records. */
public class RawRecordJdbcReader {
    private final ObjectMapper objectMapper;
    public RawRecordJdbcReader(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public Optional<RawRecord> firstInBatch(Connection connection, UUID batchId) throws SQLException {
        String sql = "select raw_record_id, batch_id, state_code, source_system, ingested_at, raw_payload, "
                + "source_metadata::text, state_pack_version, source_schema_version, pipeline_template "
                + "from raw_record where batch_id = ? order by ingested_at, raw_record_id limit 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new RawRecord(result.getObject("raw_record_id", UUID.class), result.getObject("batch_id", UUID.class),
                        result.getString("state_code"), result.getString("source_system"), result.getObject("ingested_at", OffsetDateTime.class),
                        result.getString("raw_payload"), metadata(result.getString("source_metadata")), result.getString("state_pack_version"),
                        result.getString("source_schema_version"), result.getString("pipeline_template")));
            }
        }
    }
    private Map<String, Object> metadata(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid source_metadata JSON", error); }
    }
}
