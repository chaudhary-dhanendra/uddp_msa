package gov.uddp.core.read;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.security.AuditContext;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.security.StateScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Golden Residents for search and the Golden 360 view: current values with per-field provenance, members with their
 * lineage to raw records, versions with value differences, and lifecycle events. Read-only.
 */
@Service
public class GoldenReadService {
    private static final int MAX_MERGE_CHAIN = 100;

    /**
     * A search result. For a user who does not see every state, {@code stateCodes} lists only the states in scope and
     * values that survived from a member outside the scope are null and named in {@code withheldFields} (ADR-0019).
     */
    public record GoldenResidentSummary(UUID goldenResidentId, String status, int versionNumber, String name,
                                        String dateOfBirth, String gender, String mobile, String districtLGD,
                                        int memberCount, List<String> stateCodes, UUID mergedIntoId,
                                        OffsetDateTime createdAt, OffsetDateTime updatedAt,
                                        List<String> withheldFields) {
        GoldenResidentSummary scoped(Set<String> withheld, List<String> visibleStates) {
            return new GoldenResidentSummary(goldenResidentId, status, versionNumber,
                    GoldenVisibility.isWithheld(withheld, "name") ? null : name,
                    GoldenVisibility.isWithheld(withheld, "dateOfBirth") ? null : dateOfBirth,
                    GoldenVisibility.isWithheld(withheld, "gender") ? null : gender,
                    GoldenVisibility.isWithheld(withheld, "mobile") ? null : mobile,
                    GoldenVisibility.isWithheld(withheld, "address.districtLGD") ? null : districtLGD,
                    memberCount, visibleStates, mergedIntoId, createdAt, updatedAt, List.copyOf(withheld));
        }
    }

    /** A canonical record behind a Golden value, with its state, source and the value it holds for the field. */
    public record ContributorView(UUID canonicalResidentId, String stateCode, String sourceSystem, JsonNode value,
                                  boolean selected) {
    }

    /**
     * One surviving field: the value, the rule that chose it, the policy version and the winning record (by
     * {@code selectedCanonicalResidentId}, with its state, source, raw record and batch) among all contributors.
     */
    public record GoldenFieldView(String fieldPath, JsonNode value, String rule, String survivorshipPolicyVersion,
                                  UUID selectedCanonicalResidentId, String selectedStateCode, String selectedSourceSystem,
                                  UUID selectedRawRecordId, UUID selectedBatchId, List<ContributorView> contributors,
                                  boolean withheld) {
    }

    public record GoldenMemberView(UUID canonicalResidentId, String stateCode, String sourceSystem,
                                   String sourceRecordKey, UUID rawRecordId, UUID batchId, int canonicalVersion,
                                   boolean canonicalCurrent, String name, int addedInVersion,
                                   OffsetDateTime memberSince) {
    }

    public record GoldenVersionSummary(int versionNumber, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                       List<String> changeReasons, UUID matchingRunId, UUID goldenBuildId,
                                       String survivorshipPolicyVersion) {
    }

    /**
     * The Golden 360 view. For a retired ID the values and members are those of its last version, and
     * {@code redirectTo} is the active identity its merge chain resolves to (null for an active ID).
     */
    public record GoldenResident360(UUID goldenResidentId, String status, OffsetDateTime createdAt,
                                    OffsetDateTime retiredAt, UUID mergedIntoId, UUID splitFromId,
                                    UUID resolvedGoldenResidentId, UUID redirectTo, Integer currentVersion,
                                    int latestVersion, UUID matchingRunId, UUID goldenBuildId,
                                    String survivorshipPolicyVersion, JsonNode payload, List<GoldenFieldView> fields,
                                    List<GoldenMemberView> members, List<GoldenVersionSummary> versions,
                                    List<String> stateCodes, int hiddenMemberCount, List<String> withheldFields,
                                    String uddpId, String redirectToUddpId) {
    }

    public record ValueChange(String fieldPath, JsonNode before, JsonNode after) {
    }

    public record VersionMember(UUID canonicalResidentId, String sourceRecordKey, String stateCode) {
    }

    /** One version with its values, provenance, members and the value changes since the previous version. */
    public record GoldenVersionView(int versionNumber, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                    List<String> changeReasons, UUID matchingRunId, UUID goldenBuildId,
                                    String survivorshipPolicyVersion, JsonNode payload, List<GoldenFieldView> fields,
                                    List<VersionMember> members, List<ValueChange> changes) {
    }

