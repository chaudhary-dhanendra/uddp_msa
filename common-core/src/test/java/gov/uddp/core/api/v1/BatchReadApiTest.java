package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M12 read API: ingestion batches with pagination, sorting, filters and the shared error body. */
class BatchReadApiTest extends ReadApiFixture {
    @Test
    void batchesArePagedSortedAndFilteredByState() throws Exception {
        var all = read("/api/v1/batches");
        assertEquals(3, all.path("totalItems").asInt());
        assertEquals(1, all.path("totalPages").asInt());
        assertEquals(0, all.path("page").asInt());
        assertEquals(20, all.path("size").asInt());
        assertEquals("startedAt,desc", all.path("sort").asText());
        var newest = all.path("items").get(0).path("startedAt").asText();
        var oldest = all.path("items").get(2).path("startedAt").asText();
        assertTrue(newest.compareTo(oldest) >= 0, "newest first");

        var first = read("/api/v1/batches?size=2&page=0&sort=stateCode,asc");
        var second = read("/api/v1/batches?size=2&page=1&sort=stateCode,asc");
        assertEquals(2, first.path("totalPages").asInt());
        assertEquals(2, first.path("items").size());
        assertEquals(1, second.path("items").size());
        assertTrue(first.path("items").get(1).path("stateCode").asText()
                .compareTo(second.path("items").get(0).path("stateCode").asText()) <= 0);

        String packId = firstPackId();
        var byPack = read("/api/v1/batches?state={s}", packId);
        var byCode = read("/api/v1/batches?state={s}", stateCodeOf(packId));
        assertEquals(1, byPack.path("totalItems").asInt());
        assertEquals(byPack.path("items"), byCode.path("items"));
        var batch = byPack.path("items").get(0);
        assertEquals("COMPLETED", batch.path("status").asText());
        assertEquals(batch.path("rawRecords").asInt(), batch.path("canonicalResidents").asInt());
        assertEquals(0, batch.path("dqErrors").asInt());
        assertTrue(batch.path("durationMs").isNumber());
        assertEquals(0, read("/api/v1/batches?state=nowhere").path("totalItems").asInt());
        assertEquals(0, read("/api/v1/batches?page=5").path("items").size());
    }

    @Test
    void batchDetailCarriesItsDqEvaluation() throws Exception {
        UUID batchId = UUID.fromString(read("/api/v1/batches").path("items").get(0).path("batchId").asText());
        var detail = read("/api/v1/batches/{id}", batchId);
        assertEquals(batchId.toString(), detail.path("batch").path("batchId").asText());
        assertEquals(detail.path("batch").path("rawRecords").asInt(), detail.path("dq").path("recordsEvaluated").asInt());
        assertTrue(detail.path("dq").path("rulesEvaluated").asInt() > 0);
        assertEquals(0, detail.path("processingErrors").size());
        assertTrue(detail.path("rules").isArray());
    }

    @Test
    void invalidPagingSortAndIdsAreRejectedWithTheErrorBody() throws Exception {
        mvc.perform(get("/api/v1/batches").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("size must be between 1 and 200"))
                .andExpect(jsonPath("$.path").value("/api/v1/batches"));
        mvc.perform(get("/api/v1/batches").param("size", "201")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/batches").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page must be 0 or greater"));
        mvc.perform(get("/api/v1/batches").param("sort", "raw_payload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.startsWith("Unsupported sort 'raw_payload'")));
        mvc.perform(get("/api/v1/batches").param("sort", "startedAt,sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Sort direction must be asc or desc"));
        mvc.perform(get("/api/v1/batches").param("page", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for page: x"));
        mvc.perform(get("/api/v1/batches/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for batchId: not-a-uuid"));
        UUID missing = UUID.randomUUID();
        mvc.perform(get("/api/v1/batches/{id}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Ingestion batch does not exist: " + missing));
        mvc.perform(get("/api/v1/dq/findings").param("severity", "FATAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("severity must be ERROR or WARNING"));
    }
}
