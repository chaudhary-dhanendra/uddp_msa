package gov.uddp.core.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.matching.MatchingMonitoringService.BandDecisions;
import gov.uddp.core.stewardship.StewardshipConflictException;
import gov.uddp.core.testsupport.TestDatabase;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchingMonitoringServiceTest {
    private PostgresConnectionFactory connections;
    private MatchingProfileService profiles;
    private MatchingMonitoringService monitoring;

    @BeforeEach
    void setUp() {
        connections = TestDatabase.freshMigrated();
        profiles = new MatchingProfileService(connections, new ObjectMapper(), (m, a) -> null, Runnable::run);
        monitoring = new MatchingMonitoringService(connections, new ObjectMapper(), profiles);
    }

    @Test
    void metricsOutsideTheBoundsOrDriftingFromThePreviousRunRaiseAlertsOnce() throws SQLException {
        run("2026-10-01T00:00:00Z", 0.30, 0.05, 30, 5);
        run("2026-10-02T00:00:00Z", 0.62, 0.25, 70, 25);   // match rate doubles, review rate above its 0.2 bound

        var alerts = monitoring.alerts(true);

        assertEquals(java.util.Set.of("match_rate:DRIFT", "review_rate:OUT_OF_BOUNDS", "review_rate:DRIFT",
                "auto_pairs:DRIFT", "review_volume:DRIFT"),
                alerts.stream().map(a -> a.metric() + ":" + a.kind()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(alerts.size(), monitoring.alerts(true).size());   // evaluating again adds nothing
        monitoring.acknowledge(alerts.getFirst().alertId(), "steward");
        assertEquals(alerts.size() - 1, monitoring.alerts(true).size());
        assertEquals(2, monitoring.metrics(10).size());
    }

    @Test
    void boundsAreConfigurable() throws SQLException {
        monitoring.updateBound("match_rate", null, 0.95, 5.0, "admin");
        monitoring.updateBound("review_rate", null, 0.9, 5.0, "admin");
        monitoring.updateBound("auto_pairs", null, null, null, "admin");
        monitoring.updateBound("review_volume", null, null, null, "admin");
        run("2026-10-01T00:00:00Z", 0.30, 0.05, 30, 5);
        run("2026-10-02T00:00:00Z", 0.62, 0.25, 70, 25);

        assertTrue(monitoring.alerts(true).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> monitoring.updateBound("match_rate", 0.9, 0.1, null, "admin"));
        assertThrows(IllegalArgumentException.class, () -> monitoring.updateBound("nonsense", null, null, null, "admin"));
    }

    @Test
    void stewardBehaviourBecomesThresholdProposals() {
        // 33 of 34 decisions at 0.90-0.95 accepted; 30 of 30 at 0.75-0.80 rejected; 0.85-0.90 has too little evidence.
        var bands = Map.of(18, new BandDecisions(33, 1), 17, new BandDecisions(3, 0), 15, new BandDecisions(0, 30));

        var drafts = MatchingMonitoringService.draft(bands, 0.95, 0.75, 20, 0.97);

        assertEquals(2, drafts.size());
        assertEquals("AUTO_MATCH", drafts.get(0).kind());
        assertEquals(0.90, drafts.get(0).proposed(), 1e-9);
        assertTrue(drafts.get(0).rationale().startsWith("97% of 0.90–0.95 accepted"), drafts.get(0).rationale());
        assertEquals("POSSIBLE_MATCH", drafts.get(1).kind());
        assertEquals(0.80, drafts.get(1).proposed(), 1e-9);
        assertTrue(MatchingMonitoringService.draft(Map.of(18, new BandDecisions(10, 0)), 0.95, 0.75, 20, 0.97).isEmpty());
        assertTrue(MatchingMonitoringService.draft(Map.of(18, new BandDecisions(25, 5)), 0.95, 0.75, 20, 0.97).isEmpty());
    }

    @Test
    void aProposalNeedsADifferentApproverAndAnApprovedProfileThenCreatesANewVersion() throws SQLException {
        UUID proposal = UUID.randomUUID();
        update("insert into matching_threshold_proposal(proposal_id, kind, current_value, proposed_value, rationale,"
                + " proposed_by) values ('" + proposal + "', 'AUTO_MATCH', 0.95, 0.90, 'test', 'maker')");

        assertThrows(StewardshipConflictException.class, () -> monitoring.approve(proposal, "maker"));
        assertThrows(StewardshipConflictException.class, () -> monitoring.approve(proposal, null));
        assertThrows(StewardshipConflictException.class, () -> monitoring.approve(proposal, "checker"));  // no approved profile

        UUID approved = UUID.randomUUID();
        update("insert into matching_profile(profile_id,name,version,status,config,parameters,weights,trained_at,created_by)"
                + " values ('" + approved + "','resident',1,'APPROVED','{\"blocking\":[[\"mobile\"]],\"thresholds\":"
                + "{\"autoMatch\":0.95,\"possibleMatch\":0.7}}'::jsonb,'{}'::jsonb,'[]'::jsonb,now(),'maker')");

        var applied = monitoring.approve(proposal, "checker");

        assertEquals("APPLIED", applied.status());
        var now = profiles.approved().orElseThrow();
        assertEquals(2, now.version());
        assertEquals(0.90, ((Number) ((Map<?, ?>) now.config().get("thresholds")).get("autoMatch")).doubleValue(), 1e-9);
        assertEquals(0.7, ((Number) ((Map<?, ?>) now.config().get("thresholds")).get("possibleMatch")).doubleValue(), 1e-9);
        assertEquals("RETIRED", profiles.get(approved).status());
        assertEquals(now.profileId(), applied.appliedProfileId());
        assertThrows(StewardshipConflictException.class, () -> monitoring.reject(proposal, "checker"));
    }

    // ---- helpers

    private void run(String startedAt, double matchRate, double reviewRate, int auto, int possible) throws SQLException {
        UUID run = UUID.randomUUID();
        update("insert into matching_run(run_id, model_version, threshold, possible_match_threshold, status, started_at,"
                + " records_considered, mode) values ('" + run + "', 'resident-match-v1', 0.95, 0.75, 'COMPLETED', '"
                + startedAt + "', 100, 'FULL')");
        update("insert into matching_run_metrics(run_id, records_considered, auto_pairs, possible_pairs, no_match_pairs,"
                + " matched_records, match_rate, review_rate, score_histogram) values ('" + run + "', 100, " + auto + ", "
                + possible + ", 1000, 20, " + matchRate + ", " + reviewRate + ", '[0,0,0,0,0,0,0,0,0,0]'::jsonb)");
    }

    private void update(String sql) throws SQLException {
        try (var c = connections.open(); var s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }
}
