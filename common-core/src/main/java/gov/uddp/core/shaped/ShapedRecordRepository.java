package gov.uddp.core.shaped;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Persists the tidy record of a source that declares layout or parse steps (table {@code shaped_record}, V100). */
@Repository
public class ShapedRecordRepository {
    private final PostgresConnectionFactory connections;

    public ShapedRecordRepository(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    public void save(UUID rawRecordId, String statePackVersion, String shapingDigest, String fieldsJson,
                     String provenanceJson) {
        String sql = "insert into shaped_record (raw_record_id, state_pack_version, shaping_digest, fields, provenance)"
                + " values (?, ?, ?, cast(? as jsonb), cast(? as jsonb)) on conflict do nothing";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, rawRecordId);
            s.setString(2, statePackVersion);
            s.setString(3, shapingDigest);
            s.setString(4, fieldsJson);
            s.setString(5, provenanceJson);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist shaped record", e);
        }
    }
}
