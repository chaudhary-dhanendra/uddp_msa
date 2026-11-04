package gov.uddp.registry;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.events.RemoteCanonicalProjection;
import gov.uddp.core.events.RemoteMatchingProjection;
import gov.uddp.messaging.Inbox;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * registry-service's projections (docs/msa-design.md, section 6.1), written with the monolith's table names so the
 * unchanged Golden build reads them: canonical Residents and their versions, family observations and memberships
 * (RemoteCanonicalProjection) and identity's matching runs (RemoteMatchingProjection), the same projections the
 * monolith uses. Every applied event is also
 * recorded in lineage.lineage_event.
 */
public final class RegistryProjections {

    private RegistryProjections() {
    }

    public static void residentCreated(Connection c, JsonNode envelope) throws SQLException {
        RemoteCanonicalProjection.residentCreated(c, envelope);
        lineage(c, envelope, envelope.path("payload").path("sourceRecordKey").asText());
    }

    public static void familyObserved(Connection c, JsonNode envelope) throws SQLException {
        RemoteCanonicalProjection.familyObserved(c, envelope);
        lineage(c, envelope, envelope.path("payload").path("residentRecordKey").asText());
    }

    /**
     * stewardship.decision-recorded: the decision and its link constraints, replicated row for row, so the next build
     * applies them exactly as the monolith's build did (ProjectedStewardship reads the effective view).
     */
    public static void decisionRecorded(Connection c, JsonNode envelope) throws SQLException {
        gov.uddp.messaging.RowReplication.apply(c, envelope.path("payload").path("rows"),
                java.util.List.of("stewardship_decision", "stewardship_link_constraint"), java.util.Map.of());
        lineage(c, envelope, envelope.path("payload").path("decisionId").asText());
    }

    /** identity's run events, into the same matching tables and RECEIVING-until-complete rule as the monolith. */
    public static Inbox.Handler matching(Inbox.Handler projection, String subjectField) {
        return (c, envelope) -> {
            projection.apply(c, envelope);
            lineage(c, envelope, envelope.path("payload").path(subjectField).asText());
        };
    }

    static final Inbox.Handler MATCH_DECIDED = matching(RemoteMatchingProjection::matchDecided, "matchingRunId");
    static final Inbox.Handler CLUSTER_ASSIGNED = matching(RemoteMatchingProjection::clusterAssigned, "matchingRunId");
    static final Inbox.Handler MATCH_RUN_COMPLETED = matching(RemoteMatchingProjection::matchRunCompleted,
            "matchingRunId");

    private static void lineage(Connection c, JsonNode envelope, String subject) throws SQLException {
        try (var s = c.prepareStatement("insert into lineage.lineage_event (event_id, event_type, subject_key,"
                + " correlation_id, producer) values (?, ?, ?, ?, ?) on conflict do nothing")) {
            s.setObject(1, UUID.fromString(envelope.path("eventId").asText()));
            s.setString(2, envelope.path("eventType").asText());
            s.setString(3, subject);
            String correlation = text(envelope.path("correlationId"));
            s.setObject(4, correlation == null || correlation.isBlank() ? null : UUID.fromString(correlation));
            s.setString(5, envelope.path("producer").asText());
            s.executeUpdate();
        }
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
