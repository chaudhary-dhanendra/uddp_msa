package gov.uddp.core.api.v1;

import gov.uddp.core.privacy.MaskPii;
import gov.uddp.core.read.BenefitReadService.BenefitDetail;
import gov.uddp.core.read.BenefitReadService.BenefitSummary;
import gov.uddp.core.read.BenefitReadService.ResidentBenefits;
import gov.uddp.core.read.BenefitReadService;
import gov.uddp.core.read.BenefitReportService.DuplicateBenefitReport;
import gov.uddp.core.read.BenefitReportService.EligibleNotEnrolledReport;
import gov.uddp.core.read.BenefitReportService.SchemeCatalogueView;
import gov.uddp.core.read.BenefitReportService;
import gov.uddp.core.read.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Schemes and benefits (M15, docs/benefit-domain.md): the platform scheme catalogue, benefits with their Golden links,
 * Resident 360 benefits and the duplicate-benefit and eligible-but-not-enrolled reports. Read-only; errors are
 * {@link ApiError}.
 */
public final class BenefitControllers {
    private BenefitControllers() {
    }

    @RestController
    @RequestMapping("/api/v1/schemes")
    @Tag(name = "Schemes and Benefits")
    public static class SchemeController {
        private final BenefitReportService reports;

        public SchemeController(BenefitReportService reports) {
            this.reports = reports;
        }

        @Operation(operationId = "getSchemeCatalogue",
                summary = "The platform scheme catalogue: departments, schemes, groups, eligibility and benefit counts")
        @GetMapping
        @PreAuthorize("hasAnyAuthority('PERM_BENEFIT_VIEW', 'PERM_REPORT_VIEW')")
        public SchemeCatalogueView catalogue() {
            return reports.catalogue();
        }
    }

    @RestController
    @RequestMapping("/api/v1/benefits")
    @Tag(name = "Schemes and Benefits")
    @MaskPii
    public static class BenefitController {
        private final BenefitReadService benefits;

        public BenefitController(BenefitReadService benefits) {
            this.benefits = benefits;
        }

        @Operation(operationId = "listBenefits", summary = "Benefits as sources name them, with scheme and present status")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "400", description = "Invalid parameter",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping
        @PreAuthorize("hasAuthority('PERM_BENEFIT_VIEW')")
        public Page<BenefitSummary> list(@Parameter(description = ReadApiControllers.STATE)
                                         @RequestParam(required = false) String state,
                                         @Parameter(description = "Platform scheme ID")
                                         @RequestParam(required = false) String schemeId,
                                         @Parameter(description = "Department code")
                                         @RequestParam(required = false) String department,
                                         @Parameter(description = "ACTIVE, SUSPENDED, CLOSED, ENDED or ALL (default)")
                                         @RequestParam(required = false) String status,
                                         @Parameter(description = "Benefits this Golden Resident currently holds")
                                         @RequestParam(required = false) UUID goldenResidentId,
                                         @Parameter(description = ReadApiControllers.PAGE)
                                         @RequestParam(required = false) Integer page,
                                         @Parameter(description = ReadApiControllers.SIZE)
                                         @RequestParam(required = false) Integer size,
                                         @Parameter(description = ReadApiControllers.SORT
                                                 + "; sourceBenefitId, schemeId, stateCode, status, createdAt, observedAt")
                                         @RequestParam(required = false) String sort) {
            return benefits.list(state, schemeId, department, status, goldenResidentId, page, size, sort);
        }

        @Operation(operationId = "getBenefit",
                summary = "One benefit: beneficiaries and household with lineage, observations and disbursements")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown benefit",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('PERM_BENEFIT_VIEW')")
        public BenefitDetail detail(@PathVariable UUID id) {
            return benefits.detail(id);
        }
    }

    @RestController
    @RequestMapping("/api/v1/golden-residents")
    @Tag(name = "Golden Residents")
    @MaskPii
    public static class ResidentBenefitController {
        private final BenefitReadService benefits;

        public ResidentBenefitController(BenefitReadService benefits) {
            this.benefits = benefits;
        }

        @Operation(operationId = "getGoldenResidentBenefits",
                summary = "Resident 360 benefits: every benefit across departments and states, direct or by household")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown Golden ID",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{id}/benefits")
        @PreAuthorize("hasAuthority('PERM_BENEFIT_VIEW')")
        public ResidentBenefits benefits(@PathVariable UUID id) {
            return benefits.benefitsOf(id);
        }
    }

    @RestController
    @RequestMapping("/api/v1/reports")
    @Tag(name = "Schemes and Benefits")
    @MaskPii
    public static class BenefitReportController {
        private final BenefitReportService reports;

        public BenefitReportController(BenefitReportService reports) {
            this.reports = reports;
        }

        @Operation(operationId = "getDuplicateBenefits",
                summary = "Golden Residents holding more than one active benefit in one exclusivity group")
        @GetMapping("/duplicate-benefits")
        @PreAuthorize("hasAuthority('PERM_REPORT_VIEW')")
        public DuplicateBenefitReport duplicates() {
            return reports.duplicates();
        }

        @Operation(operationId = "getEligibleNotEnrolled",
                summary = "Per scheme with eligibility: eligible residents holding no active benefit in its group")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "400", description = "Unknown scheme, scheme without eligibility or invalid date",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/eligible-not-enrolled")
        @PreAuthorize("hasAuthority('PERM_REPORT_VIEW')")
        public EligibleNotEnrolledReport eligibleNotEnrolled(
                @Parameter(description = "Platform scheme ID; every scheme with eligibility when omitted")
                @RequestParam(required = false) String schemeId,
                @Parameter(description = "ISO date ages are computed on; today (UTC) when omitted")
                @RequestParam(required = false) String asOf) {
            return reports.eligibleNotEnrolled(schemeId, asOf);
        }
    }
}
