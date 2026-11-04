package gov.uddp.core.scheme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.ai.AiDisabledException;
import gov.uddp.core.ai.AiDraftService;
import gov.uddp.core.ai.AiProperties;
import gov.uddp.core.ai.DisabledAiDraftService;
import gov.uddp.core.ai.FakeAiDraftService;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.rules.RuleSet;
import gov.uddp.core.rules.RuleSetService;
import gov.uddp.core.rules.RuleSetStore;
import gov.uddp.core.testsupport.TestDatabase;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Track A6: a GO PDF becomes a DRAFT eligibility rule set marked for verification; nothing is ever approved. */
class GoEligibilityDraftServiceTest {
    private static final String GOOD = """
            ```json
            {"rows":[
              {"condition":"age >= 60","output":"ELIGIBLE","clauseReference":"GO para 3(a)"},
              {"condition":"annualIncome > 100000","output":"NOT_ELIGIBLE","clauseReference":"GO para 4"}]}
            ```""";

    private PostgresConnectionFactory connections;
    private RuleSetService rules;
    private SchemePackService schemes;
    private UUID versionId;

    @BeforeEach
    void setUp() {
        connections = TestDatabase.freshMigrated();
        rules = new RuleSetService(new RuleSetStore(connections));
        schemes = new SchemePackService(connections, new ObjectMapper());
        versionId = schemes.createDraft(new SchemePackService.Draft("SCHEME-A", "Scheme A", "DEPT-1", "INDIVIDUAL",
                "ENTITLEMENT", "CASH", "2026-01-01", null, null, List.of("DEPT-1"), List.of(), List.of(), "{}"),
                "maker").id();
    }

    @Test
    void aGoBecomesAMarkedDraftLinkedToTheSchemeAndSendsOnlyTheGoText() throws Exception {
        FakeAiDraftService fake = FakeAiDraftService.answering(GOOD);
        var drafts = service(fake);

        var draft = drafts.draftFromGo("SCHEME-A", versionId, "go-12.pdf", pdf("Pension GO para 3(a): age 60 and above."),
                "maker");

        assertEquals(RuleSet.Status.DRAFT, draft.status());
        assertEquals(GoEligibilityDraftService.MARKER, draft.marker());
        assertEquals("fake-model", draft.model());
        assertEquals(List.of("age", "annualIncome"), draft.requiredAttributes());
        assertEquals(GoEligibilityDraftService.MARKER, drafts.marker(draft.ruleSetId()).orElseThrow());
        assertTrue(rules.getById(draft.ruleSetId()).dmnXml().contains(GoEligibilityDraftService.MARKER));
        assertEquals(draft.ruleSetId(), schemes.version("SCHEME-A", versionId).eligibilityRuleSetId());

        assertEquals(1, fake.calls.size());
        assertTrue(fake.calls.get(0).content().contains("age 60 and above"), "the GO text is the only content sent");
        assertEquals(GoEligibilityDraftService.INSTRUCTION, fake.calls.get(0).instruction());

        var fired = rules.evaluateById(draft.ruleSetId(), Map.of("age", 65, "annualIncome", 50000)).firedRules();
        assertEquals(1, fired.size());
        assertEquals("ELIGIBLE", fired.get(0).outputs().get("outcome"));
        assertEquals("GO para 3(a)", fired.get(0).clauseReference());
        assertTrue(rules.list(RuleSet.Type.ELIGIBILITY).stream().noneMatch(r -> r.status() == RuleSet.Status.APPROVED),
                "nothing is approved automatically");
    }

    @Test
    void invalidModelOutputIsRejectedAndNothingIsStored() throws Exception {
        for (String bad : List.of("not json", "{\"rows\":[]}",
                "{\"rows\":[{\"condition\":\"age >= 60\",\"output\":\"ELIGIBLE\"}]}",
                "{\"rows\":[{\"condition\":\"age >=\",\"output\":\"ELIGIBLE\",\"clauseReference\":\"GO 1\"}]}")) {
            var drafts = service(FakeAiDraftService.answering(bad));
            byte[] go = pdf("GO text");
            assertThrows(IllegalArgumentException.class,
                    () -> drafts.draftFromGo("SCHEME-A", versionId, "go.pdf", go, "maker"), bad);
        }
        assertTrue(rules.list(RuleSet.Type.ELIGIBILITY).isEmpty());
        assertEquals(null, schemes.version("SCHEME-A", versionId).eligibilityRuleSetId());
    }

    @Test
    void disabledWhenNotConfigured() throws Exception {
        assertFalse(new AiProperties().configured(), "off by default");
        var drafts = service(new DisabledAiDraftService());
        assertFalse(drafts.enabled());
        byte[] go = pdf("GO text");
        assertThrows(AiDisabledException.class, () -> drafts.draftFromGo("SCHEME-A", versionId, "go.pdf", go, "maker"));
        assertTrue(rules.list(RuleSet.Type.ELIGIBILITY).isEmpty());
    }

    private GoEligibilityDraftService service(AiDraftService ai) {
        return new GoEligibilityDraftService(ai, rules, schemes, connections, new ObjectMapper());
    }

    private static byte[] pdf(String text) throws Exception {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
