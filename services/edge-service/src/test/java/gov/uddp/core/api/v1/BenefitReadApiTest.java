package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * M15 acceptance through the read API: Ram Singh's Resident 360 shows PDS and pension benefits from different states
 * and departments in one view, the duplicate-benefit report finds the seeded duplicate, and the eligible-but-not-enrolled
 * report evaluates the catalogue's declarative eligibility.
 */
class BenefitReadApiTest extends ReadApiFixture {
    @Test
    void ramSinghsResident360ShowsPdsAndPensionsFromDifferentStatesInOneView() throws Exception {
        UUID ram = sql().goldenOf(sql().canonicalsNamed("Ram Singh").getFirst());
        var view = read("/api/v1/golden-residents/{id}/benefits", ram);

        assertEquals("Ram Singh", view.path("name").asText());
        assertEquals("ACTIVE", view.path("status").asText());
        assertEquals(3, view.path("benefitCount").asInt());
        assertEquals(List.of("FOOD_CIVIL_SUPPLIES", "SOCIAL_WELFARE"), texts(view.path("departments")));
        assertEquals(List.of("OLD-AGE-PENSION", "PDS-RATION"), texts(view.path("schemes")));
        assertEquals(3, view.path("states").size(), "one benefit from each of three states");

        JsonNode pds = null;
        List<String> pensionStates = new ArrayList<>();
        for (var held : view.path("benefits")) {
            var benefit = held.path("benefit");
            assertEquals("ACTIVE", benefit.path("status").asText());
            assertFalse(benefit.path("lastDisbursement").isNull(), "every sample benefit reports a disbursement");
            for (var link : held.path("lineage")) {
                // Lineage: the observation's raw record is readable through the raw record API.
                read("/api/v1/raw-records/{id}", link.path("rawRecordId").asText());
            }
            if (benefit.path("schemeId").asText().equals("PDS-RATION")) {
                pds = held;
            } else {
                assertEquals("Social Welfare", benefit.path("departmentName").asText());
                assertEquals(List.of("RESIDENT"), texts(held.path("linkedVia")));
                assertEquals("INR", benefit.path("entitlement").path("unit").asText());
                pensionStates.add(benefit.path("stateCode").asText());
            }
        }
        assertEquals(2, pensionStates.stream().distinct().count(), "old age pensions from two states");
        // The PDS ration is held by Ram's household and by Ram himself as a member named on the card.
        assertEquals(List.of("FAMILY", "RESIDENT"), texts(pds.path("linkedVia")));
        assertEquals("Food and Civil Supplies", pds.path("benefit").path("departmentName").asText());
        assertEquals("KG", pds.path("benefit").path("entitlement").path("unit").asText());
        assertEquals(25, pds.path("benefit").path("entitlement").path("quantity").asInt());
        String household = pds.path("goldenFamilyId").asText();
        assertTrue(read("/api/v1/golden-residents/{id}/families", ram).findValuesAsText("goldenFamilyId")
                .contains(household), "the PDS household is one of Ram's Golden Families");

        // Sita Singh, the other R1001 member, sees the same household ration.
        UUID sita = sql().goldenOf(sql().canonicalsNamed("Sita Singh").getFirst());
        var sitas = read("/api/v1/golden-residents/{id}/benefits", sita);
        assertEquals(1, sitas.path("benefitCount").asInt());
        assertEquals(pds.path("benefit").path("benefitId"), sitas.path("benefits").get(0).path("benefit").path("benefitId"));

        mvc.perform(get("/api/v1/golden-residents/{id}/benefits", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void listsFiltersPagesAndDetailsBenefits() throws Exception {
        var all = read("/api/v1/benefits");
        assertEquals(5, all.path("totalItems").asInt());
        var ids = all.path("items").findValuesAsText("sourceBenefitId");
        assertEquals(ids.stream().sorted().toList(), ids);
        assertEquals(3, read("/api/v1/benefits?schemeId=OLD-AGE-PENSION").path("totalItems").asInt());
        assertEquals(2, read("/api/v1/benefits?department=FOOD_CIVIL_SUPPLIES").path("totalItems").asInt());
        assertEquals(5, read("/api/v1/benefits?status=ACTIVE").path("totalItems").asInt());
        assertEquals(0, read("/api/v1/benefits?status=ENDED").path("totalItems").asInt());
        UUID ram = sql().goldenOf(sql().canonicalsNamed("Ram Singh").getFirst());
        assertEquals(3, read("/api/v1/benefits?goldenResidentId={id}", ram).path("totalItems").asInt());
        String pack = firstPackId();
        var inState = read("/api/v1/benefits?state={s}", pack);
        assertTrue(inState.path("totalItems").asInt() > 0);
        inState.path("items").forEach(b -> assertEquals(stateCodeOf(pack), b.path("stateCode").asText()));
        var page = read("/api/v1/benefits?size=2&page=1&sort=schemeId,desc");
        assertEquals(2, page.path("items").size());
        assertEquals(3, page.path("totalPages").asInt());

        mvc.perform(get("/api/v1/benefits").param("status", "PAID")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("status must be ACTIVE, SUSPENDED, CLOSED, ENDED or ALL"));
        mvc.perform(get("/api/v1/benefits").param("department", "DEFENCE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/benefits").param("sort", "amount")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/benefits").param("goldenResidentId", "not-a-uuid")).andExpect(status().isBadRequest());

        // The household ration R1001: two members name it, its Golden Family holds it, one lifting reported twice.
        var r1001 = read("/api/v1/benefits?schemeId=PDS-RATION&size=1").path("items").get(0);
        var detail = read("/api/v1/benefits/{id}", r1001.path("benefitId").asText());
        assertEquals("FAMILY", detail.path("benefit").path("beneficiaryLevel").asText());
        assertEquals(2, detail.path("beneficiaries").size());
        assertEquals(List.of("Ram Singh", "Sita Singh"), detail.path("beneficiaries").findValuesAsText("name")
                .stream().sorted().toList());
        assertEquals(List.of("Ram S.", "Sita S."), readAsProgramUser(gov.uddp.core.security.Permission.BENEFIT_VIEW, "/api/v1/benefits/{id}",
                r1001.path("benefitId").asText()).path("beneficiaries").findValuesAsText("name").stream().sorted().toList(),
                "a PROGRAM_USER sees masked names");
        assertEquals(2, detail.path("household").path("memberGoldenResidentIds").size());
        assertEquals(detail.path("benefit").path("goldenFamilyId"), detail.path("household").path("goldenFamilyId"));
        assertEquals(2, detail.path("observations").size());
        detail.path("observations").forEach(o -> assertTrue(o.path("current").asBoolean()));
        assertEquals(1, detail.path("disbursements").size());
        mvc.perform(get("/api/v1/benefits/{id}", UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void theCatalogueAndTheDuplicateReportFindTheSeededDuplicate() throws Exception {
        var catalogue = read("/api/v1/schemes");
        assertEquals("1.0.0", catalogue.path("catalogueVersion").asText());
        assertEquals(2, catalogue.path("departments").size());
        assertEquals(List.of("PDS-RATION:2", "OLD-AGE-PENSION:3", "DISABILITY-PENSION:0"), catalogue.path("schemes")
                .findValuesAsText("schemeId").stream().map(id -> id + ":" + count(catalogue, id)).toList());

        var report = read("/api/v1/reports/duplicate-benefits");
        assertEquals(1, report.path("findingCount").asInt());
        var finding = report.path("findings").get(0);
        UUID ram = sql().goldenOf(sql().canonicalsNamed("Ram Singh").getFirst());
        assertEquals(ram.toString(), finding.path("goldenResidentId").asText());
        assertEquals("Ram Singh", finding.path("name").asText());
        assertEquals("SOCIAL-PENSION", finding.path("exclusivityGroup").asText());
        assertEquals(List.of("OLD-AGE-PENSION"), texts(finding.path("schemeIds")));
        assertEquals(2, finding.path("stateCodes").size());
        assertEquals(2, finding.path("benefits").size());
        assertEquals(2, finding.path("benefits").findValuesAsText("sourceBenefitId").stream().distinct().count());
    }

    @Test
    void eligibleButNotEnrolledFollowsTheCatalogueEligibilityAndTheAsOfDate() throws Exception {
        var report = read("/api/v1/reports/eligible-not-enrolled?asOf=2026-10-25");
        assertEquals("2026-10-25", report.path("asOf").asText());
        assertEquals(1, report.path("schemes").size(), "only the old age pension declares eligibility");
        var pension = report.path("schemes").get(0);
        assertEquals("OLD-AGE-PENSION", pension.path("schemeId").asText());
        assertEquals(45, pension.path("eligibility").path("minAgeYears").asInt());
        assertEquals(5, pension.path("residentsEvaluated").asInt());
        assertEquals(3, pension.path("eligible").asInt(), "Ram Singh 46, Ramesh Kumar 51, Anita Devi 48");
        assertEquals(2, pension.path("enrolled").asInt());
        assertEquals(1, pension.path("notEnrolledCount").asInt());
        var ramesh = pension.path("notEnrolled").get(0);
        assertEquals("Ramesh Kumar", ramesh.path("name").asText());
        assertEquals(51, ramesh.path("ageYears").asInt());
        assertEquals(1, ramesh.path("stateCodes").size());

        // Ages are computed on asOf: on 2020-01-01 only Ramesh Kumar (45 that day) is eligible.
        var earlier = read("/api/v1/reports/eligible-not-enrolled?schemeId=OLD-AGE-PENSION&asOf=2020-01-01")
                .path("schemes").get(0);
        assertEquals(1, earlier.path("eligible").asInt());
        assertEquals(0, earlier.path("enrolled").asInt());
        assertEquals("Ramesh Kumar", earlier.path("notEnrolled").get(0).path("name").asText());

        mvc.perform(get("/api/v1/reports/eligible-not-enrolled").param("asOf", "25-10-2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("asOf must be an ISO date (YYYY-MM-DD)"));
        mvc.perform(get("/api/v1/reports/eligible-not-enrolled").param("schemeId", "NOPE"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/eligible-not-enrolled").param("schemeId", "PDS-RATION"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Scheme PDS-RATION declares no eligibility"));
    }

    private static int count(JsonNode catalogue, String schemeId) {
        for (var scheme : catalogue.path("schemes")) {
            if (scheme.path("schemeId").asText().equals(schemeId)) {
                return scheme.path("benefitCount").asInt();
            }
        }
        return -1;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }
}
