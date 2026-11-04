package gov.uddp.core.canonical;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.mapping.ApplicationMappingEngine;
import gov.uddp.core.mapping.ApplicationMappingEngine.ApplicationMapping;
import gov.uddp.core.mapping.ApplicationMappingEngine.MappedApplication;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.statepack.LoadedStatePack;
import java.sql.Connection;
import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes the canonical Application observation of one raw record: the application the delivery names, its stable
 * platform identity (created on first sight) and, when its stage or status differs from the previous delivery of the
 * same source application, an append-only stage event. Idempotent per raw record and State Pack version. A mapping
 * failure is recorded in {@code application_processing_error} and never affects the Resident.
 */
@Service
public class CanonicalApplicationProcessor {
    private final PostgresConnectionFactory connections;
    private final ApplicationMappingEngine mapper;
    private final ObjectMapper json;

    public CanonicalApplicationProcessor(PostgresConnectionFactory connections, ApplicationMappingEngine mapper,
                                         ObjectMapper json) {
        this.connections = connections;
        this.mapper = mapper;
        this.json = json;
    }

    /** Outcome of observing one raw record. */
    public enum Outcome { OBSERVED, NO_APPLICATION, ALREADY_OBSERVED, FAILED }

    /** stateCode|sourceSystem|sourceEntity|schemeId|sourceApplicationId: an application as one source names it. */
    public static String applicationRecordKey(RawRecord raw, String schemeId, String sourceApplicationId) {
        return String.join("|", raw.stateCode(), raw.sourceSystem(), raw.sourceEntity(), schemeId, sourceApplicationId);
    }

