package gov.uddp.core.scheme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.scheme.SchemePackService.CodeCrosswalk;
import gov.uddp.core.scheme.SchemePackService.Details;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Track A4: classification, GO reference, amount/quantity/frequency and code crosswalks are versioned with a scheme. */
class SchemePackDetailsTest {
    @Test
    void detailsAreStoredVersionedAndImportedFromThePacksCrosswalks() throws Exception {
        var db = TestDatabase.freshMigrated();
        var service = new SchemePackService(db, new ObjectMapper());
        Details details = new Details("CENTRALLY_SPONSORED", "GO Ms 12/2026", "ENTITLEMENT", "CASH", "INR", "MONTHLY",
                new BigDecimal("1000.00"), null, List.of(new CodeCrosswalk("state-2", "VRIDHA")));

        var draft = service.createDraft(new SchemePackService.Draft("SCHEME-D", "Scheme D", "DEPT-1", "INDIVIDUAL",
                "ENTITLEMENT", "CASH", "2026-01-01", null, null, List.of("DEPT-1"), List.of(), List.of(), "{}", details),
                "maker");
        assertEquals(details, draft.details());

        var edited = new Details("STATE", "GO Ms 13/2026", "ENTITLEMENT", "IN_KIND", "KG", "MONTHLY", null,
                new BigDecimal("35.000"), List.of(new CodeCrosswalk("state-1", "AAY")));
        var updated = service.updateDraft(draft.id(), new SchemePackService.Draft("SCHEME-D", "Scheme D", "DEPT-1",
                "INDIVIDUAL", "ENTITLEMENT", "IN_KIND", "2026-01-01", null, null, List.of("DEPT-1"), List.of(),
                List.of(), "{}", edited));
        assertEquals(edited, updated.details());

        var copy = service.newVersion("SCHEME-D", draft.id(), "maker");
        assertEquals(2, copy.version());
        assertEquals(edited, copy.details(), "a new version carries every detail, benefit form included");

        assertThrows(IllegalStateException.class, () -> service.createDraft(new SchemePackService.Draft("SCHEME-E",
                "Scheme E", "DEPT-1", "INDIVIDUAL", "ENTITLEMENT", "CASH", "2026-01-01", null, null, List.of("DEPT-1"),
                List.of(), List.of(), "{}", new Details("LOCAL", null, null, null, null, null, null, null, null)), "maker"));

        assertEquals(3, service.importCatalogue(new SchemeCatalogue(SampleData.SCHEME_CATALOGUE)));
        int added = service.importCodeCrosswalks(SampleData.loader(SampleData.STATE_PACKS).all());
        assertTrue(added >= 5, "PHH, AAY, VRIDHA, OAP and DIVYANG");
        assertEquals(0, service.importCodeCrosswalks(SampleData.loader(SampleData.STATE_PACKS).all()), "imported once");
        var pension = service.history("OLD-AGE-PENSION").get(0).details();
        assertTrue(pension.codeCrosswalks().stream().map(CodeCrosswalk::sourceCode).toList().containsAll(List.of("VRIDHA", "OAP")));
        assertEquals("INR", pension.unit());
    }
}