    public record GoldenEventView(long sequence, String eventType, Integer versionNumber, UUID goldenBuildId,
                                  UUID relatedGoldenResidentId, UUID canonicalResidentId, String sourceRecordKey,
                                  String fieldPath, JsonNode oldValue, JsonNode newValue, JsonNode detail,
                                  OffsetDateTime occurredAt) {
    }

    public record GoldenResidentHistory(UUID goldenResidentId, String status, UUID resolvedGoldenResidentId,
                                        List<GoldenVersionView> versions, List<GoldenEventView> events) {
    }

    private static final Map<String, String> SORT = Map.of(
            "name", "v.golden_payload->>'name'", "updatedAt", "v.effective_from", "createdAt", "g.created_at",
            "memberCount", "member_count", "versionNumber", "v.version_number");
    private static final String LATEST_VERSION = " join lateral (select * from golden_resident_version x"
            + " where x.golden_resident_id = g.golden_resident_id order by x.version_number desc limit 1) v on true";
    /** Memberships of version {@code v} (alias m): added at or before it and not removed by it. */
    private static final String MEMBER_OF_VERSION = "m.golden_resident_id = g.golden_resident_id"
            + " and m.added_in_version <= v.version_number"
            + " and (m.removed_in_version is null or m.removed_in_version > v.version_number)";
    private static final String SUMMARY_COLUMNS = "select g.golden_resident_id, g.status, g.created_at, g.merged_into_id,"
            + " v.version_number, v.effective_from, v.golden_payload::text as payload_text,"
            + " (select count(*) from golden_resident_membership m where " + MEMBER_OF_VERSION + ") as member_count,"
            + " (select string_agg(distinct c.state_code, ',' order by c.state_code) from golden_resident_membership m"
            + " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id"
            + " where " + MEMBER_OF_VERSION + ") as state_codes"
            + " from golden_resident g" + LATEST_VERSION;

    private final Db db;
    private final BatchReadService batches;
    private final GoldenVisibility goldens;

    public GoldenReadService(Db db, BatchReadService batches, GoldenVisibility goldens) {
        this.db = db;
        this.batches = batches;
        this.goldens = goldens;
    }

