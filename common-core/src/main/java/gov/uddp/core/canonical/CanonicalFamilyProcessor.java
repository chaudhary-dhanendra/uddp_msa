package gov.uddp.core.canonical;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.mapping.FamilyMappingEngine;
import gov.uddp.core.mapping.FamilyMappingEngine.FamilyMapping;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.statepack.LoadedStatePack;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes the canonical Family observation of one raw record (M14, ADR-0009): the households the delivery names for
 * the resident it describes. Append-only and idempotent per raw record and State Pack version. A Family mapping
 * failure is recorded in {@code canonical_family_processing_error} and never affects the Resident.
 */
@Service
public class CanonicalFamilyProcessor {
    private final PostgresConnectionFactory connections;
    private final FamilyMappingEngine mapper;
    private final ObjectMapper json;

    private gov.uddp.core.events.PlatformEvents events;

    public CanonicalFamilyProcessor(PostgresConnectionFactory connections, FamilyMappingEngine mapper, ObjectMapper json) {
        this.connections = connections;
        this.mapper = mapper;
        this.json = json;
    }

    /** MSA Phase 2/3: with events enabled, each observation also writes its family-observed event atomically. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEvents(gov.uddp.core.events.PlatformEvents events) {
        this.events = events;
    }

    /** Outcome of observing one raw record. */
    public enum Outcome { OBSERVED, NO_FAMILY_RULES, ALREADY_OBSERVED, FAILED }

    /** stateCode|sourceSystem|sourceEntity|sourceFamilyId: a household as one source names it. */
    public static String familyRecordKey(RawRecord raw, String sourceFamilyId) {
        return String.join("|", raw.stateCode(), raw.sourceSystem(), raw.sourceEntity(), sourceFamilyId);
    }

    /**
     * @param residentRecordKey    the Resident source record key of the delivery (ADR-0003)
     * @param canonicalResidentId  the canonical Resident that represents this delivery
     */
    public Outcome observe(RawRecord raw, LoadedStatePack pack, String residentRecordKey, UUID canonicalResidentId) {
        Optional<FamilyMapping> mapped;
        try {
            mapped = mapper.map(raw, pack, raw.sourceSystem());
        } catch (RuntimeException error) {
            recordFailure(raw, pack.manifest().version(), error.getMessage());
            return Outcome.FAILED;
        }
        if (mapped.isEmpty()) {
            return Outcome.NO_FAMILY_RULES;
        }
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                UUID observation = UUID.randomUUID();
                if (!insertObservation(c, observation, raw, pack, mapped.get(), residentRecordKey, canonicalResidentId)) {
                    c.rollback();
                    return Outcome.ALREADY_OBSERVED;
                }
                Map<String, Object> householdIds = householdIdentifiers(c, canonicalResidentId);
                for (String familyId : mapped.get().sourceFamilyIds()) {
                    insertMembership(c, observation, raw, familyId, mapped.get(), householdIds);
                }
                if (events != null) {
                    events.familyObserved(c, observation);
                }
                c.commit();
                return Outcome.OBSERVED;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist canonical Family observation", e);
        }
    }

    private boolean insertObservation(Connection c, UUID id, RawRecord raw, LoadedStatePack pack, FamilyMapping mapped,
                                      String residentRecordKey, UUID canonicalResidentId) throws Exception {
        String sql = "insert into canonical_family_observation(canonical_family_observation_id, raw_record_id,"
                + " batch_id, state_code, source_system, source_entity, state_pack_version, mapping_version,"
                + " resident_record_key, canonical_resident_id) values (?,?,?,?,?,?,?,?,?,?)"
                + " on conflict (raw_record_id, state_pack_version) do nothing";
        try (var s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            s.setObject(2, raw.rawRecordId());
            s.setObject(3, raw.batchId());
            s.setString(4, raw.stateCode());
            s.setString(5, raw.sourceSystem());
            s.setString(6, raw.sourceEntity());
            s.setString(7, pack.manifest().version());
            s.setString(8, mapped.mappingVersion());
            s.setString(9, residentRecordKey);
            s.setObject(10, canonicalResidentId);
            return s.executeUpdate() == 1;
        }
    }

    /**
     * The HOUSEHOLD-subject identifiers (Track E2) of the resident record, in their protected form, so the Family
     * layer can anchor households on them (Track E3).
     */
    private Map<String, Object> householdIdentifiers(Connection c, UUID canonicalResidentId) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        try (var s = c.prepareStatement("select canonical_payload->'extensions'->'"
                + gov.uddp.core.identity.IdentifierProtection.EXTENSION + "' from canonical_resident"
                + " where canonical_resident_id = ?")) {
            s.setObject(1, canonicalResidentId);
            var r = s.executeQuery();
            if (r.next() && r.getString(1) != null) {
                json.readTree(r.getString(1)).fields().forEachRemaining(entry -> {
                    if ("HOUSEHOLD".equals(entry.getValue().path("subject").asText())) {
                        out.put(entry.getKey(), entry.getValue());
                    }
                });
            }
        }
        return out;
    }

    private void insertMembership(Connection c, UUID observation, RawRecord raw, String familyId, FamilyMapping mapped,
                                  Map<String, Object> householdIds) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceFamilyId", familyId);
        payload.put("extensions", mapped.extensions());
        if (!householdIds.isEmpty()) {
            payload.put("identifiers", householdIds);
        }
        try (var s = c.prepareStatement("insert into canonical_family_membership(canonical_family_membership_id,"
                + " canonical_family_observation_id, family_record_key, source_family_id, relationship_type,"
                + " family_payload) values (?,?,?,?,?,cast(? as jsonb))")) {
            s.setObject(1, UUID.randomUUID());
            s.setObject(2, observation);
            s.setString(3, familyRecordKey(raw, familyId));
            s.setString(4, familyId);
            s.setString(5, mapped.relationshipType());
            s.setString(6, json.writeValueAsString(payload));
            s.executeUpdate();
        }
    }

    private void recordFailure(RawRecord raw, String packVersion, String message) {
        try (var c = connections.open();
                var s = c.prepareStatement("insert into canonical_family_processing_error(raw_record_id, batch_id,"
                        + " state_pack_version, error_message) values (?,?,?,?)")) {
            s.setObject(1, raw.rawRecordId());
            s.setObject(2, raw.batchId());
            s.setString(3, packVersion);
            s.setString(4, message == null ? "Family mapping failed" : message);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record Family processing error", e);
        }
    }
}
