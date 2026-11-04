package gov.uddp.core.stewardship;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenResidentService;
import gov.uddp.core.read.ReadNotFoundException;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M13 acceptance: POSSIBLE_MATCH pairs become review tasks; accept, reject, merge and split are append-only
 * decisions that every later Golden build honours, with Golden IDs carried forward (MERGED_INTO / SPLIT_FROM, never
 * reused) and matching evidence and canonical rows untouched. Sample data: the three "Ram Singh" records are one
 * AUTO_MATCH group and "Sita Singh"/"Sunita Devi" is a POSSIBLE_MATCH pair.
 */
class StewardshipTest {
    private PostgresConnectionFactory connections;
    private Sql sql;
    private GoldenResidentService golden;
    private StewardshipService stewardship;
    private Set<UUID> ram;
    private Set<UUID> sitaSunita;

    @BeforeEach
    void sample() {
        connections = TestDatabase.freshMigrated();
        sql = new Sql(connections);
        var loader = SampleData.loader(SampleData.STATE_PACKS);
        var processing = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
        golden = SampleData.golden(connections, loader);
        stewardship = SampleData.stewardship(connections, golden);
        ram = Set.copyOf(sql.canonicalsNamed("Ram Singh"));
        sitaSunita = Set.of(sql.canonicalsNamed("Sita Singh").getFirst(), sql.canonicalsNamed("Sunita Devi").getFirst());
        golden.build(run(List.of(ram), List.of(sitaSunita)));
    }

    @Test
    void aPossibleMatchBecomesOneReviewTaskAndAcceptingItMergesThePair() {
        assertEquals(5, active());
        assertEquals(1, sql.count("select count(*) from stewardship_review_task"));
        golden.build(run(List.of(ram), List.of(sitaSunita)));
        assertEquals(1, sql.count("select count(*) from stewardship_review_task"), "a pair never gets a second task");
        UUID sita = goldenOf("Sita Singh");
        UUID sunita = goldenOf("Sunita Devi");
        String evidence = matchingEvidence();
        String canonical = canonicalRows();

        var result = stewardship.decide(task(), "accept", "Asha (district officer)", "Same person: Aadhaar seen");

        assertEquals("ACCEPT_MATCH", result.action());
        assertEquals(1, result.constraints());
        assertEquals(1, result.goldenBuild().merged());
        assertEquals(4, active());
        UUID survivor = goldenOf("Sita Singh");
        assertEquals(survivor, goldenOf("Sunita Devi"));
        UUID loser = survivor.equals(sita) ? sunita : sita;
        assertEquals("RETIRED", sql.text("select status from golden_resident where golden_resident_id=?", loser));
        assertEquals(survivor, sql.uuid("select merged_into_id from golden_resident where golden_resident_id=?", loser));
        assertEquals(1, sql.count("select count(*) from golden_resident_event where event_type='MERGED'"
                + " and golden_resident_id=? and related_golden_resident_id=?", survivor, loser));
        assertEquals(evidence, matchingEvidence(), "matching evidence is never changed");
        assertEquals(canonical, canonicalRows(), "canonical rows are never changed");

        // The decision outlives later matching runs, which still score the pair as POSSIBLE or even NO_MATCH.
        golden.build(run(List.of(ram), List.of(sitaSunita)));
        golden.build(run(List.of(ram), List.of()));
        assertEquals(survivor, goldenOf("Sita Singh"));
        assertEquals(survivor, goldenOf("Sunita Devi"));
        assertEquals(1, sql.count("select count(*) from stewardship_review_task"));
        assertEquals(1, sql.count("select (summary->'stewardship'->>'constraintsInForce')::int from golden_build"
                + " order by started_at desc limit 1"));

        assertThrows(StewardshipConflictException.class,
                () -> stewardship.decide(task(), "REJECT", "Asha", "changed my mind"));
    }

    @Test
    void aRejectedPairStaysApartEvenWhenALaterRunAutoMatchesIt() {
        UUID sita = goldenOf("Sita Singh");
        UUID sunita = goldenOf("Sunita Devi");
        var result = stewardship.decide(task(), "REJECT", "Asha", "Different mothers' names on record");
        assertEquals("REJECT_MATCH", result.action());
        assertEquals(5, active());

        golden.build(run(List.of(ram, sitaSunita), List.of()));

        assertEquals(5, active());
        assertEquals(sita, goldenOf("Sita Singh"));
        assertEquals(sunita, goldenOf("Sunita Devi"));
    }

