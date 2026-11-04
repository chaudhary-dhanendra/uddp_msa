package gov.uddp.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenResidentService;
import gov.uddp.core.identity.IdentityPolicyEvaluator.Candidate;
import gov.uddp.core.identity.IdentityPolicyEvaluator.Subject;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Track E3: the Identity Policy ladder. A shared deterministic identifier merges records matching never linked unless a
 * DOB/gender conflict sends the pair to review; different values of a unique identifier split a matching cluster
 * through a CANNOT_LINK constraint; a configured household identifier anchors Golden Families across sources; and
 * without an approved policy the Golden build is exactly as before.
 */
class IdentityPolicyTest {
    private static final Path SEED = Path.of("../platform/identity/identifier-types.yaml");

    @Test
    void theLadderDecidesDeterministicConflictNeverMergeAndStricterPairs() {
        var policy = new IdentityPolicy(List.of("AADHAAR"), List.of("AADHAAR", "VOTER_ID"), List.of("VOTER_ID"),
                null, 365, true, 0.99, null);
        var subjects = List.of(
                subject("a", Map.of("AADHAAR", "h1"), "1980-03-12", "MALE"),
                subject("b", Map.of("AADHAAR", "h1"), "1980-03-12", "MALE"),
                subject("c", Map.of("AADHAAR", "h2"), "1980-03-12", "MALE"),
                subject("d", Map.of("AADHAAR", "h2"), "1990-01-01", "MALE"),
                subject("e", Map.of("VOTER_ID", "v1"), "1975-01-01", "FEMALE"),
                subject("f", Map.of("VOTER_ID", "v2"), "1975-01-01", "FEMALE"),
                subject("g", Map.of(), "1960-01-01", "MALE"),
                subject("h", Map.of(), "1960-01-01", "MALE"));
        var evaluation = IdentityPolicyEvaluator.evaluate(policy, subjects, List.of(
                new Candidate("a", "c", 0.999, true),
                new Candidate("e", "f", 0.97, true),
                new Candidate("g", "h", 0.98, true)), false);

        assertEquals(List.of(IdentityPolicyEvaluator.Pair.of("a", "b", "AADHAAR")), evaluation.autoMatches());
        assertEquals(List.of(IdentityPolicyEvaluator.Pair.of("c", "d", "AADHAAR")), evaluation.conflicts(),
                "the same Aadhaar with a ten-year DOB gap goes to review");
        assertEquals(List.of("a|c|AADHAAR", "e|f|VOTER_ID"), evaluation.neverMerge().stream()
                .map(p -> p.keyA() + "|" + p.keyB() + "|" + p.type()).toList());
        assertEquals(List.of("g|h"), evaluation.probabilisticReviews().stream().map(p -> p.keyA() + "|" + p.keyB()).toList(),
                "no identifiers: 0.98 is below the stricter 0.99");
        assertEquals(4, evaluation.withDeterministicIdentifier());
        assertEquals(4, evaluation.probabilisticOnly());

        var registry = IdentifierTypeRegistry.fromSeed(SEED);
        assertThrows(IllegalArgumentException.class, () -> new IdentityPolicy(List.of("VOTER_ID"), List.of(), List.of(),
                null, null, null, null, null).validate(registry), "a MEDIUM identifier cannot match deterministically");
        assertThrows(IllegalArgumentException.class, () -> new IdentityPolicy(List.of(), List.of(), List.of(),
                "AADHAAR", null, null, null, null).validate(registry), "a PERSON identifier cannot anchor families");
    }

    @Test
    void anApprovedPolicyMergesSplitsAndAnchorsWhileNoPolicyChangesNothing() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        UUID run = SampleData.processedSampleWithMatchingRun(connections);
        var policies = new IdentityPolicyService(connections, new IdentifierTypeRegistry(connections, SEED,
                IdentifierTypeRegistry.defaultValidators()));
        GoldenResidentService golden = golden(connections, policies);
        UUID ram1 = canonical(sql, "Ram Singh", "ST1");
        UUID ram2 = canonical(sql, "Ram Singh", "ST2");
        identify(sql, ram1, "AADHAAR", "h-ram-1");
        identify(sql, ram2, "AADHAAR", "h-ram-2");
        identify(sql, canonical(sql, "Sita Singh", null), "AADHAAR", "h-shared");
        identify(sql, canonical(sql, "Anita Devi", null), "AADHAAR", "h-shared");
        identify(sql, canonical(sql, "Sunita Devi", null), "AADHAAR", "h-gender");
        identify(sql, canonical(sql, "Ramesh Kumar", null), "AADHAAR", "h-gender");
        anchor(sql, "ST1|pds|pds|R1001", "rc-1");
        anchor(sql, "ST1|pds|pds|R1002", "rc-1");

