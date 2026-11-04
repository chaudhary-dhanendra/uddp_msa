package gov.uddp.core.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.department.DepartmentRepository;
import gov.uddp.core.department.SourcePackService;
import gov.uddp.core.inbox.ExceptionInboxService;
import gov.uddp.core.inbox.ExceptionInboxService.Filter;
import gov.uddp.core.inbox.RulesAndMatchingExceptionSources;
import gov.uddp.core.matching.MatchingDriftMetrics;
import gov.uddp.core.rules.RuleSet.OwnerType;
import gov.uddp.core.rules.RuleSet.Status;
import gov.uddp.core.rules.RuleSet.Type;
import gov.uddp.core.scheme.SchemePackService;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * Track A maker-checker: rule sets (eligibility included) and Scheme Pack versions become effective only when a user
 * other than their author and submitter approves them, and there is no path that approves them automatically. Their
 * open items reach the exception inbox and their counts reach monitoring; Track B's drift reaches the gates.
 */
class RuleAndSchemeApprovalTest {
    private PostgresConnectionFactory connections;
    private RuleSetService rules;
    private SchemePackService schemes;
    private SourcePackService packs;

    @BeforeEach
    void setUp() {
        connections = TestDatabase.freshMigrated();
        rules = new RuleSetService(new RuleSetStore(connections));
        schemes = new SchemePackService(connections, new ObjectMapper());
        packs = new SourcePackService(SampleData.loader(SampleData.STATE_PACKS), new DepartmentRepository(connections));
    }

    @Test
    void anEligibilityRuleNeedsAnotherUserToApproveIt() {
        RuleSet draft = rules.createDraft(eligibilityDraft("GO-1"), "maker");
        assertEquals(Status.DRAFT, draft.status());
        assertEquals(1, draft.version());
        assertTrue(effective().isEmpty(), "a draft is never effective");

        assertThrows(ApprovalConflictException.class, () -> rules.approve(draft.id(), "checker", null),
                "a draft must be submitted first");
        rules.submit(draft.id(), "maker");
        assertTrue(effective().isEmpty(), "a submitted rule set is not effective yet");
        assertThrows(AccessDeniedException.class, () -> rules.approve(draft.id(), "maker", null));
        assertThrows(AccessDeniedException.class, () -> rules.approve(draft.id(), null, null));
        assertThrows(IllegalArgumentException.class, () -> rules.reject(draft.id(), "checker", " "));

        assertEquals(Status.APPROVED, rules.approve(draft.id(), "checker", "clause checked").status());
        assertEquals(draft.id(), effective().orElseThrow().ruleSet().id());
        assertThrows(ApprovalConflictException.class, () -> rules.approve(draft.id(), "other", null));
        assertEquals("checker", decidedBy("rule_set", "rule_set_id", draft.id()));
    }

    @Test
    void anotherSubmitterCannotApproveTheirOwnSubmissionEither() {
        RuleSet draft = rules.createDraft(eligibilityDraft("GO-2"), "maker");
        rules.submit(draft.id(), "submitter");
        assertThrows(AccessDeniedException.class, () -> rules.approve(draft.id(), "submitter", null));
        assertEquals(Status.REJECTED, rules.reject(draft.id(), "checker", "wrong clause").status());
        assertTrue(effective().isEmpty());
    }

    @Test
    void invalidDmnIsRejectedAtDraftTime() {
        var bad = new RuleSetService.Draft(Type.ELIGIBILITY, OwnerType.SCHEME, "SCHEME-A", LocalDate.of(2026, 1, 1),
                null, "<definitions/>", List.of("age"));
        assertThrows(IllegalArgumentException.class, () -> rules.createDraft(bad, "maker"));
    }

    @Test
    void aSchemePackVersionNeedsAnApprovedEligibilityRuleAndAnotherUser() {
        RuleSet rule = rules.createDraft(eligibilityDraft("GO-3"), "maker");
        var version = schemes.createDraft(schemeDraft(rule.id()), "maker");
        assertEquals("DRAFT", version.status());
        schemes.submit("SCHEME-A", version.id(), "maker");

        assertThrows(ApprovalConflictException.class,
                () -> schemes.approve("SCHEME-A", version.id(), "checker", null), "eligibility rule is still a draft");
        rules.submit(rule.id(), "maker");
        rules.approve(rule.id(), "checker", null);

        assertThrows(AccessDeniedException.class, () -> schemes.approve("SCHEME-A", version.id(), "maker", null));
        assertEquals("APPROVED", schemes.approve("SCHEME-A", version.id(), "checker", null).status());
        assertThrows(IllegalArgumentException.class, () -> schemes.setEligibilityRuleSet(version.id(), rule.id()),
                "an approved version cannot swap its eligibility rule without a new approval");
    }

