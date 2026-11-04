package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M14 read API: Golden Families, their members with lineage, related households, history, and a Resident's households. */
class FamilyReadApiTest extends ReadApiFixture {
    @Test
    void listsFiltersAndPagesGoldenFamilies() throws Exception {
        var all = read("/api/v1/families");
        assertEquals(6, all.path("totalItems").asInt());
        var ids = all.path("items").findValuesAsText("sourceFamilyId");
        assertEquals(ids.stream().sorted().toList(), ids);
        all.path("items").forEach(family -> {
            assertEquals("ACTIVE", family.path("status").asText());
            assertEquals(1, family.path("versionNumber").asInt());
        });

        String pack = firstPackId();
        var inState = read("/api/v1/families?state={s}", pack);
        assertTrue(inState.path("totalItems").asInt() > 0);
        inState.path("items").forEach(family -> assertEquals(stateCodeOf(pack), family.path("stateCode").asText()));
        assertEquals(inState.path("totalItems"), read("/api/v1/families?state={s}", stateCodeOf(pack)).path("totalItems"));

        String first = ids.getFirst();
        assertEquals(1, read("/api/v1/families?q={q}", first.toLowerCase()).path("totalItems").asInt());
        var largest = read("/api/v1/families?size=1&sort=memberCount,desc");
        assertEquals(1, largest.path("items").size());
        assertEquals(6, largest.path("totalPages").asInt());
        assertEquals(2, largest.path("items").get(0).path("memberCount").asInt());
        assertEquals(0, read("/api/v1/families?status=RETIRED").path("totalItems").asInt());

        mvc.perform(get("/api/v1/families").param("status", "GONE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("status must be ACTIVE, RETIRED or ALL"));
        mvc.perform(get("/api/v1/families").param("sort", "secret")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/families").param("size", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void familyDetailShowsMembersWithLineageRelatedHouseholdsAndHistory() throws Exception {
        UUID ram = sql().goldenOf(sql().canonicalsNamed("Ram Singh").getFirst());
        var households = read("/api/v1/golden-residents/{id}/families", ram);
        assertEquals(3, households.size(), "Ram Singh belongs to one household in each of three states");
        assertEquals(households.size(), read("/api/v1/families?goldenResidentId={id}", ram).path("totalItems").asInt());

        var largest = read("/api/v1/families?size=1&sort=memberCount,desc").path("items").get(0);
        var detail = read("/api/v1/families/{id}", largest.path("goldenFamilyId").asText());
        assertEquals(1, detail.path("currentVersion").asInt());
        assertEquals(2, detail.path("members").size());
        assertEquals(2, detail.path("payload").path("memberCount").asInt());
        var names = detail.path("members").findValuesAsText("name");
        assertTrue(names.contains("Ram Singh"), names.toString());
        var masked = readAsProgramUser(gov.uddp.core.security.Permission.FAMILY_VIEW, "/api/v1/families/{id}", largest.path("goldenFamilyId").asText())
                .path("members").findValuesAsText("name");
        assertTrue(masked.contains("Ram S.") && !masked.contains("Ram Singh"), "a PROGRAM_USER sees masked names");
        for (var member : detail.path("members")) {
            assertEquals("UNKNOWN", member.path("relationshipType").asText());
            assertEquals("ACTIVE", member.path("goldenResidentStatus").asText());
            // Lineage: the canonical Resident and raw record of the delivery that placed the member here.
            UUID canonical = UUID.fromString(member.path("canonicalResidentId").asText());
            assertEquals(member.path("rawRecordId").asText(), sql().uuid(
                    "select raw_record_id from canonical_resident where canonical_resident_id=?", canonical).toString());
            read("/api/v1/raw-records/{id}", member.path("rawRecordId").asText());
        }
        // Ram's two other households are related to this one through his Golden Resident.
        assertEquals(2, detail.path("relatedFamilies").size());
        detail.path("relatedFamilies").forEach(related ->
                assertEquals(ram.toString(), related.path("sharedGoldenResidentIds").get(0).asText()));
        assertEquals(1, detail.path("versions").size());

        var history = read("/api/v1/families/{id}/history", largest.path("goldenFamilyId").asText());
        assertEquals(1, history.path("versions").size());
        assertEquals(2, history.path("versions").get(0).path("members").size());
        assertEquals("CREATED", history.path("events").get(0).path("eventType").asText());
        assertEquals(2, history.path("events").findValuesAsText("eventType").stream()
                .filter("MEMBER_ADDED"::equals).count());
    }

    @Test
    void unknownIdsAre404() throws Exception {
        UUID unknown = UUID.randomUUID();
        mvc.perform(get("/api/v1/families/{id}", unknown)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(get("/api/v1/families/{id}/history", unknown)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/golden-residents/{id}/families", unknown)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/families/not-a-uuid")).andExpect(status().isBadRequest());
    }
}
