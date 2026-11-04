package gov.uddp.core.read;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.scheme.SchemeCatalogue;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Applications: each source application with its platform scheme, present stage and status, stage history and
 * observations (lineage to raw records); and a Golden Resident's applications across states. The Golden link is
 * resolved from the current Golden membership of the applicant's source record on every read. Read-only.
 */
@Service
public class ApplicationReadService {
    /** An application and its present statement (its latest observation). */
    public record ApplicationSummary(UUID applicationId, String applicationRecordKey, String schemeId,
                                     String schemeName, String departmentCode, String stateCode, String sourceSystem,
                                     String sourceSchemeCode, String sourceApplicationId,
                                     String applicantSourceRecordKey, LocalDate submittedOn, String currentStage,
                                     String currentStatus, String nextStage, LocalDate lastActionDate,
                                     JsonNode attributes, OffsetDateTime createdAt, OffsetDateTime observedAt) {
    }

    public record StageEventView(UUID applicationStageEventId, String stage, String status, String previousStage,
                                 String previousStatus, LocalDate occurredOn, OffsetDateTime recordedAt,
                                 UUID applicationObservationId, UUID rawRecordId) {
    }

    public record ApplicationObservationView(UUID applicationObservationId, OffsetDateTime observedAt,
                                             String currentStage, String currentStatus, String nextStage,
                                             LocalDate lastActionDate, JsonNode metadata, UUID rawRecordId,
                                             UUID batchId, UUID canonicalResidentId, String statePackVersion,
                                             String mappingVersion, String schemeCatalogueVersion) {
    }

    /** One application with its applicant's Golden Resident (null before a Golden build), history and lineage. */
    public record ApplicationDetail(ApplicationSummary application, UUID goldenResidentId,
                                    List<StageEventView> stageEvents, List<ApplicationObservationView> observations) {
    }

    /** A Golden Resident's applications. */
    public record ResidentApplications(UUID goldenResidentId, String status, int applicationCount,
                                       List<ApplicationSummary> applications) {
    }

    private static final String SUMMARY = "select s.application_id, s.application_record_key, s.scheme_id,"
            + " s.state_code, s.source_system, s.source_scheme_code, s.source_application_id,"
            + " s.applicant_source_record_key, s.submitted_on, s.current_stage, s.current_status, s.next_stage,"
            + " s.last_action_date, s.attributes::text as attributes_text, s.created_at, s.observed_at"
            + " from application_state s";

    private final Db db;
    private final SchemeCatalogue catalogue;
    private final GoldenVisibility goldens;

    public ApplicationReadService(Db db, SchemeCatalogue catalogue, GoldenVisibility goldens) {
        this.db = db;
        this.catalogue = catalogue;
        this.goldens = goldens;
    }

    public ApplicationDetail detail(UUID applicationId) {
        var application = db.one(SUMMARY + " where s.application_id = ?", this::summary, applicationId)
                .orElseThrow(() -> new ReadNotFoundException("Application does not exist: " + applicationId));
        Db.requireInScope(application.stateCode(), "Application does not exist: " + applicationId);
        UUID golden = db.one("select m.golden_resident_id from golden_resident_membership m"
                        + " where m.source_record_key = ? and m.effective_to is null",
                r -> Db.uuid(r, "golden_resident_id"), application.applicantSourceRecordKey()).orElse(null);
        var events = db.list("select e.application_stage_event_id, e.stage, e.status, e.previous_stage,"
                        + " e.previous_status, e.occurred_on, e.recorded_at, e.application_observation_id,"
                        + " o.raw_record_id from application_stage_event e"
                        + " join application_observation o on o.application_observation_id = e.application_observation_id"
                        + " where e.application_id = ? order by e.recorded_at, e.application_stage_event_id",
                r -> new StageEventView(Db.uuid(r, "application_stage_event_id"), r.getString("stage"),
                        r.getString("status"), r.getString("previous_stage"), r.getString("previous_status"),
                        BenefitReadService.date(r, "occurred_on"), Db.time(r, "recorded_at"),
                        Db.uuid(r, "application_observation_id"), Db.uuid(r, "raw_record_id")),
                applicationId);
        var observations = db.list("select o.application_observation_id, o.observed_at, o.current_stage,"
                        + " o.current_status, o.next_stage, o.last_action_date, o.metadata::text, o.raw_record_id,"
                        + " o.batch_id, o.canonical_resident_id, o.state_pack_version, o.mapping_version,"
                        + " o.scheme_catalogue_version from application_observation o where o.application_id = ?"
                        + " order by o.observed_at, o.application_observation_id",
                r -> new ApplicationObservationView(Db.uuid(r, "application_observation_id"),
                        Db.time(r, "observed_at"), r.getString("current_stage"), r.getString("current_status"),
                        r.getString("next_stage"), BenefitReadService.date(r, "last_action_date"),
                        db.json(r.getString(7)), Db.uuid(r, "raw_record_id"), Db.uuid(r, "batch_id"),
                        Db.uuid(r, "canonical_resident_id"), r.getString("state_pack_version"),
                        r.getString("mapping_version"), r.getString("scheme_catalogue_version")),
                applicationId);
        return new ApplicationDetail(application, golden, events, observations);
    }

    /** A Golden Resident's applications across states; 404 for an unknown Golden ID. */
    public ResidentApplications applicationsOf(UUID goldenResidentId) {
        String status = db.one("select g.status from golden_resident g where g.golden_resident_id = ?",
                r -> r.getString("status"), goldenResidentId)
                .orElseThrow(() -> new ReadNotFoundException("Golden Resident does not exist: " + goldenResidentId));
        goldens.requireVisible(goldenResidentId);
        var applications = db.list(SUMMARY + " where exists (select 1 from golden_resident_membership m"
                        + " where m.golden_resident_id = ? and m.effective_to is null"
                        + " and m.source_record_key = s.applicant_source_record_key)"
                        + Db.inScope("s.state_code") + " order by s.submitted_on, s.application_id",
                this::summary, Db.withScope(goldenResidentId));
        return new ResidentApplications(goldenResidentId, status, applications.size(), applications);
    }

    private ApplicationSummary summary(java.sql.ResultSet r) throws Exception {
        String schemeId = r.getString("scheme_id");
        var scheme = catalogue.scheme(schemeId);
        return new ApplicationSummary(Db.uuid(r, "application_id"), r.getString("application_record_key"), schemeId,
                scheme.map(SchemeCatalogue.Scheme::name).orElse(null),
                scheme.map(SchemeCatalogue.Scheme::department).orElse(null), r.getString("state_code"),
                r.getString("source_system"), r.getString("source_scheme_code"), r.getString("source_application_id"),
                r.getString("applicant_source_record_key"), BenefitReadService.date(r, "submitted_on"),
                r.getString("current_stage"), r.getString("current_status"), r.getString("next_stage"),
                BenefitReadService.date(r, "last_action_date"), db.json(r.getString("attributes_text")),
                Db.time(r, "created_at"), Db.time(r, "observed_at"));
    }
}
