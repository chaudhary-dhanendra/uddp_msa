package gov.uddp.core.api.v1;

import gov.uddp.core.read.GoldenReadService;
import gov.uddp.core.read.GoldenReadService.GoldenResident360;
import gov.uddp.core.security.AuditContext;
import gov.uddp.core.security.Audited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Purpose-based full view (P1 Part 5a): every other response masks personal data; a user with RESIDENT_VIEW_FULL may
 * reveal one Golden Resident's 360 unmasked, for this one response, after choosing a configured purpose. Each reveal
 * is audited with the purpose and the note. The state scope applies as for the masked view.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Golden Residents")
public class RevealController {
    private final GoldenReadService golden;
    private final List<String> purposes;

    public RevealController(GoldenReadService golden,
                            @Value("${uddp.privacy.reveal-purposes:Grievance redressal,Benefit eligibility verification,"
                                    + "Duplicate investigation,Data correction request,Legal or audit request}")
                            List<String> purposes) {
        this.golden = golden;
        this.purposes = purposes.stream().map(String::strip).filter(p -> !p.isEmpty()).toList();
    }

    /** Why the data is needed: one of the configured purposes, and optional free text. */
    public record RevealRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String purpose,
                                @Schema(nullable = true) String note) {
    }

    @Operation(operationId = "listRevealPurposes", summary = "The purposes a full view may be revealed for")
    @GetMapping("/privacy/reveal-purposes")
    @PreAuthorize("hasAuthority('PERM_RESIDENT_VIEW')")
    public List<String> purposes() {
        return purposes;
    }

    @Operation(operationId = "revealGoldenResident",
            summary = "One Golden Resident's 360 unmasked, for a stated purpose (audited; this response only)")
    @ApiResponse(responseCode = "200", description = "The unmasked 360")
    @ApiResponse(responseCode = "400", description = "No purpose, or not one of the configured purposes",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "No RESIDENT_VIEW_FULL",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/golden-residents/{id}/reveal")
    @PreAuthorize("hasAuthority('PERM_RESIDENT_VIEW_FULL')")
    @Audited(action = "RESIDENT_REVEAL", resource = "GOLDEN_RESIDENT", idVariable = "id")
    public GoldenResident360 reveal(@PathVariable UUID id, @RequestBody(required = false) RevealRequest request) {
        String purpose = request == null || request.purpose() == null ? "" : request.purpose().strip();
        if (purpose.isEmpty()) {
            throw new IllegalArgumentException("State the purpose of the full view");
        }
        String chosen = purposes.stream().filter(p -> p.equalsIgnoreCase(purpose)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Choose one of the configured purposes: " + purposes));
        AuditContext.detail("purpose", chosen);
        String note = request.note() == null ? null : request.note().strip();
        if (note != null && !note.isEmpty()) {
            AuditContext.detail("note", note);
        }
        return golden.detail(id);
    }
}