    @Test
    void pendingItemsReachTheInboxAndMonitoring() {
        String sourceCode = packs.find("state-1").orElseThrow().sourceCode();
        RuleSet draft = rules.createDraft(eligibilityDraft("GO-4"), "maker");
        RuleSet dq = rules.createDraft(new RuleSetService.Draft(Type.DATA_QUALITY, OwnerType.SOURCE_PACK, "state-1",
                LocalDate.of(2026, 1, 1), null, dmn("DQ-1"), List.of("age")), "maker");
        rules.submit(dq.id(), "maker");
        var version = schemes.createDraft(schemeDraft(null), "maker");
        schemes.submit("SCHEME-A", version.id(), "maker");
        execute("insert into matching_profile(profile_id,name,version,status,config,created_by,submitted_by)"
                + " values (gen_random_uuid(),'profile',1,'PENDING_APPROVAL','{}','maker','maker')");

        var inbox = new ExceptionInboxService(List.of(
                new RulesAndMatchingExceptionSources.RuleSets(connections, packs),
                new RulesAndMatchingExceptionSources.SchemePacks(connections, packs),
                new RulesAndMatchingExceptionSources.UncertainMatches(connections),
                new RulesAndMatchingExceptionSources.MatchingAlerts(connections)),
                SampleData.loader(SampleData.STATE_PACKS), Clock.systemUTC());
        Map<String, Integer> all = inbox.counts(null, state -> true);
        assertEquals(1, all.get("RULE_DRAFT"));
        assertEquals(1, all.get("RULE_APPROVAL"));
        assertEquals(1, all.get("SCHEME_APPROVAL"));
        assertEquals(1, all.get("MATCHING_APPROVAL"));
        assertEquals(0, all.get("UNCERTAIN_MATCH"));
        assertTrue(inbox.list(new Filter(null, "RULE_DRAFT", null, null), state -> true).getFirst().item().title()
                .contains(draft.ownerId()));

        Map<String, Integer> scoped = inbox.counts(null, sourceCode::equalsIgnoreCase);
        assertEquals(1, scoped.get("RULE_APPROVAL"), "the pack's own rule is in its department's scope");
        assertEquals(1, scoped.get("SCHEME_APPROVAL"), "the scheme's owning department is in scope");
        assertEquals(0, scoped.get("MATCHING_APPROVAL"), "matching-wide items need ALL scope");

        var pending = new PendingApprovalMetrics(connections, packs).metrics(sourceCode, "any", null, null);
        assertEquals(2.0, pending.get("PENDING_APPROVALS"), "the source pack's rule and its department's scheme");
    }

    @Test
    void matchingDriftIsTheChangeOfTheMatchRate() {
        var drift = new MatchingDriftMetrics(connections);
        assertTrue(drift.metrics("S", "src", null, null).isEmpty());
        metricsRun(0.40, "2026-01-01T00:00:00Z");
        assertTrue(drift.metrics("S", "src", null, null).isEmpty(), "one run has no change yet");
        metricsRun(0.25, "2026-01-02T00:00:00Z");
        assertEquals(-15.0, drift.metrics("S", "src", null, null).get("MATCH_RATE_CHANGE_PCT"), 1e-9);
    }

    private java.util.Optional<RuleSetService.Evaluation> effective() {
        return rules.evaluateIfEffective(Type.ELIGIBILITY, OwnerType.SCHEME, "SCHEME-A", LocalDate.of(2026, 6, 1),
                Map.of("age", 30));
    }

    private static RuleSetService.Draft eligibilityDraft(String clause) {
        return new RuleSetService.Draft(Type.ELIGIBILITY, OwnerType.SCHEME, "SCHEME-A", LocalDate.of(2026, 1, 1),
                null, dmn(clause), List.of("age"));
    }

    private static SchemePackService.Draft schemeDraft(UUID ruleSetId) {
        return new SchemePackService.Draft("SCHEME-A", "Scheme A", "DEPT-1", "INDIVIDUAL", "ENTITLEMENT", "CASH",
                "2026-01-01", null, ruleSetId, List.of("DEPT-1"), List.of(), List.of(), "{}");
    }

    private void metricsRun(double matchRate, String createdAt) {
        UUID run = UUID.randomUUID();
        execute("insert into matching_run(run_id,model_version,threshold,status) values ('" + run
                + "','m',0.9,'COMPLETED')");
        execute("insert into matching_run_metrics(run_id,records_considered,auto_pairs,possible_pairs,no_match_pairs,"
                + "matched_records,match_rate,review_rate,score_histogram,created_at) values ('" + run + "',10,1,1,1,4,"
                + matchRate + ",0.1,'[]','" + createdAt + "')");
    }

    private String decidedBy(String table, String column, UUID id) {
        try (var c = connections.open();
                var s = c.prepareStatement("select decided_by from " + table + " where " + column + "=?")) {
            s.setObject(1, id);
            var r = s.executeQuery();
            r.next();
            return r.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void execute(String sql) {
        try (var c = connections.open(); var s = c.createStatement()) {
            s.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String dmn(String clause) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions" name="rules" namespace="https://uddp.gov/rules">
                  <decision id="decision" name="decision">
                    <decisionTable id="table" hitPolicy="FIRST">
                      <input id="age"><inputExpression id="age-expression" typeRef="integer"><text>age</text></inputExpression></input>
                      <output id="eligible" name="eligible" typeRef="boolean" />
                      <output id="clause" name="clauseReference" typeRef="string" />
                      <rule id="rule"><inputEntry id="age-entry"><text>&gt;= 18</text></inputEntry><outputEntry id="eligible-output"><text>true</text></outputEntry><outputEntry id="clause-output"><text>"%s"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                </definitions>
                """.formatted(clause);
    }
}