    /**
     * @param residentRecordKey   the Resident source record key of the delivery (the applicant unless the pack maps one)
     * @param canonicalResidentId the canonical Resident that represents this delivery
     */
    public Outcome observe(RawRecord raw, LoadedStatePack pack, String residentRecordKey, UUID canonicalResidentId) {
        Optional<ApplicationMapping> mapped;
        try {
            mapped = mapper.map(raw, pack, raw.sourceSystem());
        } catch (RuntimeException error) {
            recordFailure(raw, pack.manifest().version(), error.getMessage());
            return Outcome.FAILED;
        }
        if (mapped.isEmpty()) {
            return Outcome.NO_APPLICATION;
        }
        if (mapped.get().application().isEmpty()) {
            return Outcome.NO_APPLICATION;
        }
        MappedApplication application = mapped.get().application().get();
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                String key = applicationRecordKey(raw, application.scheme().schemeId(), application.sourceApplicationId());
                UUID applicationId = applicationIdentity(c, raw, key, application);
                lock(c, applicationId);
                UUID observation = UUID.randomUUID();
                if (!insertObservation(c, observation, raw, pack, mapped.get(), application, applicationId,
                        residentRecordKey, canonicalResidentId)) {
                    c.rollback();
                    return Outcome.ALREADY_OBSERVED;
                }
                appendStageEvent(c, applicationId, observation, application);
                c.commit();
                return Outcome.OBSERVED;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist canonical Application observation", e);
        }
    }

    /** The application's platform ID: the existing one for its record key, or a new one (never reused). */
    private static UUID applicationIdentity(Connection c, RawRecord raw, String key, MappedApplication application)
            throws Exception {
        try (var s = c.prepareStatement("insert into application(application_id, application_record_key, state_code,"
                + " source_system, source_entity, scheme_id, source_application_id) values (?,?,?,?,?,?,?)"
                + " on conflict (application_record_key) do nothing")) {
            s.setObject(1, UUID.randomUUID());
            s.setString(2, key);
            s.setString(3, raw.stateCode());
            s.setString(4, raw.sourceSystem());
            s.setString(5, raw.sourceEntity());
            s.setString(6, application.scheme().schemeId());
            s.setString(7, application.sourceApplicationId());
            s.executeUpdate();
        }
        try (var s = c.prepareStatement("select application_id from application where application_record_key = ?")) {
            s.setString(1, key);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getObject(1, UUID.class);
            }
        }
    }

    /** Serialises concurrent deliveries of one application so each compares with the right previous stage. */
    private static void lock(Connection c, UUID applicationId) throws Exception {
        try (var s = c.prepareStatement("select 1 from application where application_id = ? for update")) {
            s.setObject(1, applicationId);
            s.executeQuery().close();
        }
    }

    private boolean insertObservation(Connection c, UUID id, RawRecord raw, LoadedStatePack pack,
                                      ApplicationMapping mapped, MappedApplication a, UUID applicationId,
                                      String residentRecordKey, UUID canonicalResidentId) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("extensions", a.extensions());
        String sql = "insert into application_observation(application_observation_id, raw_record_id, batch_id,"
                + " state_code, source_system, source_entity, state_pack_version, mapping_version,"
                + " scheme_catalogue_version, application_id, source_scheme_code, scheme_id, source_application_id,"
                + " applicant_source_record_key, canonical_resident_id, submitted_on, current_stage, current_status,"
                + " next_stage, last_action_date, attributes, metadata)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb))"
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
            s.setString(9, mapped.catalogueVersion());
            s.setObject(10, applicationId);
            s.setString(11, a.sourceSchemeCode());
            s.setString(12, a.scheme().schemeId());
            s.setString(13, a.sourceApplicationId());
            s.setString(14, a.applicantSourceRecordKey() == null ? residentRecordKey : a.applicantSourceRecordKey());
            s.setObject(15, canonicalResidentId);
            s.setObject(16, a.submittedOn() == null ? null : Date.valueOf(a.submittedOn()));
            s.setString(17, a.currentStage());
            s.setString(18, a.currentStatus());
            s.setString(19, a.nextStage());
            s.setObject(20, a.lastActionDate() == null ? null : Date.valueOf(a.lastActionDate()));
            s.setString(21, json.writeValueAsString(a.attributes()));
            s.setString(22, json.writeValueAsString(metadata));
            return s.executeUpdate() == 1;
        }
    }

    /** Appends an event when this delivery's stage or status is new for the application (always for the first). */
    private static void appendStageEvent(Connection c, UUID applicationId, UUID observation, MappedApplication a)
            throws Exception {
        String previousStage = null;
        String previousStatus = null;
        try (var s = c.prepareStatement("select stage, status from application_stage_event where application_id = ?"
                + " order by recorded_at desc, application_stage_event_id desc limit 1")) {
            s.setObject(1, applicationId);
            try (var r = s.executeQuery()) {
                if (r.next()) {
                    previousStage = r.getString(1);
                    previousStatus = r.getString(2);
                }
            }
        }
        if (a.currentStage().equals(previousStage) && a.currentStatus().equals(previousStatus)) {
            return;
        }
        try (var s = c.prepareStatement("insert into application_stage_event(application_stage_event_id,"
                + " application_id, application_observation_id, stage, status, previous_stage, previous_status,"
                + " occurred_on) values (?,?,?,?,?,?,?,?)")) {
            s.setObject(1, UUID.randomUUID());
            s.setObject(2, applicationId);
            s.setObject(3, observation);
            s.setString(4, a.currentStage());
            s.setString(5, a.currentStatus());
            s.setString(6, previousStage);
            s.setString(7, previousStatus);
            s.setObject(8, a.lastActionDate() == null ? null : Date.valueOf(a.lastActionDate()));
            s.executeUpdate();
        }
    }

    private void recordFailure(RawRecord raw, String packVersion, String message) {
        try (var c = connections.open();
                var s = c.prepareStatement("insert into application_processing_error(raw_record_id, batch_id,"
                        + " state_pack_version, error_message) values (?,?,?,?)")) {
            s.setObject(1, raw.rawRecordId());
            s.setObject(2, raw.batchId());
            s.setString(3, packVersion);
            s.setString(4, message == null ? "Application mapping failed" : message);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record Application processing error", e);
        }
    }
}
