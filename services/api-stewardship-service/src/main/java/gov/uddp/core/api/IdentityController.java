package gov.uddp.core.api;

import gov.uddp.core.identity.IdentifierType;
import gov.uddp.core.identity.IdentifierTypeRegistry;
import gov.uddp.core.identity.IdentityPolicy;
import gov.uddp.core.identity.IdentityPolicyService;
import gov.uddp.core.identity.IdentityStudioService;
import gov.uddp.core.security.Audited;
import gov.uddp.core.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity administration (Track E): the identifier type registry and the Identity Policy. A type or policy changes
 * only when a different user with IDENTITY_APPROVE approves the proposed change.
 */
@RestController
@RequestMapping("/api/identity")
public class IdentityController {
    private final IdentifierTypeRegistry registry;
    private final IdentityPolicyService policies;
    private final IdentityStudioService studio;

    public IdentityController(IdentifierTypeRegistry registry, IdentityPolicyService policies,
                              IdentityStudioService studio) {
        this.registry = registry;
        this.policies = policies;
        this.studio = studio;
    }

    /** Studio Identity step: identifier coverage per source (% present, % invalid, % duplicated). */
    @GetMapping("/coverage")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_VIEW')")
    public IdentityStudioService.IdentityCoverage identityCoverage() {
        return studio.coverage();
    }

    /** Studio Identity step: a policy recommended from the coverage, warning when nothing matches deterministically. */
    @GetMapping("/recommendation")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_VIEW')")
    public IdentityStudioService.IdentityRecommendation identityRecommendation() {
        return studio.recommend();
    }

    /** Studio Identity step: what a policy would do to the current data (reads only). */
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_VIEW')")
    @Audited(action = "IDENTITY_POLICY_PREVIEW", resource = "IDENTITY_POLICY")
    public IdentityStudioService.IdentityPreview previewIdentityPolicy(@RequestBody IdentityPolicy policy) {
        return studio.preview(policy);
    }

    public record DecisionRequest(String reason) {
    }

    @GetMapping("/identifier-types")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_VIEW')")
    public List<IdentifierType> identifierTypes() {
        return registry.types();
    }

    @GetMapping("/identifier-type-changes")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_VIEW')")
    public List<IdentifierTypeRegistry.IdentifierTypeChange> identifierTypeChanges() {
        return registry.changes();
    }

    @PostMapping("/identifier-type-changes")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_MANAGE')")
    @Audited(action = "IDENTIFIER_TYPE_PROPOSE", resource = "IDENTIFIER_TYPE")
    public IdentifierTypeRegistry.IdentifierTypeChange proposeIdentifierType(@RequestBody IdentifierType definition) {
        return registry.propose(definition, CurrentUser.username());
    }

    @PostMapping("/identifier-type-changes/{id}/submit")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_MANAGE')")
    @Audited(action = "IDENTIFIER_TYPE_SUBMIT", resource = "IDENTIFIER_TYPE", idVariable = "id")
    public IdentifierTypeRegistry.IdentifierTypeChange submitIdentifierType(@PathVariable UUID id) {
        return registry.submit(id, CurrentUser.username());
    }

    @PostMapping("/identifier-type-changes/{id}/approve")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_APPROVE')")
    @Audited(action = "IDENTIFIER_TYPE_APPROVE", resource = "IDENTIFIER_TYPE", idVariable = "id")
    public IdentifierTypeRegistry.IdentifierTypeChange approveIdentifierType(@PathVariable UUID id,
                                                              @RequestBody(required = false) DecisionRequest body) {
        return registry.approve(id, CurrentUser.username(), body == null ? null : body.reason());
    }

    @PostMapping("/identifier-type-changes/{id}/reject")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_APPROVE')")
    @Audited(action = "IDENTIFIER_TYPE_REJECT", resource = "IDENTIFIER_TYPE", idVariable = "id")
    public IdentifierTypeRegistry.IdentifierTypeChange rejectIdentifierType(@PathVariable UUID id, @RequestBody DecisionRequest body) {
        return registry.reject(id, CurrentUser.username(), body == null ? null : body.reason());
    }

    @GetMapping("/policies")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_VIEW')")
    public List<IdentityPolicyService.IdentityPolicyVersion> identityPolicies() {
        return policies.list();
    }

    @PostMapping("/policies")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_MANAGE')")
    @Audited(action = "IDENTITY_POLICY_DRAFT", resource = "IDENTITY_POLICY")
    public IdentityPolicyService.IdentityPolicyVersion draftIdentityPolicy(@RequestBody IdentityPolicy policy) {
        return policies.draft(policy, CurrentUser.username());
    }

    @PostMapping("/policies/{id}/submit")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_MANAGE')")
    @Audited(action = "IDENTITY_POLICY_SUBMIT", resource = "IDENTITY_POLICY", idVariable = "id")
    public IdentityPolicyService.IdentityPolicyVersion submitIdentityPolicy(@PathVariable UUID id) {
        return policies.submit(id, CurrentUser.username());
    }

    @PostMapping("/policies/{id}/approve")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_APPROVE')")
    @Audited(action = "IDENTITY_POLICY_APPROVE", resource = "IDENTITY_POLICY", idVariable = "id")
    public IdentityPolicyService.IdentityPolicyVersion approveIdentityPolicy(@PathVariable UUID id,
                                                              @RequestBody(required = false) DecisionRequest body) {
        return policies.approve(id, CurrentUser.username(), body == null ? null : body.reason());
    }

    @PostMapping("/policies/{id}/reject")
    @PreAuthorize("hasAuthority('PERM_IDENTITY_APPROVE')")
    @Audited(action = "IDENTITY_POLICY_REJECT", resource = "IDENTITY_POLICY", idVariable = "id")
    public IdentityPolicyService.IdentityPolicyVersion rejectIdentityPolicy(@PathVariable UUID id, @RequestBody DecisionRequest body) {
        return policies.reject(id, CurrentUser.username(), body == null ? null : body.reason());
    }
}
