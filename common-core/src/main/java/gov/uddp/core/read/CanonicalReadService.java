package gov.uddp.core.read;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.read.BatchReadService.BatchSummary;
import gov.uddp.core.read.BatchReadService.DqFinding;
import gov.uddp.core.read.statepack.StatePackQueryService;
import gov.uddp.core.security.AuditContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Canonical Residents with lineage to their raw record and batch, version chains and validation results. */
@Service
public class CanonicalReadService {
    public record CanonicalResidentSummary(UUID canonicalResidentId, String stateCode, String sourceSystem,
                                           String sourceEntity, UUID batchId, UUID rawRecordId,
                                           String statePackVersion, String sourceRecordKey, int versionNumber,
                                           boolean current, String name, String dateOfBirth, String gender,
                                           String mobile, String districtLGD, UUID goldenResidentId,
                                           OffsetDateTime createdAt) {
    }

    /** One mapping rule applied to this record: the raw source value and the canonical value it produced. */
    public record FieldMapping(String sourceField, String sourceValue, String target, JsonNode canonicalValue,
                               String conversion, String crosswalk, boolean required) {
    }

    public record SourceField(String name, String value) {
    }

    public record ValidationWarningView(String fieldPath, String rule, String message, OffsetDateTime occurredAt) {
    }

    public record CanonicalVersionView(UUID canonicalResidentId, int versionNumber, UUID batchId, UUID rawRecordId,
                                       String statePackVersion, OffsetDateTime createdAt, boolean current) {
    }

    public record CanonicalResidentDetail(CanonicalResidentSummary summary, JsonNode payload, JsonNode extensions,
                                          List<FieldMapping> fieldMappings, List<SourceField> sourceFields,
                                          RawRecordRef raw, BatchSummary batch, List<ValidationWarningView> warnings,
                                          List<DqFinding> dqFindings, List<CanonicalVersionView> versions) {
    }

    public record RawRecordRef(UUID rawRecordId, String sourceRecordId, OffsetDateTime ingestedAt,
                               String pipelineTemplate) {
    }

    public record RawCanonicalRef(UUID canonicalResidentId, int versionNumber, boolean current, String statePackVersion) {
    }

    public record RawRecordDetail(UUID rawRecordId, UUID batchId, String stateCode, String sourceSystem,
                                  String sourceRecordId, OffsetDateTime ingestedAt, String statePackVersion,
                                  String sourceSchemaVersion, String pipelineTemplate, String rawPayload,
                                  JsonNode sourceMetadata, List<SourceField> fields, String parseError,
                                  List<RawCanonicalRef> canonicalResidents, List<String> processingErrors) {
    }

    private static final Map<String, String> SORT = Map.of(
            "name", "c.canonical_payload->>'name'", "createdAt", "c.created_at", "stateCode", "c.state_code",
            "sourceSystem", "c.source_system");
    /** Canonical rows with their version (latest = nothing supersedes it) and current Golden membership. */
    private static final String FROM = " from canonical_resident c"
            + " left join canonical_resident_version v on v.canonical_resident_id = c.canonical_resident_id"
            + " left join golden_resident_membership m on m.canonical_resident_id = c.canonical_resident_id"
            + " and m.effective_to is null"
            + " where c.canonical_entity_type = 'Resident'";
    private static final String COLUMNS = "select c.*, coalesce(v.source_record_key, 'canonical:' || c.canonical_resident_id)"
            + " as source_record_key, coalesce(v.version_number, 1) as version_number,"
            + " not exists (select 1 from canonical_resident_version s where s.supersedes_canonical_id"
            + " = c.canonical_resident_id) as is_current, m.golden_resident_id, c.canonical_payload::text as payload_text";

    private final Db db;
    private final StatePackQueryService packs;
    private final BatchReadService batches;
    private final RawCsvRecordParser parser;

    public CanonicalReadService(Db db, StatePackQueryService packs, BatchReadService batches,
                                RawCsvRecordParser parser) {
        this.db = db;
        this.packs = packs;
        this.batches = batches;
        this.parser = parser;
    }