    /**
     * @param q        name contains (case-insensitive)
     * @param mobile   digits the mobile number contains
     * @param district exact LGD district code
     * @param state    pack ID or state code of at least one member
     * @param status   ACTIVE (default), RETIRED or ALL
     */
    public Page<GoldenResidentSummary> list(String q, String mobile, String district, String state, String status,
                                            Integer page, Integer size, String sort) {
        var query = PageQuery.of(page, size, sort, SORT, "name,asc", "g.golden_resident_id");
        String s = status == null || status.isBlank() ? "ACTIVE" : status.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "RETIRED", "ALL").contains(s)) {
            throw new IllegalArgumentException("status must be ACTIVE, RETIRED or ALL");
        }
        String statusFilter = s.equals("ALL") ? null : s;
        String name = q == null || q.isBlank() ? null : "%" + CanonicalReadService.escapeLike(q.strip()) + "%";
        String digits = mobile == null ? "" : mobile.replaceAll("\\D", "");
        String phone = digits.isEmpty() ? null : "%" + digits + "%";
        String lgd = district == null || district.isBlank() ? null : district.strip();
        String code = batches.stateCode(state);
        String where = " where (cast(? as text) is null or g.status = cast(? as text))"
                + " and (cast(? as text) is null or v.golden_payload->>'name' ilike cast(? as text))"
                + " and (cast(? as text) is null or regexp_replace(coalesce(v.golden_payload->>'mobile', ''), '\\D', '', 'g')"
                + " like cast(? as text))"
                + " and (cast(? as text) is null or v.golden_payload#>>'{address,districtLGD}' = cast(? as text))"
                + " and (cast(? as text) is null or exists (select 1 from golden_resident_membership m"
                + " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id"
                + " where " + MEMBER_OF_VERSION + " and c.state_code = cast(? as text)))"
                + GoldenVisibility.visible("g");
        Object[] parameters = Db.withScope(statusFilter, statusFilter, name, name, phone, phone, lgd, lgd, code, code);
        long total = db.count("select count(*) from golden_resident g" + LATEST_VERSION + where, parameters);
        var items = db.list(SUMMARY_COLUMNS + where + query.sql(), this::summary, parameters);
        StateScope scope = CurrentUser.scope();
        if (!scope.all()) {
            var withheld = goldens.withheldFields(items.stream().map(GoldenResidentSummary::goldenResidentId).toList());
            items = items.stream().map(item -> item.scoped(withheld.getOrDefault(item.goldenResidentId(), Set.of()),
                    item.stateCodes().stream().filter(scope::allows).toList())).toList();
        }
        return Page.of(items, query, total);
    }

    public GoldenResident360 detail(UUID id) {
        var identity = identity(id);
        goldens.requireVisible(id);
        StateScope scope = CurrentUser.scope();
        var versions = versions(id);
        var latest = versions.getLast();
        var fields = scoped(fields(latest.id()), scope);
        Set<String> withheld = withheld(fields);
        var members = db.list("select m.canonical_resident_id, m.source_record_key, m.added_in_version, m.effective_from,"
                        + " c.state_code, c.source_system, c.raw_record_id, c.batch_id, c.canonical_payload->>'name' as name,"
                        + " coalesce(cv.version_number, 1) as canonical_version,"
                        + " not exists (select 1 from canonical_resident_version s"
                        + " where s.supersedes_canonical_id = c.canonical_resident_id) as is_current"
                        + " from golden_resident_membership m"
                        + " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id"
                        + " left join canonical_resident_version cv on cv.canonical_resident_id = c.canonical_resident_id"
                        + " where m.golden_resident_id = ? and m.added_in_version <= ?"
                        + " and (m.removed_in_version is null or m.removed_in_version > ?)"
                        + " order by c.state_code, m.canonical_resident_id",
                r -> new GoldenMemberView(Db.uuid(r, "canonical_resident_id"), r.getString("state_code"),
                        r.getString("source_system"), r.getString("source_record_key"), Db.uuid(r, "raw_record_id"),
                        Db.uuid(r, "batch_id"), r.getInt("canonical_version"), r.getBoolean("is_current"),
                        r.getString("name"), r.getInt("added_in_version"), Db.time(r, "effective_from")),
                id, latest.number(), latest.number());
        boolean active = "ACTIVE".equals(identity.status());
        UUID resolved = resolve(id);
        var visibleMembers = members.stream().filter(member -> scope.allows(member.stateCode())).toList();
        var states = new TreeSet<String>();
        visibleMembers.forEach(member -> states.add(member.stateCode()));
        AuditContext.state(states.isEmpty() ? null : states.first());
        return new GoldenResident360(id, identity.status(), identity.createdAt(), identity.retiredAt(),
                identity.mergedIntoId(), identity.splitFromId(), resolved,
                !active && !resolved.equals(id) ? resolved : null, active ? latest.number() : null, latest.number(),
                latest.matchingRunId(), latest.buildId(), latest.policyVersion(), without(latest.payload(), withheld),
                fields, visibleMembers,
                versions.stream().map(v -> new GoldenVersionSummary(v.number(), v.from(), v.to(), v.reasons(),
                        v.matchingRunId(), v.buildId(), v.policyVersion())).toList(),
                List.copyOf(states), members.size() - visibleMembers.size(), List.copyOf(withheld),
                uddpId("RESIDENT", id), !active && !resolved.equals(id) ? uddpId("RESIDENT", resolved) : null);
    }

    /** The Golden 360 of the identity a UDDP ID names (Track E4); a retired one carries redirectTo(UddpId). */
    public GoldenResident360 byUddpId(String uddpId) {
        return detail(goldenIdOf(db, "RESIDENT", uddpId));
    }

    /** The UDDP ID minted for a Golden identity (Track E4), or null before the first build that minted it. */
    String uddpId(String kind, UUID goldenId) {
        return uddpIdOf(db, kind, goldenId);
    }

    static String uddpIdOf(Db db, String kind, UUID goldenId) {
        return db.one("select uddp_id from uddp_id where kind = ? and golden_id = ?", r -> r.getString(1), kind, goldenId)
                .orElse(null);
    }

    static UUID goldenIdOf(Db db, String kind, String uddpId) {
        return db.one("select golden_id from uddp_id where kind = ? and uddp_id = ?", r -> Db.uuid(r, "golden_id"),
                        kind, uddpId == null ? "" : uddpId.strip())
                .orElseThrow(() -> new ReadNotFoundException("Unknown UDDP ID"));
    }

    public GoldenResidentHistory history(UUID id) {
        var identity = identity(id);
        goldens.requireVisible(id);
        StateScope scope = CurrentUser.scope();
        List<GoldenVersionView> out = new ArrayList<>();
        Map<Integer, Set<String>> withheldByVersion = new LinkedHashMap<>();
        JsonNode previous = null;
        for (var version : versions(id)) {
            var members = db.list("select m.canonical_resident_id, m.source_record_key, c.state_code"
                            + " from golden_resident_membership m"
                            + " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id"
                            + " where m.golden_resident_id = ? and m.added_in_version <= ?"
                            + " and (m.removed_in_version is null or m.removed_in_version > ?)"
                            + " order by c.state_code, m.canonical_resident_id",
                    r -> new VersionMember(Db.uuid(r, "canonical_resident_id"), r.getString("source_record_key"),
                            r.getString("state_code")),
                    id, version.number(), version.number());
            var fields = scoped(fields(version.id()), scope);
            Set<String> withheld = withheld(fields);
            withheldByVersion.put(version.number(), withheld);
            JsonNode payload = without(version.payload(), withheld);
            out.add(new GoldenVersionView(version.number(), version.from(), version.to(), version.reasons(),
                    version.matchingRunId(), version.buildId(), version.policyVersion(), payload, fields,
                    members.stream().filter(member -> scope.allows(member.stateCode())).toList(),
                    changes(previous, payload)));
            previous = payload;
        }
        Set<UUID> outOfScope = scope.all() ? Set.of() : new LinkedHashSet<>(db.list("select m.canonical_resident_id"
                + " from golden_resident_membership m join canonical_resident c"
                + " on c.canonical_resident_id = m.canonical_resident_id where m.golden_resident_id = ?"
                + " and not (c.state_code = any(cast(? as text[])))",
                r -> Db.uuid(r, "canonical_resident_id"), id, (Object) scope.sqlCodes()));
        var events = db.list("select event_sequence, event_type, version_number, golden_build_id,"
                        + " related_golden_resident_id, canonical_resident_id, source_record_key, field_path,"
                        + " old_value::text as old_text, new_value::text as new_text, detail::text as detail_text, occurred_at"
                        + " from golden_resident_event where golden_resident_id = ? order by event_sequence",
                r -> new GoldenEventView(r.getLong("event_sequence"), r.getString("event_type"),
                        Db.integer(r, "version_number"), Db.uuid(r, "golden_build_id"),
                        Db.uuid(r, "related_golden_resident_id"), Db.uuid(r, "canonical_resident_id"),
                        r.getString("source_record_key"), r.getString("field_path"), db.json(r.getString("old_text")),
                        db.json(r.getString("new_text")), db.json(r.getString("detail_text")), Db.time(r, "occurred_at")),
                id);
        if (!scope.all()) {
            events = events.stream()
                    .filter(event -> event.canonicalResidentId() == null || !outOfScope.contains(event.canonicalResidentId()))
                    .map(event -> event.fieldPath() != null && GoldenVisibility.isWithheld(
                            withheldByVersion.get(event.versionNumber()), event.fieldPath())
                            ? new GoldenEventView(event.sequence(), event.eventType(), event.versionNumber(),
                                    event.goldenBuildId(), event.relatedGoldenResidentId(), event.canonicalResidentId(),
                                    event.sourceRecordKey(), event.fieldPath(), null, null, event.detail(),
                                    event.occurredAt())
                            : event)
                    .toList();
        }
        return new GoldenResidentHistory(id, identity.status(), resolve(id), out, events);
    }

    /** Leaf values that differ between two payloads, by dotted path; every leaf of the first version is a change. */
    static List<ValueChange> changes(JsonNode before, JsonNode after) {
        Map<String, JsonNode> a = new LinkedHashMap<>();
        Map<String, JsonNode> b = new LinkedHashMap<>();
        flatten("", before, a);
        flatten("", after, b);
        var paths = new LinkedHashSet<String>();
        paths.addAll(a.keySet());
        paths.addAll(b.keySet());
        List<ValueChange> out = new ArrayList<>();
        for (String path : new TreeSet<>(paths)) {
            if (!Objects.equals(a.get(path), b.get(path))) {
                out.add(new ValueChange(path, a.get(path), b.get(path)));
            }
        }
        return out;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, JsonNode> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> flatten(prefix.isEmpty() ? entry.getKey()
                    : prefix + "." + entry.getKey(), entry.getValue(), out));
        } else {
            out.put(prefix, node);
        }
    }

    private record Identity(String status, OffsetDateTime createdAt, OffsetDateTime retiredAt, UUID mergedIntoId,
                            UUID splitFromId) {
    }

    private record Version(UUID id, int number, OffsetDateTime from, OffsetDateTime to, List<String> reasons,
                           UUID matchingRunId, UUID buildId, String policyVersion, JsonNode payload) {
    }

    private Identity identity(UUID id) {
        return db.one("select status, created_at, retired_at, merged_into_id, split_from_id from golden_resident"
                        + " where golden_resident_id = ?",
                r -> new Identity(r.getString("status"), Db.time(r, "created_at"), Db.time(r, "retired_at"),
                        Db.uuid(r, "merged_into_id"), Db.uuid(r, "split_from_id")), id)
                .orElseThrow(() -> new ReadNotFoundException("Golden Resident does not exist: " + id));
    }

    private List<Version> versions(UUID id) {
        var versions = db.list("select golden_resident_version_id, version_number, effective_from, effective_to,"
                        + " change_reasons::text as reasons_text, matching_run_id, golden_build_id,"
                        + " survivorship_policy_version, golden_payload::text as payload_text"
                        + " from golden_resident_version where golden_resident_id = ? order by version_number",
                r -> {
                    List<String> reasons = new ArrayList<>();
                    JsonNode reasonNode = db.json(r.getString("reasons_text"));
                    if (reasonNode != null) {
                        reasonNode.forEach(reason -> reasons.add(reason.asText()));
                    }
                    return new Version(Db.uuid(r, "golden_resident_version_id"), r.getInt("version_number"),
                            Db.time(r, "effective_from"), Db.time(r, "effective_to"), reasons,
                            Db.uuid(r, "matching_run_id"), Db.uuid(r, "golden_build_id"),
                            r.getString("survivorship_policy_version"), db.json(r.getString("payload_text")));
                }, id);
        if (versions.isEmpty()) {
            throw new IllegalStateException("Golden Resident has no version: " + id);
        }
        return versions;
    }

    private record ProvenanceRow(String path, UUID selected, JsonNode value, String rule, String policy,
                                 List<UUID> contributors) {
    }

    private record CanonicalInfo(String stateCode, String sourceSystem, UUID rawRecordId, UUID batchId, JsonNode payload) {
    }

    private List<GoldenFieldView> fields(UUID versionId) {
        var rows = db.list("select field_path, canonical_resident_id, selected_value::text as value_text, rule_applied,"
                        + " survivorship_policy_version, contributing_canonical_ids::text as contributors_text"
                        + " from golden_resident_version_provenance where golden_resident_version_id = ? order by field_path",
                r -> {
                    List<UUID> contributors = new ArrayList<>();
                    JsonNode ids = db.json(r.getString("contributors_text"));
                    if (ids != null) {
                        ids.forEach(x -> contributors.add(UUID.fromString(x.asText())));
                    }
                    return new ProvenanceRow(r.getString("field_path"), Db.uuid(r, "canonical_resident_id"),
                            db.json(r.getString("value_text")), r.getString("rule_applied"),
                            r.getString("survivorship_policy_version"), contributors);
                }, versionId);
        Set<UUID> ids = new LinkedHashSet<>();
        rows.forEach(row -> {
            ids.add(row.selected());
            ids.addAll(row.contributors());
        });
        Map<UUID, CanonicalInfo> canonicals = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            String array = "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
            db.list("select canonical_resident_id, state_code, source_system, raw_record_id, batch_id,"
                            + " canonical_payload::text as payload_text from canonical_resident"
                            + " where canonical_resident_id = any(cast(? as uuid[]))",
                    r -> canonicals.put(Db.uuid(r, "canonical_resident_id"), new CanonicalInfo(r.getString("state_code"),
                            r.getString("source_system"), Db.uuid(r, "raw_record_id"), Db.uuid(r, "batch_id"),
                            db.json(r.getString("payload_text")))),
                    array);
        }
        List<GoldenFieldView> out = new ArrayList<>();
        for (var row : rows) {
            var winner = canonicals.get(row.selected());
            List<ContributorView> contributors = row.contributors().stream().map(contributor -> {
                var info = canonicals.get(contributor);
                return new ContributorView(contributor, info == null ? null : info.stateCode(),
                        info == null ? null : info.sourceSystem(),
                        info == null ? null : CanonicalReadService.at(info.payload(), row.path()),
                        contributor.equals(row.selected()));
            }).toList();
            out.add(new GoldenFieldView(row.path(), row.value(), row.rule(), row.policy(), row.selected(),
                    winner == null ? null : winner.stateCode(), winner == null ? null : winner.sourceSystem(),
                    winner == null ? null : winner.rawRecordId(), winner == null ? null : winner.batchId(),
                    contributors, false));
        }
        return out;
    }

    /**
     * The field views a scoped user may see (ADR-0019): contributors outside the scope are left out, and a value that
     * survived from a member outside the scope is withheld (no value, no winning record).
     */
    private static List<GoldenFieldView> scoped(List<GoldenFieldView> fields, StateScope scope) {
        if (scope.all()) {
            return fields;
        }
        return fields.stream().map(field -> {
            var contributors = field.contributors().stream()
                    .filter(contributor -> scope.allows(contributor.stateCode()))
                    .toList();
            if (scope.allows(field.selectedStateCode())) {
                return new GoldenFieldView(field.fieldPath(), field.value(), field.rule(),
                        field.survivorshipPolicyVersion(), field.selectedCanonicalResidentId(), field.selectedStateCode(),
                        field.selectedSourceSystem(), field.selectedRawRecordId(), field.selectedBatchId(), contributors,
                        false);
            }
            return new GoldenFieldView(field.fieldPath(), null, field.rule(), field.survivorshipPolicyVersion(), null,
                    null, null, null, null, contributors, true);
        }).toList();
    }

    private static Set<String> withheld(List<GoldenFieldView> fields) {
        Set<String> withheld = new TreeSet<>();
        fields.stream().filter(GoldenFieldView::withheld).forEach(field -> withheld.add(field.fieldPath()));
        return withheld;
    }

    /** A copy of the payload without the withheld field paths. */
    private static JsonNode without(JsonNode payload, Set<String> withheld) {
        if (payload == null || withheld.isEmpty() || !payload.isObject()) {
            return payload;
        }
        var copy = payload.deepCopy();
        for (String path : withheld) {
            String[] parts = path.replace("[]", "").split("\\.");
            JsonNode parent = copy;
            for (int i = 0; i < parts.length - 1 && parent != null; i++) {
                parent = parent.get(parts[i]);
            }
            if (parent instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                object.remove(parts[parts.length - 1]);
            }
        }
        return copy;
    }

    /** Follows merged_into pointers to the active identity (or the last retired one if the chain ends retired). */
    private UUID resolve(UUID id) {
        UUID current = id;
        for (int i = 0; i < MAX_MERGE_CHAIN; i++) {
            var row = db.one("select status, merged_into_id from golden_resident where golden_resident_id = ?",
                    r -> new Object[] {r.getString(1), Db.uuid(r, "merged_into_id")}, current);
            if (row.isEmpty() || "ACTIVE".equals(row.get()[0]) || row.get()[1] == null) {
                return current;
            }
            current = (UUID) row.get()[1];
        }
        throw new IllegalStateException("Golden Resident merge chain is too long or cyclic: " + id);
    }

    private GoldenResidentSummary summary(ResultSet r) throws SQLException {
        JsonNode payload = db.json(r.getString("payload_text"));
        String states = r.getString("state_codes");
        return new GoldenResidentSummary(Db.uuid(r, "golden_resident_id"), r.getString("status"),
                r.getInt("version_number"), CanonicalReadService.text(payload, "name"),
                CanonicalReadService.text(payload, "dateOfBirth"), CanonicalReadService.text(payload, "gender"),
                CanonicalReadService.text(payload, "mobile"), CanonicalReadService.text(payload, "address.districtLGD"),
                r.getInt("member_count"), states == null ? List.of() : Arrays.asList(states.split(",")),
                Db.uuid(r, "merged_into_id"), Db.time(r, "created_at"), Db.time(r, "effective_from"), List.of());
    }
}
