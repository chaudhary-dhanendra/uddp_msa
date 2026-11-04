package gov.uddp.core.api.v1;

import gov.uddp.core.privacy.MaskPii;
import gov.uddp.core.read.ApplicationReadService;
import gov.uddp.core.read.ApplicationReadService.ApplicationDetail;
import gov.uddp.core.read.ApplicationReadService.ResidentApplications;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Applications: one application with its stage history, and a Golden Resident's applications. Read-only. */
public final class ApplicationControllers {
    private ApplicationControllers() {
    }

    @RestController
    @RequestMapping("/api/v1/applications")
    @Tag(name = "Applications")
    @MaskPii
    public static class ApplicationController {
        private final ApplicationReadService applications;

        public ApplicationController(ApplicationReadService applications) {
            this.applications = applications;
        }

        @Operation(operationId = "getApplication",
                summary = "One application: present stage and status, stage events and observations with lineage")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown application",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('PERM_BENEFIT_VIEW')")
        public ApplicationDetail detail(@PathVariable UUID id) {
            return applications.detail(id);
        }
    }

    @RestController
    @RequestMapping("/api/v1/golden-residents")
    @Tag(name = "Golden Residents")
    @MaskPii
    public static class ResidentApplicationController {
        private final ApplicationReadService applications;

        public ResidentApplicationController(ApplicationReadService applications) {
            this.applications = applications;
        }

        @Operation(operationId = "getGoldenResidentApplications",
                summary = "A Golden Resident's applications across states, with present stage and status")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown Golden ID",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{id}/applications")
        @PreAuthorize("hasAuthority('PERM_BENEFIT_VIEW')")
        public ResidentApplications applications(@PathVariable UUID id) {
            return applications.applicationsOf(id);
        }
    }
}