    public Page<CanonicalResidentSummary> list(String state, UUID batchId, String name, boolean includeSuperseded,
                                               Integer page, Integer size, String sort) {
        var query = PageQuery.of(page, size, sort, SORT, "name,asc", "c.canonical_resident_id");
        String code = batches.stateCode(state);
        String search = name == null || name.isBlank() ? null : "%" + escapeLike(name.strip()) + "%";
        String where = " and (cast(? as text) is null or c.state_code = cast(? as text))"
                + " and (cast(? as uuid) is null or c.batch_id = cast(? as uuid))"
                + " and (cast(? as text) is null or c.canonical_payload->>'name' ilike cast(? as text))"
                + " and (? or not exists (select 1 from canonical_resident_version s"
                + " where s.supersedes_canonical_id = c.canonical_resident_id))" + Db.inScope("c.state_code");
        Object[] parameters = Db.withScope(code, code, batchId, batchId, search, search, includeSuperseded);
        long total = db.count("select count(*)" + FROM + where, parameters);
        var items = db.list(COLUMNS + FROM + where + query.sql(), this::summary, parameters);
        return Page.of(items, query, total);
    }

    public CanonicalResidentDetail detail(UUID id) {
        var row = db.one(COLUMNS + FROM + " and c.canonical_resident_id = ?",
                        r -> Map.entry(summary(r), r.getString("payload_text")), id)
                .orElseThrow(() -> new ReadNotFoundException("Canonical Resident does not exist: " + id));
        var summary = row.getKey();
        Db.requireInScope(summary.stateCode(), "Canonical Resident does not exist: " + id);
        AuditContext.state(summary.stateCode());
        JsonNode payload = db.json(row.getValue());
        var raw = raw(summary.rawRecordId());
        var fieldMappings = mappings(summary, payload, raw.fields());
        var warnings = db.list("select field_path, rule, message, occurred_at from canonical_validation_warning"
                        + " where canonical_resident_id = ? order by occurred_at, canonical_validation_warning_id",
                r -> new ValidationWarningView(r.getString("field_path"), r.getString("rule"), r.getString("message"),
                        Db.time(r, "occurred_at")), id);
        var findings = db.list("select r.*, e.batch_id, e.state_code, e.source_system from dq_result r"
                        + " join dq_batch_evaluation e on e.dq_evaluation_id = r.dq_evaluation_id"
                        + " where r.canonical_resident_id = ? or r.raw_record_id = ? order by r.evaluated_at, r.dq_result_id",
                r -> new DqFinding(Db.uuid(r, "dq_result_id"), Db.uuid(r, "batch_id"), r.getString("state_code"),
                        r.getString("source_system"), Db.uuid(r, "raw_record_id"), Db.uuid(r, "canonical_resident_id"),
                        r.getString("rule_id"), r.getString("rule_type"), r.getString("severity"),
                        r.getString("field_path"), r.getString("message"), Db.time(r, "evaluated_at")),
                id, summary.rawRecordId());
        var versions = db.list("select c.canonical_resident_id, coalesce(v.version_number, 1) as version_number,"
                        + " c.batch_id, c.raw_record_id, c.state_pack_version, c.created_at,"
                        + " not exists (select 1 from canonical_resident_version s"
                        + " where s.supersedes_canonical_id = c.canonical_resident_id) as is_current"
                        + " from canonical_resident c"
                        + " left join canonical_resident_version v on v.canonical_resident_id = c.canonical_resident_id"
                        + " where coalesce(v.source_record_key, 'canonical:' || c.canonical_resident_id) = ?"
                        + " order by version_number",
                r -> new CanonicalVersionView(Db.uuid(r, "canonical_resident_id"), r.getInt("version_number"),
                        Db.uuid(r, "batch_id"), Db.uuid(r, "raw_record_id"), r.getString("state_pack_version"),
                        Db.time(r, "created_at"), r.getBoolean("is_current")),
                summary.sourceRecordKey());
        JsonNode extensions = payload == null ? null : payload.get("extensions");
        return new CanonicalResidentDetail(summary, payload, extensions, fieldMappings, raw.fields(),
                new RawRecordRef(raw.rawRecordId(), raw.sourceRecordId(), raw.ingestedAt(), raw.pipelineTemplate()),
                batches.batch(summary.batchId()).batch(), warnings, findings, versions);
    }

