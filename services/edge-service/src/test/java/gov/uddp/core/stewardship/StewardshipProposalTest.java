package gov.uddp.core.stewardship;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenResidentService;
import gov.uddp.core.stewardship.StewardshipProposalService.Proposal;
import gov.uddp.core.stewardship.StewardshipProposalService.ProposalRequest;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * P1 Part 4b, stewardship maker-checker: a proposal changes no Golden data; only a different user's approval applies
 * it (with the existing decision logic and Golden rebuild); a rejection needs a reason and changes nothing; the
 * proposer can decide nothing; a proposal whose Golden Residents changed since it was made is refused as stale; with
 * maker-checker off, direct decisions work as before. Sample data: Ram Singh x3 AUTO_MATCH, Sita/Sunita POSSIBLE.
 */
class StewardshipProposalTest {
    private PostgresConnectionFactory connections;
    private Sql sql;
    private StewardshipService stewardship;
    private StewardshipProposalService proposals;

    @BeforeEach
    void sample() {
        connections = TestDatabase.freshMigrated();
        sql = new Sql(connections);
        var loader = SampleData.loader(SampleData.STATE_PACKS);
        var processing = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
        GoldenResidentService golden = SampleData.golden(connections, loader);
        stewardship = SampleData.stewardship(connections, golden);
        proposals = new StewardshipProposalService(connections, stewardship, new ObjectMapper().findAndRegisterModules(),
                true);
        Set<UUID> ram = Set.copyOf(sql.canonicalsNamed("Ram Singh"));
        Set<UUID> pair = Set.of(sql.canonicalsNamed("Sita Singh").getFirst(), sql.canonicalsNamed("Sunita Devi").getFirst());
        golden.build(MatchingRunFixture.completed(connections, List.of(ram), List.of(pair)));
    }

    @Test
    void aProposalChangesNothingAndADifferentUsersApprovalAppliesIt() {
        String before = goldenState();
        Proposal proposal = proposals.propose(new ProposalRequest("ACCEPT_MATCH", task(), null, null, "Same person"),
                "asha.steward");
        assertEquals("PENDING_APPROVAL", proposal.status());
        assertEquals(2, proposal.goldenResidentIds().size());
        assertEquals(before, goldenState(), "a proposal changes no Golden data");
        assertEquals(0, sql.count("select count(*) from stewardship_decision"));

        var result = proposals.approve(proposal.proposalId(), "ravi.steward", "Checked the documents");

        assertEquals("ACCEPT_MATCH", result.action());
        assertEquals("asha.steward", result.reviewer(), "the decision is the proposer's");
        assertEquals(1, result.goldenBuild().merged());
        assertEquals(4, active());
        Proposal approved = proposals.find(proposal.proposalId());
        assertEquals("APPROVED", approved.status());
        assertEquals("ravi.steward", approved.decidedBy());
        assertEquals(result.decisionId(), approved.decisionId());
        assertThrows(StewardshipConflictException.class,
                () -> proposals.approve(proposal.proposalId(), "ravi.steward", null), "decided once only");
    }

    @Test
    void aRejectionNeedsAReasonAndChangesNothing() {
        String before = goldenState();
        Proposal proposal = proposals.propose(new ProposalRequest("MERGE", null,
                List.of(goldenOf("Ram Singh"), goldenOf("Sita Singh")), null, "Looks alike"), "asha.steward");
        assertThrows(IllegalArgumentException.class, () -> proposals.reject(proposal.proposalId(), "ravi.steward", " "));

        Proposal rejected = proposals.reject(proposal.proposalId(), "ravi.steward", "Different fathers");

        assertEquals("REJECTED", rejected.status());
        assertEquals("Different fathers", rejected.decisionReason());
        assertEquals(before, goldenState());
        assertEquals(0, sql.count("select count(*) from stewardship_decision"));
    }

    @Test
    void theProposerCanNeitherApproveNorReject() {
        Proposal proposal = proposals.propose(new ProposalRequest("REJECT_MATCH", task(), null, null, "Different"),
                "asha.steward");
        assertThrows(AccessDeniedException.class, () -> proposals.approve(proposal.proposalId(), "asha.steward", null));
        assertThrows(AccessDeniedException.class, () -> proposals.approve(proposal.proposalId(), "ASHA.STEWARD", null));
        assertThrows(AccessDeniedException.class,
                () -> proposals.reject(proposal.proposalId(), "asha.steward", "changed my mind"));
        assertEquals("PENDING_APPROVAL", proposals.find(proposal.proposalId()).status());
        assertEquals(0, sql.count("select count(*) from stewardship_decision"));
    }

    @Test
    void aProposalWhoseGoldenResidentsChangedIsStale() {
        UUID ram = goldenOf("Ram Singh");
        List<String> keys = sql.column("select source_record_key from golden_resident_membership"
                + " where golden_resident_id = ? and effective_to is null order by source_record_key", String.class, ram);
        Proposal proposal = proposals.propose(new ProposalRequest("SPLIT", null, List.of(ram), List.of(keys.getFirst()),
                "Two people"), "asha.steward");
        // Meanwhile another decision changes Ram Singh's members.
        stewardship.split(ram, List.of(keys.getLast()), "someone.else", "Another split");
        String after = goldenState();

        assertThrows(StewardshipConflictException.class,
                () -> proposals.approve(proposal.proposalId(), "ravi.steward", null));

        assertEquals("STALE", proposals.find(proposal.proposalId()).status());
        assertEquals(after, goldenState(), "a stale proposal applies nothing");
        assertEquals(1, sql.count("select count(*) from stewardship_decision"));
    }

    @Test
    void withMakerCheckerOffDirectDecisionsWorkAsBefore() {
        assertThrows(StewardshipConflictException.class, proposals::requireDirectAllowed, "on by default");
        var direct = new StewardshipProposalService(connections, stewardship, new ObjectMapper(), false);
        assertDoesNotThrow(direct::requireDirectAllowed);

        var result = stewardship.decide(task(), "ACCEPT", "asha.steward", "Same person");

        assertEquals("ACCEPT_MATCH", result.action());
        assertEquals(4, active());
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

    private String goldenState() {
        return sql.text("select string_agg(g.golden_resident_id || g.status || coalesce(m.keys, ''), ','"
                + " order by g.golden_resident_id) from golden_resident g left join (select golden_resident_id,"
                + " string_agg(source_record_key, '|' order by source_record_key) as keys from golden_resident_membership"
                + " where effective_to is null group by golden_resident_id) m on m.golden_resident_id = g.golden_resident_id");
    }
}
