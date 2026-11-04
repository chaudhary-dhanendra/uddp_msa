package gov.uddp.core.canonical;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresCanonicalResidentRepository implements CanonicalResidentRepository {
    private final PostgresConnectionFactory connections;

    private gov.uddp.core.events.PlatformEvents events;

    public PostgresCanonicalResidentRepository(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    /**
     * MSA Phase 2: with events enabled, each new version and failure also writes its outbox event atomically. Without
     * a commit the connection is closed and the server discards both writes; inside a bound chunk transaction the
     * chunk's own commit or rollback covers them.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEvents(gov.uddp.core.events.PlatformEvents events) {
        this.events = events;
    }

    private boolean eventsOn() {
        return events != null && events.enabled();
    }

    public boolean insert(CanonicalResidentRecord r) {
        String sql = "insert into canonical_resident (canonical_resident_id, raw_record_id, batch_id, state_code, source_system,"
                + " source_entity, state_pack_version, canonical_payload, mapped_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)"
                + " on conflict (raw_record_id, state_pack_version, canonical_entity_type) do nothing";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, r.canonicalResidentId());
            s.setObject(2, r.rawRecordId());
            s.setObject(3, r.batchId());
            s.setString(4, r.stateCode());
            s.setString(5, r.sourceSystem());
            s.setString(6, r.sourceEntity());
            s.setString(7, r.statePackVersion());
            s.setString(8, r.canonicalPayload());
            s.setObject(9, r.mappedAt());
            return s.executeUpdate() == 1;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist canonical Resident", e);
        }
    }

    /** Writes the canonical row and its version row in one statement, so neither exists without the other. */
    @Override
    public boolean insertVersion(CanonicalResidentRecord r, String sourceRecordKey, int versionNumber, UUID supersedes) {
        String sql = "with inserted as (insert into canonical_resident (canonical_resident_id, raw_record_id, batch_id,"
                + " state_code, source_system, source_entity, state_pack_version, canonical_payload, mapped_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)"
                + " on conflict (raw_record_id, state_pack_version, canonical_entity_type) do nothing"
                + " returning canonical_resident_id)"
                + " insert into canonical_resident_version (canonical_resident_id, source_record_key, version_number,"
                + " supersedes_canonical_id) select canonical_resident_id, ?, ?, ? from inserted";
        try (var c = connections.open()) {
            boolean withEvent = eventsOn();
            if (withEvent) {
                c.setAutoCommit(false);
            }
            try (var s = c.prepareStatement(sql)) {
                s.setObject(1, r.canonicalResidentId());
                s.setObject(2, r.rawRecordId());
                s.setObject(3, r.batchId());
                s.setString(4, r.stateCode());
                s.setString(5, r.sourceSystem());
                s.setString(6, r.sourceEntity());
                s.setString(7, r.statePackVersion());
                s.setString(8, r.canonicalPayload());
                s.setObject(9, r.mappedAt());
                s.setString(10, sourceRecordKey);
                s.setInt(11, versionNumber);
                s.setObject(12, supersedes, java.sql.Types.OTHER);
                boolean inserted = s.executeUpdate() == 1;
                if (withEvent) {
                    if (inserted) {
                        events.residentCreated(c, r, sourceRecordKey, versionNumber, supersedes);
                    }
                    c.commit();
                }
                return inserted;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist canonical Resident version", e);
        }
    }

    @Override
    public Optional<LatestCanonicalVersion> latestVersion(String sourceRecordKey) {
        String sql = "select v.canonical_resident_id, v.version_number, c.state_pack_version, c.canonical_payload::text"
                + " from canonical_resident_version v"
                + " join canonical_resident c on c.canonical_resident_id = v.canonical_resident_id"
                + " where v.source_record_key = ? and not exists (select 1 from canonical_resident_version s"
                + " where s.supersedes_canonical_id = v.canonical_resident_id)";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setString(1, sourceRecordKey);
            try (var r = s.executeQuery()) {
                return r.next()
                        ? Optional.of(new LatestCanonicalVersion(r.getObject(1, UUID.class), r.getInt(2), r.getString(3),
                                r.getString(4)))
                        : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the latest canonical version", e);
        }
    }

    public Optional<CanonicalResidentRecord> findById(UUID id) {
        String sql = "select canonical_resident_id, raw_record_id, batch_id, state_code, source_system, source_entity,"
                + " state_pack_version, canonical_payload::text, mapped_at, created_at"
                + " from canonical_resident where canonical_resident_id = ?";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(read(r)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read canonical Resident", e);
        }
    }

    public boolean exists(UUID rawRecordId, String version) {
        String sql = "select 1 from canonical_resident"
                + " where raw_record_id = ? and state_pack_version = ? and canonical_entity_type = 'Resident'";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, rawRecordId);
            s.setString(2, version);
            try (var r = s.executeQuery()) {
                return r.next();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot check canonical Resident", e);
        }
    }

    public void recordFailure(CanonicalProcessingError e) {
        String sql = "insert into canonical_processing_error (raw_record_id, batch_id, state_pack_version, error_message,"
                + " reason_code, pack_id, source_id, fix_kind, fix_key, draft_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " (select d.draft_id from studio_draft d where d.pack_id = ? order by d.created_at desc limit 1))";
        try (var c = connections.open()) {
            boolean withEvent = eventsOn();
            if (withEvent) {
                c.setAutoCommit(false);
            }
            try (var s = c.prepareStatement(sql)) {
                s.setObject(1, e.rawRecordId());
                s.setObject(2, e.batchId());
                s.setString(3, e.statePackVersion());
                s.setString(4, e.message());
                s.setString(5, e.reasonCode() == null ? gov.uddp.core.validation.ReasonCodes.OTHER : e.reasonCode());
                s.setString(6, e.packId());
                s.setString(7, e.sourceId());
                s.setString(8, e.fix() == null ? null : e.fix().kind());
                s.setString(9, e.fix() == null ? null : e.fix().key());
                s.setString(10, e.packId());
                s.executeUpdate();
                if (withEvent) {
                    events.recordRejected(c, e);
                    c.commit();
                }
            }
        } catch (Exception x) {
            throw new IllegalStateException("Cannot record canonical processing failure", x);
        }
    }

    public void recordValidationWarning(CanonicalValidationWarning w) {
        String sql = "insert into canonical_validation_warning (canonical_resident_id, raw_record_id, batch_id,"
                + " state_pack_version, field_path, rule, message) values (?, ?, ?, ?, ?, ?, ?)";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, w.canonicalResidentId());
            s.setObject(2, w.rawRecordId());
            s.setObject(3, w.batchId());
            s.setString(4, w.statePackVersion());
            s.setString(5, w.fieldPath());
            s.setString(6, w.rule());
            s.setString(7, w.message());
            s.executeUpdate();
        } catch (Exception x) {
            throw new IllegalStateException("Cannot record canonical validation warning", x);
        }
    }

    private CanonicalResidentRecord read(ResultSet r) throws Exception {
        return new CanonicalResidentRecord(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class),
                r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8),
                r.getObject(9, OffsetDateTime.class), r.getObject(10, OffsetDateTime.class));
    }
}