    public RawRecordDetail raw(UUID id) {
        var detail = db.one("select * from raw_record where raw_record_id = ?", r -> {
            String payload = r.getString("raw_payload");
            List<SourceField> fields = new ArrayList<>();
            String parseError = null;
            try {
                parser.parse(payload).forEach((name, value) -> fields.add(new SourceField(name,
                        value == null ? null : value.toString())));
            } catch (RuntimeException e) {
                parseError = e.getMessage();
            }
            return new RawRecordDetail(Db.uuid(r, "raw_record_id"), Db.uuid(r, "batch_id"), r.getString("state_code"),
                    r.getString("source_system"), r.getString("source_record_id"), Db.time(r, "ingested_at"),
                    r.getString("state_pack_version"), r.getString("source_schema_version"),
                    r.getString("pipeline_template"), payload, db.json(r.getString("source_metadata")), fields,
                    parseError, List.of(), List.of());
        }, id).orElseThrow(() -> new ReadNotFoundException("Raw record does not exist: " + id));
        Db.requireInScope(detail.stateCode(), "Raw record does not exist: " + id);
        AuditContext.state(detail.stateCode());
        var canonicals = db.list("select c.canonical_resident_id, coalesce(v.version_number, 1) as version_number,"
                        + " c.state_pack_version, not exists (select 1 from canonical_resident_version s"
                        + " where s.supersedes_canonical_id = c.canonical_resident_id) as is_current"
                        + " from canonical_resident c"
                        + " left join canonical_resident_version v on v.canonical_resident_id = c.canonical_resident_id"
                        + " where c.raw_record_id = ? order by c.created_at",
                r -> new RawCanonicalRef(Db.uuid(r, "canonical_resident_id"), r.getInt("version_number"),
                        r.getBoolean("is_current"), r.getString("state_pack_version")), id);
        var errors = db.list("select error_message from canonical_processing_error where raw_record_id = ?"
                + " order by occurred_at", r -> r.getString(1), id);
        return new RawRecordDetail(detail.rawRecordId(), detail.batchId(), detail.stateCode(), detail.sourceSystem(),
                detail.sourceRecordId(), detail.ingestedAt(), detail.statePackVersion(), detail.sourceSchemaVersion(),
                detail.pipelineTemplate(), detail.rawPayload(), detail.sourceMetadata(), detail.fields(),
                detail.parseError(), canonicals, errors);
    }

    /** The pack's Resident mapping rules for this record's source, each with its source and canonical value. */
    private List<FieldMapping> mappings(CanonicalResidentSummary summary, JsonNode payload, List<SourceField> fields) {
        Map<String, String> source = new LinkedHashMap<>();
        fields.forEach(field -> source.put(field.name(), field.value()));
        List<FieldMapping> out = new ArrayList<>();
        packs.find(summary.stateCode()).ifPresent(pack -> pack.mappings().stream()
                .filter(mapping -> mapping.sourceId().equals(summary.sourceSystem()))
                .filter(mapping -> mapping.entity().equals("Resident"))
                .forEach(mapping -> mapping.rules().forEach(rule -> out.add(new FieldMapping(rule.sourceField(),
                        source.get(rule.sourceField()), rule.target(), at(payload, rule.target()), rule.conversion(),
                        rule.crosswalk(), rule.required())))));
        return out;
    }

    static JsonNode at(JsonNode node, String path) {
        if (node == null || path == null) {
            return null;
        }
        for (String part : path.replace("[]", "").split("\\.")) {
            node = node == null ? null : node.get(part);
        }
        return node;
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private CanonicalResidentSummary summary(ResultSet r) throws SQLException {
        JsonNode payload = db.json(r.getString("payload_text"));
        return new CanonicalResidentSummary(Db.uuid(r, "canonical_resident_id"), r.getString("state_code"),
                r.getString("source_system"), r.getString("source_entity"), Db.uuid(r, "batch_id"),
                Db.uuid(r, "raw_record_id"), r.getString("state_pack_version"), r.getString("source_record_key"),
                r.getInt("version_number"), r.getBoolean("is_current"), text(payload, "name"),
                text(payload, "dateOfBirth"), text(payload, "gender"), text(payload, "mobile"),
                text(payload, "address.districtLGD"), Db.uuid(r, "golden_resident_id"), Db.time(r, "created_at"));
    }

    static String text(JsonNode payload, String path) {
        JsonNode value = at(payload, path);
        return value == null || value.isNull() ? null : value.asText();
    }
}