        // A draft or submitted policy is not in force: the build is the M14 baseline.
        var draft = policies.draft(new IdentityPolicy(List.of("AADHAAR"), List.of("AADHAAR"), List.of(), "RATION_CARD",
                3650, true, null, null), "maker");
        policies.submit(draft.policyId(), "maker");
        golden.build(run);
        assertEquals(5, sql.count("select count(*) from golden_resident where status='ACTIVE'"));
        assertEquals(6, sql.count("select count(*) from golden_family where status='ACTIVE'"));
        assertEquals(sql.goldenOf(ram1), sql.goldenOf(ram2), "matching's cluster stands without a policy");

        policies.approve(draft.policyId(), "checker", "coverage reviewed");
        golden.build(run);

        assertNotEquals(sql.goldenOf(ram1), sql.goldenOf(ram2), "two different Aadhaar values never merge");
        assertEquals(sql.goldenOf(canonical(sql, "Sita Singh", null)), sql.goldenOf(canonical(sql, "Anita Devi", null)),
                "the same Aadhaar merges records matching never linked");
        assertNotEquals(sql.goldenOf(canonical(sql, "Sunita Devi", null)),
                sql.goldenOf(canonical(sql, "Ramesh Kumar", null)), "a gender conflict goes to review, not a merge");
        String summary = sql.text("select summary->>'identity' from golden_build order by started_at desc limit 1");
        assertTrue(summary.contains("\"neverMerge\": 1") && summary.contains("\"deterministicMatches\": 1")
                && summary.contains("\"conflictsForReview\": 1"), summary);

        assertEquals(1, sql.count("select count(*) from golden_family where status='ACTIVE'"
                + " and family_record_key='anchor:RATION_CARD:rc-1'"));
        assertEquals(3, sql.count("select count(*) from golden_family_membership m join golden_family f"
                + " using (golden_family_id) where f.family_record_key='anchor:RATION_CARD:rc-1' and m.effective_to is null"),
                "both ST1 households sharing the ration card are one family");
        assertEquals(5, sql.count("select count(*) from golden_family where status='ACTIVE'"));
    }

    private static GoldenResidentService golden(PostgresConnectionFactory connections, IdentityPolicyService policies) {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var service = new GoldenResidentService(connections, json, SampleData.loader(SampleData.STATE_PACKS));
        var families = new gov.uddp.core.golden.GoldenFamilyBuilder(json);
        families.setIdentityPolicies(policies);
        service.setFamilyBuilder(families);
        service.setStewardship(new gov.uddp.core.stewardship.StewardshipStore());
        service.setIdentityPolicies(policies);
        return service;
    }

    private static Subject subject(String key, Map<String, String> ids, String dob, String gender) {
        return new Subject(key, "ST1", "src", ids, LocalDate.parse(dob), gender);
    }

    private static UUID canonical(Sql sql, String name, String state) {
        return sql.uuid("select canonical_resident_id from canonical_resident_current where canonical_payload->>'name' = ?"
                + " and (cast(? as text) is null or state_code = ?) order by state_code limit 1", name, state, state);
    }

    /** Test setup only: gives a canonical record a protected identifier, as E2 mapping would. */
    private static void identify(Sql sql, UUID canonical, String type, String value) {
        sql.execute("update canonical_resident set canonical_payload = jsonb_set(canonical_payload,"
                + " '{extensions,uddp.identifiers}', coalesce(canonical_payload->'extensions'->'uddp.identifiers', '{}')"
                + " || jsonb_build_object(?::text, jsonb_build_object('subject', 'PERSON', 'value', ?::text)))"
                + " where canonical_resident_id = ?", type, value, canonical);
    }

    /** Test setup only: gives a source household a protected household identifier, as E2 family observation would. */
    private static void anchor(Sql sql, String familyRecordKey, String value) {
        sql.execute("update canonical_family_membership set family_payload = family_payload || jsonb_build_object("
                + "'identifiers', jsonb_build_object('RATION_CARD', jsonb_build_object('subject', 'HOUSEHOLD',"
                + " 'value', ?::text))) where family_record_key = ?", value, familyRecordKey);
    }
}
