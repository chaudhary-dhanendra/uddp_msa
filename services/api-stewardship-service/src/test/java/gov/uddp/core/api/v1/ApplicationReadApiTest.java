package gov.uddp.core.api.v1;

import static gov.uddp.core.testsupport.ApplicationFixture.HEADER;
import static gov.uddp.core.testsupport.ApplicationFixture.RULES;
import static gov.uddp.core.testsupport.ApplicationFixture.pack;
import static gov.uddp.core.testsupport.ApplicationFixture.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gov.uddp.core.canonical.CanonicalApplicationProcessor;
import gov.uddp.core.canonical.CanonicalApplicationProcessor.Outcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Applications through the read API. The shared fixture data must not change, so the synthetic deliveries reuse
 * existing raw records (one State Pack version per delivery) and only add rows to the application tables.
 */
class ApplicationReadApiTest extends ReadApiFixture {
    @Autowired
    CanonicalApplicationProcessor processor;

    @Test
    void anApplicationAndAResidentsApplicationsAreReadWithStageHistoryAndLineage() throws Exception {
        UUID ram = sql().goldenOf(sql().canonicalsNamed("Ram Singh").getFirst());
        String applicant = sql().text("select source_record_key from golden_resident_membership"
                + " where golden_resident_id = ? and effective_to is null order by source_record_key limit 1", ram);
        var raws = sql().column("select raw_record_id from raw_record order by raw_record_id limit 3", UUID.class);
        UUID batch = sql().uuid("select batch_id from raw_record where raw_record_id = ?", raws.getFirst());
        String state = sql().text("select state_code from raw_record where raw_record_id = ?", raws.getFirst());
        String[] lines = {
            "RA-1,OAP," + applicant + ",VERIFICATION,PENDING,APPROVAL,20260105,20260105,online,portal",
            "RA-1,OAP," + applicant + ",APPROVAL,PENDING,PAYMENT,20260105,20260112,online,portal",
            "RA-1,OAP," + applicant + ",APPROVAL,PENDING,PAYMENT,20260105,20260119,online,portal"};
        for (int i = 0; i < lines.length; i++) {
            var outcome = processor.observe(raw(raws.get(i), batch, state, HEADER, lines[i]),
                    pack("app-test-" + i, RULES), applicant, null);
            assertEquals(Outcome.OBSERVED, outcome);
        }
        UUID id = sql().uuid("select application_id from application where source_application_id = 'RA-1'");

        var detail = read("/api/v1/applications/{id}", id);
        var application = detail.path("application");
        assertEquals("OLD-AGE-PENSION", application.path("schemeId").asText());
        assertEquals("SOCIAL_WELFARE", application.path("departmentCode").asText());
        assertEquals("APPROVAL", application.path("currentStage").asText());
        assertEquals("PAYMENT", application.path("nextStage").asText());
        assertEquals("2026-01-19", application.path("lastActionDate").asText());
        assertEquals("2026-01-05", application.path("submittedOn").asText());
        assertEquals("online", application.path("attributes").path("mode").asText());
        assertEquals(ram.toString(), detail.path("goldenResidentId").asText());
        assertEquals(List.of("VERIFICATION", "APPROVAL"), detail.path("stageEvents").findValuesAsText("stage"));
        assertEquals(3, detail.path("observations").size());
        for (var event : detail.path("stageEvents")) {
            read("/api/v1/raw-records/{id}", event.path("rawRecordId").asText());
        }

        var residents = read("/api/v1/golden-residents/{id}/applications", ram);
        assertEquals(1, residents.path("applicationCount").asInt());
        assertEquals(id.toString(), residents.path("applications").get(0).path("applicationId").asText());
        UUID other = sql().goldenOf(sql().canonicalsNamed("Sunita Devi").getFirst());
        assertEquals(0, read("/api/v1/golden-residents/{id}/applications", other).path("applicationCount").asInt());

        mvc.perform(get("/api/v1/applications/{id}", UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(get("/api/v1/golden-residents/{id}/applications", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