    @Test
    void splitThenMergeKeepsTheOriginalIdentityAndNeverReusesAnId() {
        UUID ramId = goldenOf("Ram Singh");
        String detached = sql.text("select m.source_record_key from golden_resident_membership m"
                + " join canonical_resident c using (canonical_resident_id) where m.golden_resident_id=?"
                + " and m.effective_to is null order by m.source_record_key desc limit 1", ramId);

        var split = stewardship.split(ramId, List.of(detached), "Asha", "Different father's name");

        assertEquals("SPLIT", split.action());
        assertEquals(1, split.goldenBuild().split());
        assertEquals(6, active());
        assertEquals(2, sql.count("select count(*) from golden_resident_membership where golden_resident_id=?"
                + " and effective_to is null", ramId), "the larger side keeps the Golden ID");
        UUID sideId = sql.uuid("select golden_resident_id from golden_resident_membership where source_record_key=?"
                + " and effective_to is null", detached);
        assertNotEquals(ramId, sideId);
        assertEquals(ramId, sql.uuid("select split_from_id from golden_resident where golden_resident_id=?", sideId));
        // Matching still clusters all three; the split holds.
        golden.build(run(List.of(ram), List.of(sitaSunita)));
        assertEquals(6, active());
        assertEquals(sideId, sql.uuid("select golden_resident_id from golden_resident_membership"
                + " where source_record_key=? and effective_to is null", detached));
        // The detached record's household now points at the new identity.
        assertEquals(1, sql.count("select count(*) from golden_family_membership where golden_resident_id=?"
                + " and effective_to is null", sideId));

        var merge = stewardship.merge(List.of(sideId, ramId), "Asha", "Father's name was a typo");

        assertEquals("MERGE", merge.action());
        assertEquals(5, active());
        assertEquals(3, sql.count("select count(*) from golden_resident_membership where golden_resident_id=?"
                + " and effective_to is null", ramId));
        assertEquals("RETIRED", sql.text("select status from golden_resident where golden_resident_id=?", sideId));
        assertEquals(ramId, sql.uuid("select merged_into_id from golden_resident where golden_resident_id=?", sideId));
        assertEquals(3, sql.count("select count(*) from golden_family_membership where golden_resident_id=?"
                + " and effective_to is null", ramId));
        // Both decisions are in the log; the merge's constraints superseded the split's on the same pairs.
        assertEquals(2, sql.count("select count(*) from stewardship_decision"));
        assertEquals(0, sql.count("select count(*) from stewardship_link_constraint_effective where constraint_type='CANNOT_LINK'"));
    }

    @Test
    void invalidDecisionsAreRejectedWithoutRecordingAnything() {
        UUID ramId = goldenOf("Ram Singh");
        List<String> ramKeys = sql.column("select source_record_key from golden_resident_membership"
                + " where golden_resident_id=? and effective_to is null", String.class, ramId);

        assertThrows(IllegalArgumentException.class, () -> stewardship.decide(task(), "ACCEPT", " ", "why"));
        assertThrows(IllegalArgumentException.class, () -> stewardship.decide(task(), "ACCEPT", "Asha", ""));
        assertThrows(IllegalArgumentException.class, () -> stewardship.decide(task(), "MAYBE", "Asha", "why"));
        assertThrows(ReadNotFoundException.class, () -> stewardship.decide(UUID.randomUUID(), "ACCEPT", "Asha", "why"));
        assertThrows(IllegalArgumentException.class, () -> stewardship.merge(List.of(ramId), "Asha", "why"));
        assertThrows(IllegalArgumentException.class, () -> stewardship.merge(List.of(ramId, ramId), "Asha", "why"));
        assertThrows(ReadNotFoundException.class, () -> stewardship.merge(List.of(ramId, UUID.randomUUID()), "Asha", "why"));
        assertThrows(IllegalArgumentException.class, () -> stewardship.split(ramId, List.of(), "Asha", "why"));
        assertThrows(IllegalArgumentException.class, () -> stewardship.split(ramId, ramKeys, "Asha", "why"));
        assertThrows(IllegalArgumentException.class, () -> stewardship.split(ramId, List.of("ST9|x|x|1"), "Asha", "why"));
        assertEquals(0, sql.count("select count(*) from stewardship_decision"));

        stewardship.decide(task(), "ACCEPT", "Asha", "Same person");
        UUID retired = sql.uuid("select golden_resident_id from golden_resident where status='RETIRED'");
        assertThrows(StewardshipConflictException.class, () -> stewardship.merge(List.of(retired, ramId), "Asha", "why"));
        assertThrows(StewardshipConflictException.class, () -> stewardship.split(retired, List.of("k"), "Asha", "why"));
        assertEquals(1, sql.count("select count(*) from stewardship_decision"));
    }

    @Test
    void aDecisionNeedsACompletedMatchingRun() {
        var empty = TestDatabase.freshMigrated();
        var service = SampleData.stewardship(empty, SampleData.golden(empty, SampleData.loader(SampleData.STATE_PACKS)));
        assertThrows(StewardshipConflictException.class,
                () -> service.merge(List.of(UUID.randomUUID(), UUID.randomUUID()), "Asha", "why"));
    }

    private UUID run(List<Set<UUID>> auto, List<Set<UUID>> possible) {
        return MatchingRunFixture.completed(connections, auto, possible);
    }

    private UUID task() {
        return sql.uuid("select review_task_id from stewardship_review_task");
    }

    private UUID goldenOf(String name) {
        return sql.goldenOf(sql.canonicalsNamed(name).getFirst());
    }

    private long active() {
        return sql.count("select count(*) from golden_resident where status='ACTIVE'");
    }

    private String matchingEvidence() {
        return sql.text("select string_agg(run_id::text || canonical_record_a || canonical_record_b || decision"
                + " || match_probability || evidence::text, ',' order by run_id, canonical_record_a, canonical_record_b)"
                + " from resident_match_pair where run_id in (select run_id from matching_run order by started_at limit 2)");
    }

    private String canonicalRows() {
        return sql.text("select string_agg(canonical_resident_id::text || canonical_payload::text, ','"
                + " order by canonical_resident_id) from canonical_resident");
    }
}
