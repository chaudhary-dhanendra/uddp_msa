package gov.uddp.core.events;

import gov.uddp.messaging.RowReplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.messaging.RowReplication.Selection;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Golden rows one build wrote or changed, as registry-service publishes them (golden-replica) for the edge's read
 * model (MSA Phase 3): the build, every Golden Resident and Golden Family it touched with all their memberships,
 * versions and provenance, its lifecycle events and their UDDP IDs. Applying them in table order reproduces the
 * owner's rows exactly, with the same ids, so the read APIs answer as in the monolith.
 */
public final class GoldenReplica {

    /** Parents first. */
    public static final List<String> ORDER = List.of("golden_build", "golden_resident", "golden_resident_member",
            "golden_resident_membership", "golden_resident_version", "golden_resident_version_provenance",
            "golden_resident_field_provenance", "golden_resident_event", "golden_family", "golden_family_version",
            "golden_family_membership", "golden_family_event", "uddp_id");

    /** golden_resident points at other Golden Residents of the same replica. */
    public static final Map<String, List<String>> SELF_REFERENCES = Map.of(
            "golden_resident", List.of("merged_into_id", "split_from_id"));

    private static final String RESIDENTS = "(select golden_resident_id from golden_resident_event where golden_build_id = ?"
            + " union select related_golden_resident_id from golden_resident_event where golden_build_id = ?"
            + " and related_golden_resident_id is not null"
            + " union select golden_resident_id from golden_resident_version where golden_build_id = ?"
            + " union select m.golden_resident_id from golden_family_membership m join golden_family_event e"
            + " using (golden_family_id) where e.golden_build_id = ? and m.golden_resident_id is not null)";
    private static final String FAMILIES = "(select golden_family_id from golden_family_event where golden_build_id = ?"
            + " union select golden_family_id from golden_family_version where golden_build_id = ?)";

    private GoldenReplica() {
    }

    public static ObjectNode read(Connection c, UUID build) throws SQLException {
        List<Object> r = List.of(build, build, build, build);
        List<Object> f = List.of(build, build);
        return RowReplication.read(c, List.of(
                new Selection("golden_build", "golden_build_id = ?", List.of(build)),
                new Selection("golden_resident", "golden_resident_id in " + RESIDENTS, r),
                new Selection("golden_resident_member", "golden_resident_id in " + RESIDENTS, r),
                new Selection("golden_resident_membership", "golden_resident_id in " + RESIDENTS, r),
                new Selection("golden_resident_version", "golden_resident_id in " + RESIDENTS, r),
                new Selection("golden_resident_version_provenance", "golden_resident_version_id in (select"
                        + " golden_resident_version_id from golden_resident_version where golden_resident_id in "
                        + RESIDENTS + ")", r),
                new Selection("golden_resident_field_provenance", "golden_resident_id in " + RESIDENTS, r),
                new Selection("golden_resident_event", "golden_build_id = ?", List.of(build)),
                new Selection("golden_family", "golden_family_id in " + FAMILIES, f),
                new Selection("golden_family_version", "golden_family_id in " + FAMILIES, f),
                new Selection("golden_family_membership", "golden_family_id in " + FAMILIES, f),
                new Selection("golden_family_event", "golden_build_id = ?", List.of(build)),
                new Selection("uddp_id", "golden_id in " + RESIDENTS + " or golden_id in " + FAMILIES,
                        List.of(build, build, build, build, build, build))));
    }

    /** At most this many rows per golden-replica event (golden_resident rows are never split: they reference each other). */
    static final int CHUNK_ROWS = 1000;

    /** The build's rows in ordered chunks; the last one marks the build as replicated. */
    public static List<com.fasterxml.jackson.databind.node.ObjectNode> chunks(ObjectNode rows) {
        return RowReplication.chunks(rows, ORDER, CHUNK_ROWS, SELF_REFERENCES.keySet());
    }

    /** Applies one golden-replica chunk; the last chunk records that the whole build is here. */
    public static void apply(Connection c, JsonNode envelope) throws SQLException {
        JsonNode p = envelope.path("payload");
        RowReplication.apply(c, p.path("rows"), ORDER, SELF_REFERENCES);
        if (!p.path("last").asBoolean(true)) {
            return;
        }
        try (var s = c.prepareStatement("insert into remote_golden_replicated (golden_build_id, matching_run_id)"
                + " values (?, ?) on conflict do nothing")) {
            s.setObject(1, UUID.fromString(p.path("goldenBuildId").asText()));
            String run = p.path("matchingRunId").asText(null);
            s.setObject(2, run == null ? null : UUID.fromString(run));
            s.executeUpdate();
        }
    }
}
