package gov.uddp.core.api;

import gov.uddp.core.scheme.GoEligibilityDraftService;
import gov.uddp.core.scheme.SchemeEligibilityService;
import gov.uddp.core.scheme.SchemeImpactService;
import gov.uddp.core.scheme.SchemePackService;
import gov.uddp.core.security.Audited;
import gov.uddp.core.security.CurrentUser;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/scheme-packs")
public class SchemePackController {
    private final SchemePackService service;
    private final SchemeEligibilityService eligibility;
    private final GoEligibilityDraftService goDrafts;
    private final SchemeImpactService impact;

    public SchemePackController(SchemePackService service, SchemeEligibilityService eligibility,
                                GoEligibilityDraftService goDrafts, SchemeImpactService impact) {
        this.service = service;
        this.eligibility = eligibility;
        this.goDrafts = goDrafts;
        this.impact = impact;
    }

    @GetMapping("/{schemeId}/versions/{versionId}/data-availability")
    @PreAuthorize("hasAnyAuthority('PERM_PLATFORM_SETTINGS', 'PERM_RULES_APPROVE')")
    public SchemeImpactService.DataAvailability dataAvailability(@PathVariable String schemeId,
                                                                 @PathVariable UUID versionId) {
        return impact.dataAvailability(service.version(schemeId, versionId));
    }

    /** Eligible, enrolled, eligible-not-enrolled, enrolled-not-eligible and overlaps, by district; no writes. */
    @GetMapping("/{schemeId}/versions/{versionId}/impact")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    public SchemeImpactService.Impact impact(@PathVariable String schemeId, @PathVariable UUID versionId) {
        return impact.impact(service.version(schemeId, versionId));
    }

    @GetMapping("/{schemeId}/versions/{versionId}/geography-impact")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    public SchemeImpactService.GeographyImpact geographyImpact(@PathVariable String schemeId, @PathVariable UUID versionId,
            @RequestParam SchemeImpactService.GeographyDimension dimension) {
        return impact.impact(service.version(schemeId, versionId), dimension);
    }

    /** The eligibility stored by the latest recompute after a Golden build (404 when none has run). */
    @GetMapping("/{schemeId}/eligibility-results")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    public org.springframework.http.ResponseEntity<SchemeImpactService.LatestResults> eligibilityResults(
            @PathVariable String schemeId, @RequestParam(defaultValue = "100") int limit) {
        return impact.latest(schemeId, limit).map(org.springframework.http.ResponseEntity::ok)
                .orElse(org.springframework.http.ResponseEntity.notFound().build());
    }

    public record AiDraftStatus(boolean enabled) {
    }

    /** Whether "Draft from GO" is available; the UI shows the button only when it is. */
    @GetMapping("/ai-draft/status")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    public AiDraftStatus aiDraftStatus() {
        return new AiDraftStatus(goDrafts.enabled());
    }

