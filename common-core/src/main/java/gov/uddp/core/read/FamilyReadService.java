package gov.uddp.core.read;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Golden Families (M14, docs/family-domain.md): households with their current members resolved to Golden Residents,
 * lineage to the canonical observation and raw record behind each membership, related households that share a
 * Golden Resident, versions and events. Read-only.
 */
@Service
public class FamilyReadService {
    public record FamilySummary(UUID goldenFamilyId, String familyRecordKey, String stateCode, String sourceSystem,
                                String sourceFamilyId, String status, int versionNumber, int memberCount,
                                OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /**
     * A member of the household: the Golden Resident, the role, and the canonical membership observation (with its
     * canonical Resident, raw record and batch) that established it.
     */
    public record FamilyMemberView(String residentRecordKey, UUID goldenResidentId, String name,
                                   String goldenResidentStatus, String relationshipType, int addedInVersion,
                                   OffsetDateTime memberSince, UUID canonicalFamilyMembershipId,
                                   UUID canonicalResidentId, UUID rawRecordId, UUID batchId) {
    }

    /** Another active household that currently shares at least one Golden Resident with this one. */
    public record RelatedFamily(UUID goldenFamilyId, String familyRecordKey, String stateCode, String sourceFamilyId,
                                List<UUID> sharedGoldenResidentIds) {
    }

    public record FamilyVersionSummary(int versionNumber, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                       List<String> changeReasons, UUID goldenBuildId) {
    }

    public record GoldenFamilyDetail(UUID goldenFamilyId, String familyRecordKey, String stateCode, String sourceSystem,
                                     String sourceFamilyId, String status, OffsetDateTime createdAt,
                                     OffsetDateTime retiredAt, Integer currentVersion, int latestVersion,
                                     JsonNode payload, List<FamilyMemberView> members,
                                     List<RelatedFamily> relatedFamilies, List<FamilyVersionSummary> versions,
                                     String uddpId) {
    }

    public record FamilyVersionMember(String residentRecordKey, UUID goldenResidentId, String relationshipType) {
    }

    public record FamilyVersionView(int versionNumber, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                    List<String> changeReasons, UUID goldenBuildId, JsonNode payload,
                                    List<FamilyVersionMember> members) {
    }

    public record FamilyEventView(long sequence, String eventType, Integer versionNumber, UUID goldenBuildId,
                                  String residentRecordKey, UUID goldenResidentId, JsonNode detail,
                                  OffsetDateTime occurredAt) {
    }

    public record GoldenFamilyHistory(UUID goldenFamilyId, String status, List<FamilyVersionView> versions,
                                      List<FamilyEventView> events) {
    }

    private record Version(int number, OffsetDateTime from, OffsetDateTime to, List<String> reasons, UUID buildId,
                           JsonNode payload) {
    }

    private static final Map<String, String> SORT = Map.of(
            "sourceFamilyId", "f.source_family_id", "stateCode", "f.state_code", "memberCount", "member_count",
            "createdAt", "f.created_at", "updatedAt", "v.effective_from");
    private static final String LATEST_VERSION = " join lateral (select * from golden_family_version x"
            + " where x.golden_family_id = f.golden_family_id order by x.version_number desc limit 1) v on true";
    private static final String SUMMARY_COLUMNS = "select f.golden_family_id, f.family_record_key, f.state_code,"
            + " f.source_system, f.source_family_id, f.status, f.created_at, v.version_number, v.effective_from,"
            + " (select count(*) from golden_family_membership m where m.golden_family_id = f.golden_family_id"
            + " and m.added_in_version <= v.version_number"
            + " and (m.removed_in_version is null or m.removed_in_version > v.version_number)) as member_count"
            + " from golden_family f" + LATEST_VERSION;

    private final Db db;
    private final BatchReadService batches;
    private final GoldenVisibility goldens;

    public FamilyReadService(Db db, BatchReadService batches, GoldenVisibility goldens) {
        this.db = db;
        this.batches = batches;
        this.goldens = goldens;
    }

    /**
     * @param state            pack ID or state code of the household's source
     * @param status           ACTIVE (default), RETIRED or ALL
     * @param q                source family ID contains (case-insensitive)
     * @param goldenResidentId households this Golden Resident currently belongs to
     */
    public Page<FamilySummary> list(String state, String status, String q, UUID goldenResidentId, Integer page,
                                    Integer size, String sort) {
        var query = PageQuery.of(page, size, sort, SORT, "sourceFamilyId,asc", "f.golden_family_id");
        String s = status == null || status.isBlank() ? "ACTIVE" : status.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "RETIRED", "ALL").contains(s)) {
            throw new IllegalArgumentException("status must be ACTIVE, RETIRED or ALL");
        }
        String statusFilter = s.equals("ALL") ? null : s;
        String code = batches.stateCode(state);
        String id = q == null || q.isBlank() ? null : "%" + CanonicalReadService.escapeLike(q.strip()) + "%";
        String where = " where (cast(? as text) is null or f.status = cast(? as text))"
                + " and (cast(? as text) is null or f.state_code = cast(? as text))"
                + " and (cast(? as text) is null or f.source_family_id ilike cast(? as text))"
                + " and (cast(? as uuid) is null or exists (select 1 from golden_family_membership m"
                + " where m.golden_family_id = f.golden_family_id and m.effective_to is null"
                + " and m.golden_resident_id = cast(? as uuid)))" + Db.inScope("f.state_code");
        Object[] parameters = Db.withScope(statusFilter, statusFilter, code, code, id, id, goldenResidentId,
                goldenResidentId);
        long total = db.count("select count(*) from golden_family f" + LATEST_VERSION + where, parameters);
        var items = db.list(SUMMARY_COLUMNS + where + query.sql(), FamilyReadService::summary, parameters);
        return Page.of(items, query, total);
    }

    /** The active households a Golden Resident currently belongs to; 404 for an unknown Golden ID. */
    public List<FamilySummary> familiesOf(UUID goldenResidentId) {
        goldens.requireVisible(goldenResidentId);
        return db.list(SUMMARY_COLUMNS + " where f.status = 'ACTIVE' and exists (select 1 from golden_family_membership m"
                        + " where m.golden_family_id = f.golden_family_id and m.effective_to is null"
                        + " and m.golden_resident_id = ?)" + Db.inScope("f.state_code")
                        + " order by f.state_code, f.source_family_id",
                FamilyReadService::summary, Db.withScope(goldenResidentId));
    }

    public GoldenFamilyDetail detail(UUID id) {
        var identity = db.one("select golden_family_id, family_record_key, state_code, source_system, source_family_id,"
                        + " status, created_at, retired_at from golden_family where golden_family_id = ?",
                r -> new Object[] {r.getString("family_record_key"), r.getString("state_code"),
                        r.getString("source_system"), r.getString("source_family_id"), r.getString("status"),
                        Db.time(r, "created_at"), Db.time(r, "retired_at")}, id)
                .orElseThrow(() -> new ReadNotFoundException("Golden Family does not exist: " + id));
        Db.requireInScope((String) identity[1], "Golden Family does not exist: " + id);
        var versions = versions(id);
        var latest = versions.getLast();
        var members = db.list("select m.resident_record_key, m.golden_resident_id, m.relationship_type,"
                        + " m.added_in_version, m.effective_from, m.canonical_family_membership_id, g.status as golden_status,"
                        + " (select gv.golden_payload->>'name' from golden_resident_version gv"
                        + " where gv.golden_resident_id = m.golden_resident_id order by gv.version_number desc limit 1)"
                        + " as name, o.canonical_resident_id, o.raw_record_id, o.batch_id"
                        + " from golden_family_membership m"
                        + " join golden_resident g on g.golden_resident_id = m.golden_resident_id"
                        + " join canonical_family_membership cm"
                        + " on cm.canonical_family_membership_id = m.canonical_family_membership_id"
                        + " join canonical_family_observation o"
                        + " on o.canonical_family_observation_id = cm.canonical_family_observation_id"
                        + " where m.golden_family_id = ? and m.added_in_version <= ?"
                        + " and (m.removed_in_version is null or m.removed_in_version > ?)"
                        + " order by m.resident_record_key",
                r -> new FamilyMemberView(r.getString("resident_record_key"), Db.uuid(r, "golden_resident_id"),
                        r.getString("name"), r.getString("golden_status"), r.getString("relationship_type"),
                        r.getInt("added_in_version"), Db.time(r, "effective_from"),
                        Db.uuid(r, "canonical_family_membership_id"), Db.uuid(r, "canonical_resident_id"),
                        Db.uuid(r, "raw_record_id"), Db.uuid(r, "batch_id")),
                id, latest.number(), latest.number());
        var related = db.list("select f.golden_family_id, f.family_record_key, f.state_code, f.source_family_id,"
                        + " string_agg(distinct o.golden_resident_id::text, ',' order by o.golden_resident_id::text) as shared"
                        + " from golden_family_membership mine"
                        + " join golden_family_membership o on o.golden_resident_id = mine.golden_resident_id"
                        + " and o.effective_to is null and o.golden_family_id <> mine.golden_family_id"
                        + " join golden_family f on f.golden_family_id = o.golden_family_id and f.status = 'ACTIVE'"
                        + " where mine.golden_family_id = ? and mine.effective_to is null" + Db.inScope("f.state_code")
                        + " group by f.golden_family_id, f.family_record_key, f.state_code, f.source_family_id"
                        + " order by f.state_code, f.source_family_id",
                r -> new RelatedFamily(Db.uuid(r, "golden_family_id"), r.getString("family_record_key"),
                        r.getString("state_code"), r.getString("source_family_id"),
                        java.util.Arrays.stream(r.getString("shared").split(",")).map(UUID::fromString).toList()),
                Db.withScope(id));
        String status = (String) identity[4];
        boolean active = "ACTIVE".equals(status);
        return new GoldenFamilyDetail(id, (String) identity[0], (String) identity[1], (String) identity[2],
                (String) identity[3], status, (OffsetDateTime) identity[5], (OffsetDateTime) identity[6],
                active ? latest.number() : null, latest.number(), latest.payload(), members, related,
                versions.stream().map(v -> new FamilyVersionSummary(v.number(), v.from(), v.to(), v.reasons(),
                        v.buildId())).toList(), GoldenReadService.uddpIdOf(db, "FAMILY", id));
    }

    /** The Golden Family a UDDP ID names (Track E4), within the caller's scope. */
    public GoldenFamilyDetail byUddpId(String uddpId) {
        return detail(GoldenReadService.goldenIdOf(db, "FAMILY", uddpId));
    }

    public GoldenFamilyHistory history(UUID id) {
        var identity = db.one("select status, state_code from golden_family where golden_family_id = ?",
                        r -> new String[] {r.getString(1), r.getString(2)}, id)
                .orElseThrow(() -> new ReadNotFoundException("Golden Family does not exist: " + id));
        Db.requireInScope(identity[1], "Golden Family does not exist: " + id);
        String status = identity[0];
        List<FamilyVersionView> out = new ArrayList<>();
        for (var version : versions(id)) {
            var members = db.list("select resident_record_key, golden_resident_id, relationship_type"
                            + " from golden_family_membership where golden_family_id = ? and added_in_version <= ?"
                            + " and (removed_in_version is null or removed_in_version > ?) order by resident_record_key",
                    r -> new FamilyVersionMember(r.getString(1), Db.uuid(r, "golden_resident_id"), r.getString(3)),
                    id, version.number(), version.number());
            out.add(new FamilyVersionView(version.number(), version.from(), version.to(), version.reasons(),
                    version.buildId(), version.payload(), members));
        }
        var events = db.list("select event_sequence, event_type, version_number, golden_build_id, resident_record_key,"
                        + " golden_resident_id, detail::text, occurred_at from golden_family_event"
                        + " where golden_family_id = ? order by event_sequence",
                r -> new FamilyEventView(r.getLong(1), r.getString(2), Db.integer(r, "version_number"),
                        Db.uuid(r, "golden_build_id"), r.getString(5), Db.uuid(r, "golden_resident_id"),
                        db.json(r.getString(7)), Db.time(r, "occurred_at")),
                id);
        return new GoldenFamilyHistory(id, status, out, events);
    }

    private List<Version> versions(UUID id) {
        var versions = db.list("select version_number, effective_from, effective_to, change_reasons::text,"
                        + " golden_build_id, family_payload::text from golden_family_version where golden_family_id = ?"
                        + " order by version_number",
                r -> new Version(r.getInt(1), Db.time(r, "effective_from"), Db.time(r, "effective_to"),
                        reasons(db.json(r.getString(4))), Db.uuid(r, "golden_build_id"), db.json(r.getString(6))),
                id);
        if (versions.isEmpty()) {
            throw new IllegalStateException("Golden Family has no version: " + id);
        }
        return versions;
    }

    private static List<String> reasons(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null) {
            node.forEach(reason -> out.add(reason.asText()));
        }
        return out;
    }

    private static FamilySummary summary(ResultSet r) throws SQLException {
        return new FamilySummary(Db.uuid(r, "golden_family_id"), r.getString("family_record_key"),
                r.getString("state_code"), r.getString("source_system"), r.getString("source_family_id"),
                r.getString("status"), r.getInt("version_number"), r.getInt("member_count"),
                Db.time(r, "created_at"), Db.time(r, "effective_from"));
    }
}
