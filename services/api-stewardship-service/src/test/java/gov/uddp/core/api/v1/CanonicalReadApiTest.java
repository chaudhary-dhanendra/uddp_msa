package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M12 read API: canonical explorer list and detail with lineage to raw record and batch, and raw records. */
class CanonicalReadApiTest extends ReadApiFixture {
    @Test
    void listSearchesByNameAndFiltersByStateAndBatch() throws Exception {
        var all = read("/api/v1/canonical-residents");
        assertEquals(7, all.path("totalItems").asInt());
        assertEquals("name,asc", all.path("sort").asText());
        var names = all.path("items").findValuesAsText("name");
        assertEquals(names.stream().sorted().toList(), names);

        var ram = read("/api/v1/canonical-residents?name=rAm sIn");
        assertEquals(3, ram.path("totalItems").asInt());
        var states = new HashSet<String>();
        ram.path("items").forEach(item -> {
            states.add(item.path("stateCode").asText());
            assertTrue(item.path("current").asBoolean());
            assertEquals(1, item.path("versionNumber").asInt());
            assertTrue(item.path("goldenResidentId").isTextual());
        });
        assertEquals(3, states.size());
        assertEquals(1, new HashSet<>(ram.path("items").findValuesAsText("goldenResidentId")).size(),
                "the three records share one Golden ID");
        assertEquals(0, read("/api/v1/canonical-residents?name=%25").path("totalItems").asInt(),
                "LIKE wildcards in the search are literal");

        String packId = firstPackId();
        var byState = read("/api/v1/canonical-residents?state={s}", packId);
        UUID batch = UUID.fromString(byState.path("items").get(0).path("batchId").asText());
        assertEquals(byState.path("totalItems"), read("/api/v1/canonical-residents?batchId={b}", batch).path("totalItems"));
        assertEquals(2, read("/api/v1/canonical-residents?size=2&sort=createdAt,desc").path("items").size());
        mvc.perform(get("/api/v1/canonical-residents").param("sort", "canonical_payload"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailShowsSourceAgainstCanonicalValuesAndLineage() throws Exception {
        String packId = firstPackId();
        var item = read("/api/v1/canonical-residents?state={s}&name=Ram Singh", packId).path("items").get(0);
        String id = item.path("canonicalResidentId").asText();
        var detail = read("/api/v1/canonical-residents/{id}", id);
        assertEquals(item, detail.path("summary"));
        assertEquals("Ram Singh", detail.path("payload").path("name").asText());
        assertEquals("Ram S.", readAsProgramUser(gov.uddp.core.security.Permission.CANONICAL_VIEW, "/api/v1/canonical-residents/{id}", id)
                .path("payload").path("name").asText(), "a PROGRAM_USER sees the masked name");
        assertTrue(detail.path("extensions").isObject());
        var mappings = detail.path("fieldMappings");
        assertTrue(mappings.size() >= 5);
        var name = mappings.findParents("target").stream().filter(m -> m.path("target").asText().equals("name"))
                .findFirst().orElseThrow();
        assertEquals("Ram Singh", name.path("sourceValue").asText());
        assertEquals("Ram Singh", name.path("canonicalValue").asText());
        var gender = mappings.findParents("target").stream().filter(m -> m.path("target").asText().equals("gender"))
                .findFirst().orElseThrow();
        assertEquals("MALE", gender.path("canonicalValue").asText());
        assertTrue(gender.path("crosswalk").isTextual(), "the crosswalk that produced the canonical value");
        assertTrue(detail.path("sourceFields").size() >= 5);
        assertEquals(item.path("rawRecordId"), detail.path("raw").path("rawRecordId"));
        assertEquals(item.path("batchId"), detail.path("batch").path("batchId"));
        assertEquals(1, detail.path("versions").size());
        assertTrue(detail.path("versions").get(0).path("current").asBoolean());

        var raw = read("/api/v1/raw-records/{id}", item.path("rawRecordId").asText());
        assertEquals(item.path("batchId"), raw.path("batchId"));
        assertTrue(raw.path("rawPayload").asText().contains("Ram Singh"));
        assertTrue(raw.path("parseError").isNull());
        assertEquals(detail.path("sourceFields"), raw.path("fields"));
        assertEquals(id, raw.path("canonicalResidents").get(0).path("canonicalResidentId").asText());
    }

    @Test
    void unknownAndMalformedIdsAreRejected() throws Exception {
        UUID missing = UUID.randomUUID();
        mvc.perform(get("/api/v1/canonical-residents/{id}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Canonical Resident does not exist: " + missing));
        mvc.perform(get("/api/v1/raw-records/{id}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Raw record does not exist: " + missing));
        mvc.perform(get("/api/v1/canonical-residents/{id}", "123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        mvc.perform(get("/api/v1/canonical-residents").param("batchId", "nope")).andExpect(status().isBadRequest());
    }
}