    /** Drafts the version's eligibility rule set from its GO PDF; the result is a DRAFT marked for verification. */
    @PostMapping("/{schemeId}/versions/{versionId}/eligibility/ai-draft")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "SCHEME_PACK_AI_DRAFT", resource = "SCHEME_PACK", idVariable = "schemeId")
    public GoEligibilityDraftService.AiRuleDraft draftFromGo(@PathVariable String schemeId, @PathVariable UUID versionId,
                                                              @RequestParam("file") MultipartFile file) throws IOException {
        return goDrafts.draftFromGo(schemeId, versionId, file.getOriginalFilename(), file.getBytes(),
                CurrentUser.username());
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_PLATFORM_SETTINGS', 'PERM_RULES_APPROVE')")
    public List<SchemePackService.Version> list() {
        return service.list();
    }

    @GetMapping("/{schemeId}/versions")
    @PreAuthorize("hasAnyAuthority('PERM_PLATFORM_SETTINGS', 'PERM_RULES_APPROVE')")
    public List<SchemePackService.Version> history(@PathVariable String schemeId) {
        return service.history(schemeId);
    }

    @GetMapping("/{schemeId}/effective")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    public SchemePackService.Version effective(@PathVariable String schemeId, @RequestParam LocalDate on) {
        return service.effective(schemeId, on).orElseThrow();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "SCHEME_PACK_CREATE", resource = "SCHEME_PACK")
    public SchemePackService.Version create(@RequestBody SchemePackService.Draft draft) {
        return service.createDraft(draft, CurrentUser.username());
    }

    @PutMapping("/{schemeId}/versions/{versionId}")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "SCHEME_PACK_UPDATE", resource = "SCHEME_PACK", idVariable = "schemeId")
    public SchemePackService.Version update(
            @PathVariable String schemeId,
            @PathVariable UUID versionId,
            @RequestBody SchemePackService.Draft draft) {
        if (!schemeId.equals(draft.schemeId())) {
            throw new IllegalArgumentException("Scheme key cannot change");
        }
        return service.updateDraft(versionId, draft);
    }

    @PostMapping("/{schemeId}/versions/{versionId}/copy")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "SCHEME_PACK_VERSION_CREATE", resource = "SCHEME_PACK", idVariable = "schemeId")
    public SchemePackService.Version copy(@PathVariable String schemeId, @PathVariable UUID versionId) {
        return service.newVersion(schemeId, versionId, CurrentUser.username());
    }

    @GetMapping("/{schemeId}/versions/{versionId}/eligibility/{entityId}")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    public SchemeEligibilityService.Result eligibility(
            @PathVariable String schemeId,
            @PathVariable UUID versionId,
            @PathVariable UUID entityId) {
        return eligibility.evaluate(version(schemeId, versionId), entityId);
    }

    @GetMapping("/{schemeId}/versions/{versionId}/impact-preview")
    @PreAuthorize("hasAnyAuthority('PERM_PLATFORM_SETTINGS', 'PERM_RULES_APPROVE')")
    public SchemeEligibilityService.Preview preview(
            @PathVariable String schemeId,
            @PathVariable UUID versionId,
            @RequestParam(defaultValue = "25") int sampleLimit) {
        return eligibility.preview(version(schemeId, versionId), sampleLimit);
    }

    public record DecisionRequest(String reason) {
    }

    @PostMapping("/{schemeId}/versions/{versionId}/submit")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "SCHEME_PACK_SUBMIT", resource = "SCHEME_PACK", idVariable = "schemeId")
    public SchemePackService.Version submit(@PathVariable String schemeId, @PathVariable UUID versionId) {
        return service.submit(schemeId, versionId, CurrentUser.username());
    }

    @PostMapping("/{schemeId}/versions/{versionId}/approve")
    @PreAuthorize("hasAuthority('PERM_RULES_APPROVE')")
    @Audited(action = "SCHEME_PACK_APPROVE", resource = "SCHEME_PACK", idVariable = "schemeId")
    public SchemePackService.Version approve(@PathVariable String schemeId, @PathVariable UUID versionId,
                                             @RequestBody(required = false) DecisionRequest body) {
        return service.approve(schemeId, versionId, CurrentUser.username(), body == null ? null : body.reason());
    }

    @PostMapping("/{schemeId}/versions/{versionId}/reject")
    @PreAuthorize("hasAuthority('PERM_RULES_APPROVE')")
    @Audited(action = "SCHEME_PACK_REJECT", resource = "SCHEME_PACK", idVariable = "schemeId")
    public SchemePackService.Version reject(@PathVariable String schemeId, @PathVariable UUID versionId,
                                            @RequestBody DecisionRequest body) {
        return service.reject(schemeId, versionId, CurrentUser.username(), body == null ? null : body.reason());
    }

    private SchemePackService.Version version(String schemeId, UUID id) {
        return service.history(schemeId).stream()
                .filter(version -> version.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
