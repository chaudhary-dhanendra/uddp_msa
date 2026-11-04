package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.security.Permission;
import gov.uddp.core.security.UddpPrincipal;
import gov.uddp.core.security.UserAccountStore;
import gov.uddp.core.testsupport.TestSecurity;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * P1 Part 5a: resident data is masked in every response (first name + initial, birth year, last four mobile digits,
 * district only); only a user with RESIDENT_VIEW_FULL can reveal one resident's 360 unmasked, for a configured
 * purpose, and every reveal is audited with that purpose.
 */
class PiiMaskingApiTest extends ReadApiFixture {
    static final UddpPrincipal PROGRAM_USER = TestSecurity.principal("pat.program", Set.of("PROGRAM_USER"),
            Set.of(Permission.RESIDENT_VIEW, Permission.FAMILY_VIEW, Permission.BENEFIT_VIEW, Permission.REPORT_VIEW),
            List.of("ALL"));
    static final UddpPrincipal STEWARD = TestSecurity.principal("sue.steward", Set.of("DATA_STEWARD"),
            Set.of(Permission.RESIDENT_VIEW, Permission.RESIDENT_VIEW_FULL, Permission.FAMILY_VIEW,
                    Permission.BENEFIT_VIEW), List.of("ALL"));

    @Autowired
    UserAccountStore users;

    @Test
    void onlyPlatformAdministratorsAndStewardsMayRevealFullData() {
        for (var role : users.roles()) {
            assertEquals(Set.of("PLATFORM_ADMIN", "DATA_STEWARD").contains(role.roleCode()),
                    role.permissions().contains("RESIDENT_VIEW_FULL"), role.roleCode());
        }
    }

    @Test
    void aProgramUserSeesMaskedValuesOnTheListThe360AndBenefits() throws Exception {
        UUID ram = ramSingh();
        String list = call(MockMvcRequestBuilders.get("/api/v1/golden-residents?q=Ram"), PROGRAM_USER, 200);
        String detail = call(MockMvcRequestBuilders.get("/api/v1/golden-residents/" + ram), PROGRAM_USER, 200);
        String benefits = call(MockMvcRequestBuilders.get("/api/v1/golden-residents/" + ram + "/benefits"),
                PROGRAM_USER, 200);
        for (String body : List.of(list, detail, benefits)) {
            assertNoRealPersonalData(body);
        }
        JsonNode item = json.readTree(list).path("items").get(0);
        assertEquals("Ram S.", item.path("name").asText());
        assertTrue(item.path("mobile").asText().matches("\\*+\\d{4}"), item.toString());
        assertTrue(item.path("dateOfBirth").asText().matches("\\d{4}"), item.toString());
        JsonNode resident = json.readTree(detail);
        assertEquals("Ram S.", resident.path("payload").path("name").asText());
        for (JsonNode member : resident.path("members")) {
            assertEquals("Ram S.", member.path("name").asText());
        }
        JsonNode address = resident.path("payload").path("address");
        address.fieldNames().forEachRemaining(key -> assertTrue(key.toLowerCase().startsWith("district"), key));
        assertEquals("Ram S.", json.readTree(benefits).path("name").asText());
    }

    @Test
    void aStewardSeesMaskedValuesUntilRevealingWithAPurposeWhichIsAudited() throws Exception {
        UUID ram = ramSingh();
        assertNoRealPersonalData(call(MockMvcRequestBuilders.get("/api/v1/golden-residents/" + ram), STEWARD, 200));
        assertTrue(json.readTree(call(MockMvcRequestBuilders.get("/api/v1/privacy/reveal-purposes"), STEWARD, 200))
                .toString().contains("Duplicate investigation"));

        String revealed = call(reveal(ram, "{\"purpose\":\"Duplicate investigation\",\"note\":\"Ticket 42\"}"),
                STEWARD, 200);

        assertEquals("Ram Singh", json.readTree(revealed).path("payload").path("name").asText());
        assertTrue(realMobiles().stream().anyMatch(revealed::contains), "the full mobile number is shown");
        assertEquals(1, sql().count("select count(*) from app_audit_event where action = 'RESIDENT_REVEAL'"
                + " and result = 'SUCCESS' and actor_username = 'sue.steward' and resource_id = ?"
                + " and metadata->>'purpose' = 'Duplicate investigation' and metadata->>'note' = 'Ticket 42'",
                ram.toString()));
        // The reveal is for that response only: the next ordinary read is masked again.
        assertNoRealPersonalData(call(MockMvcRequestBuilders.get("/api/v1/golden-residents/" + ram), STEWARD, 200));
    }

    @Test
    void aRevealNeedsThePermissionAndAConfiguredPurpose() throws Exception {
        UUID ram = ramSingh();
        call(reveal(ram, "{\"purpose\":\"Duplicate investigation\"}"), PROGRAM_USER, 403);
        call(reveal(ram, "{}"), STEWARD, 400);
        call(reveal(ram, "{\"purpose\":\"  \"}"), STEWARD, 400);
        call(reveal(ram, "{\"purpose\":\"Curiosity\"}"), STEWARD, 400);
        assertEquals(0, sql().count("select count(*) from app_audit_event where action = 'RESIDENT_REVEAL'"
                + " and result = 'SUCCESS' and actor_username = 'pat.program'"));
    }

    private void assertNoRealPersonalData(String body) {
        assertFalse(body.contains("Ram Singh"), body);
        for (String mobile : realMobiles()) {
            assertFalse(body.contains(mobile), "mobile " + mobile + " in " + body);
        }
        for (String birth : sql().column("select distinct canonical_payload->>'dateOfBirth' from canonical_resident"
                + " where canonical_payload->>'dateOfBirth' is not null", String.class)) {
            assertFalse(body.contains("\"" + birth + "\""), "date of birth " + birth + " in " + body);
        }
    }

    private List<String> realMobiles() {
        return sql().column("select distinct canonical_payload->>'mobile' from canonical_resident"
                + " where coalesce(canonical_payload->>'mobile', '') <> ''", String.class);
    }

    private UUID ramSingh() {
        return sql().goldenOf(sql().canonicalsNamed("Ram Singh").getFirst());
    }

    private static MockHttpServletRequestBuilder reveal(UUID id, String body) {
        return MockMvcRequestBuilders.post("/api/v1/golden-residents/" + id + "/reveal")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String call(MockHttpServletRequestBuilder request, UddpPrincipal principal, int status) throws Exception {
        var result = mvc.perform(request.with(TestSecurity.as(principal))).andReturn();
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return result.getResponse().getContentAsString();
    }
}
