package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M12 read API: Golden search and the Golden 360 view (values with provenance, members, lineage, history). */
class GoldenReadApiTest extends ReadApiFixture {
    @Test
    void searchByNameMobileDistrictAndStateWithPaging() throws Exception {
        var all = read("/api/v1/golden-residents");
        assertEquals(5, all.path("totalItems").asInt());
        var names = all.path("items").findValuesAsText("name");
        assertEquals(names.stream().sorted().toList(), names);

        var ram = read("/api/v1/golden-residents?q=ram singh");
        assertEquals(1, ram.path("totalItems").asInt());
        var item = ram.path("items").get(0);
        assertEquals(3, item.path("memberCount").asInt());
        assertEquals(3, item.path("stateCodes").size());
        assertEquals("ACTIVE", item.path("status").asText());

        String mobile = item.path("mobile").asText();
        assertEquals(1, read("/api/v1/golden-residents?mobile={m}", mobile.substring(mobile.length() - 4))
                .path("totalItems").asInt());
        String district = item.path("districtLGD").asText();
        long sameDistrict = sql().count("select count(*) from golden_resident_version v join golden_resident g"
                + " on g.golden_resident_id = v.golden_resident_id and g.status = 'ACTIVE'"
                + " where v.effective_to is null and v.golden_payload#>>'{address,districtLGD}' = ?", district);
        assertEquals(sameDistrict, read("/api/v1/golden-residents?district={d}", district).path("totalItems").asLong());
        for (var pack : read("/api/v1/states")) {
            var inState = read("/api/v1/golden-residents?state={s}", pack.path("packId").asText());
            assertTrue(inState.path("items").findValuesAsText("name").contains("Ram Singh"));
            inState.path("items").forEach(golden -> assertTrue(golden.path("stateCodes").toString()
                    .contains(pack.path("stateCode").asText())));
        }
        assertEquals(2, read("/api/v1/golden-residents?size=2&sort=memberCount,desc").path("items").size());
        assertEquals(3, read("/api/v1/golden-residents?size=2&sort=memberCount,desc").path("items").get(0)
                .path("memberCount").asInt());
        assertEquals(0, read("/api/v1/golden-residents?status=RETIRED").path("totalItems").asInt());
        assertEquals(5, read("/api/v1/golden-residents?status=all").path("totalItems").asInt());
        mvc.perform(get("/api/v1/golden-residents").param("status", "GONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("status must be ACTIVE, RETIRED or ALL"));
    }

    @Test
    void golden360ExplainsEveryValueAndLinksMembersToRawRecords() throws Exception {
        String id = read("/api/v1/golden-residents?q=Ram Singh").path("items").get(0).path("goldenResidentId").asText();
        var detail = read("/api/v1/golden-residents/{id}", id);
        assertEquals("ACTIVE", detail.path("status").asText());
        assertTrue(detail.path("redirectTo").isNull());
        assertEquals(id, detail.path("resolvedGoldenResidentId").asText());
        int version = detail.path("currentVersion").asInt();
        assertEquals(detail.path("latestVersion").asInt(), version);
        assertEquals(version, detail.path("versions").size());
        assertEquals("Ram Singh", detail.path("payload").path("name").asText());
        assertEquals("Ram S.", readAsProgramUser(gov.uddp.core.security.Permission.RESIDENT_VIEW, "/api/v1/golden-residents/{id}", id)
                .path("payload").path("name").asText(), "a PROGRAM_USER sees the masked name");
        assertEquals(3, detail.path("stateCodes").size());

        var fields = detail.path("fields");
        assertEquals(5, fields.size());
        var memberIds = new HashSet<>(detail.path("members").findValuesAsText("canonicalResidentId"));
        assertEquals(3, memberIds.size());
        for (var field : fields) {
            assertTrue(field.path("rule").isTextual(), field.toString());
            assertTrue(field.path("survivorshipPolicyVersion").isTextual());
            assertTrue(memberIds.contains(field.path("selectedCanonicalResidentId").asText()));
            assertTrue(field.path("selectedStateCode").isTextual());
            assertTrue(field.path("selectedRawRecordId").isTextual());
            assertEquals(3, field.path("contributors").size());
            assertEquals(1, field.path("contributors").findValuesAsText("selected").stream()
                    .filter(Boolean::parseBoolean).count());
        }
        var name = fields.findParents("fieldPath").stream().filter(f -> f.path("fieldPath").asText().equals("name"))
                .findFirst().orElseThrow();
        assertEquals("Ram Singh", name.path("value").asText());
        name.path("contributors").forEach(c -> assertEquals("Ram Singh", c.path("value").asText()));

        for (var member : detail.path("members")) {
            var canonical = read("/api/v1/canonical-residents/{id}", member.path("canonicalResidentId").asText());
            assertEquals(id, canonical.path("summary").path("goldenResidentId").asText());
            assertEquals(member.path("rawRecordId"), canonical.path("raw").path("rawRecordId"));
            read("/api/v1/raw-records/{id}", member.path("rawRecordId").asText());
        }

        var history = read("/api/v1/golden-residents/{id}/history", id);
        assertEquals(version, history.path("versions").size());
        var first = history.path("versions").get(0);
        assertTrue(first.path("changes").size() >= 5, "every value is new in version 1");
        assertTrue(first.path("fields").size() == 5);
        var last = history.path("versions").get(version - 1);
        assertEquals(3, last.path("members").size());
        assertTrue(history.path("events").findValuesAsText("eventType").contains("CREATED"));
        assertTrue(history.path("events").findValuesAsText("eventType").contains("MEMBER_ADDED"));
        assertTrue(history.path("events").get(0).path("sequence").asLong() > 0);
    }

    @Test
    void unknownGoldenIdsAre404() throws Exception {
        UUID missing = UUID.randomUUID();
        mvc.perform(get("/api/v1/golden-residents/{id}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Golden Resident does not exist: " + missing));
        mvc.perform(get("/api/v1/golden-residents/{id}/history", missing)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/golden-residents/{id}", "x")).andExpect(status().isBadRequest());
    }
}
