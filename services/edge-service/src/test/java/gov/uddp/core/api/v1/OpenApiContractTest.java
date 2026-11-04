package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The OpenAPI document is the contract the UI's TypeScript client is generated from. The committed copy
 * {@code web/onboarding-ui/openapi.json} must equal what the backend serves; regenerate it with
 * {@code mvn test -Dtest=OpenApiContractTest -Duddp.openapi.write=true}, then {@code npm run gen:api} in the UI.
 */
class OpenApiContractTest extends ReadApiFixture {
    static final Path COMMITTED = Path.of("../..", "web", "onboarding-ui", "openapi.json");

    @Test
    void servedDocumentDescribesTheReadApiAndMatchesTheCommittedCopy() throws Exception {
        var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        JsonNode spec = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        // The server URL depends on the test host; the UI calls relative /api paths.
        ((com.fasterxml.jackson.databind.node.ObjectNode) spec).remove("servers");
        String rendered = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(new ObjectMapper().treeToValue(spec, Object.class)).replace("\r\n", "\n") + "\n";
        if (Boolean.getBoolean("uddp.openapi.write")) {
            Files.writeString(COMMITTED, rendered, StandardCharsets.UTF_8);
        }
        var paths = spec.path("paths");
        for (String path : List.of("/api/v1/overview", "/api/v1/states", "/api/v1/states/{state}", "/api/v1/batches",
                "/api/v1/batches/{batchId}", "/api/v1/dq/rules", "/api/v1/dq/findings", "/api/v1/canonical-residents",
                "/api/v1/canonical-residents/{id}", "/api/v1/raw-records/{id}", "/api/v1/matching-runs",
                "/api/v1/matching-runs/{runId}", "/api/v1/matching-runs/{runId}/pairs",
                "/api/v1/matching-runs/{runId}/pairs/{canonicalA}/{canonicalB}", "/api/v1/golden-residents",
                "/api/v1/golden-residents/{id}", "/api/v1/golden-residents/{id}/history", "/api/v1/onboarding-runs",
                "/api/v1/onboarding-runs/{runId}", "/api/onboarding/runs", "/api/onboarding/runs/{runId}/resume",
                "/api/onboarding/runs/{runId}/next", "/api/golden-residents/build", "/api/v1/families",
                "/api/v1/families/{id}", "/api/v1/families/{id}/history", "/api/v1/golden-residents/{id}/families",
                "/api/v1/stewardship/reviews", "/api/v1/stewardship/reviews/{id}", "/api/v1/stewardship/decisions",
                "/api/stewardship/reviews/{id}/decision", "/api/stewardship/merge", "/api/stewardship/split",
                "/api/v1/audit-events", "/api/v1/audit-events/actions", "/api/v1/audit-events/export",
                "/api/v1/schemes", "/api/v1/benefits", "/api/v1/benefits/{id}", "/api/v1/golden-residents/{id}/benefits",
                "/api/v1/reports/duplicate-benefits", "/api/v1/reports/eligible-not-enrolled",
                "/api/setup/status", "/api/setup/test-connection", "/api/setup/apply", "/api/setup/reset-demo",
                "/api/studio/status", "/api/studio/mapping-targets", "/api/studio/states", "/api/studio/drafts",
                "/api/studio/drafts/{draftId}/upload", "/api/studio/drafts/{draftId}/suggestions",
                "/api/studio/drafts/{draftId}/preview", "/api/studio/drafts/{draftId}/publish",
                "/api/studio/drafts/{draftId}/onboard", "/api/studio/portability",
                "/api/studio/connections", "/api/studio/connections/{name}", "/api/studio/drafts/{draftId}/database-source",
                "/api/v1/departments", "/api/v1/departments/{departmentCode}", "/api/v1/source-packs",
                "/api/v1/source-packs/{packId}", "/api/v1/state-profile")) {
            assertTrue(paths.has(path), "OpenAPI documents " + path);
        }
        var schemas = spec.path("components").path("schemas");
        for (String schema : List.of("OverviewStats", "StatePackView", "PageBatchSummary", "CanonicalResidentDetail",
                "MatchPairDetail", "GoldenResident360", "GoldenResidentHistory", "OnboardingRunDetail", "ApiError",
                "StartRunResponse", "PageFamilySummary", "GoldenFamilyDetail", "GoldenFamilyHistory", "PageReviewTaskSummary", "ReviewTaskDetail", "DecisionResult",
                "PageAuditEventView", "AuditEventView",
                "SchemeCatalogueView", "PageBenefitSummary", "BenefitDetail", "ResidentBenefits", "DuplicateBenefitReport",
                "EligibleNotEnrolledReport")) {
            assertTrue(schemas.has(schema), "schema " + schema);
        }
        assertEquals("getGoldenResident", paths.path("/api/v1/golden-residents/{id}").path("get").path("operationId").asText());

        String committed = Files.exists(COMMITTED) ? Files.readString(COMMITTED, StandardCharsets.UTF_8) : "";
        assertEquals(committed.replace("\r\n", "\n"), rendered,
                "web/onboarding-ui/openapi.json is stale: run mvn test -Dtest=OpenApiContractTest"
                        + " -Duddp.openapi.write=true, then npm run gen:api");
    }
}
