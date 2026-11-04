package gov.uddp.core.api;

import java.util.UUID;

/**
 * OpenAPI descriptions of bodies the pre-M12 write endpoints already return as maps. They document the existing
 * contract for the generated UI client and are never used to build a response, so the bodies are unchanged.
 */
public final class WriteApiSchemas {
    private WriteApiSchemas() {
    }

    /** Body of {@code POST /api/onboarding/runs}. */
    public record StartRunResponse(UUID runId, OnboardingController.Mode mode, String processingMode, String status) {
    }

    /** Error body of the onboarding endpoints; runId and stage are present only when a stage failed (500). */
    public record OnboardingErrorBody(int status, String error, String message, UUID runId, String stage) {
    }
}
