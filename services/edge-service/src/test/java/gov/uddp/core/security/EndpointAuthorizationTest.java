package gov.uddp.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.security.UserAccountStore.NewUser;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.core.testsupport.TestSecurity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * P1 Part 2: every API endpoint enforces its permission. The expected rule of each endpoint is written down here
 * (independently of its annotation), the table must cover exactly the endpoints the application serves, and every
 * endpoint is called as each role (with the role's permissions from the database, every state) and anonymously:
 * a call the rule allows must not answer 401/403, a call it denies must answer 403 (401 when anonymous). Also: every
 * platform endpoint declares an authorization rule (default deny) and every write is audited.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EndpointAuthorizationTest {
    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory endpointConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    /** What an endpoint requires. */
    record Rule(Kind kind, Set<Permission> anyOf, Set<Permission> allOf) {
        boolean allows(Set<Permission> held, boolean anonymous) {
            return switch (kind) {
                case PUBLIC, LOGIN -> true;
                case ME, AUTHENTICATED -> !anonymous;
                // The platform is configured in this test, so setup endpoints need PLATFORM_SETTINGS.
                case SETUP -> !anonymous && held.contains(Permission.PLATFORM_SETTINGS);
                case PERMISSION -> !anonymous && allOf.stream().allMatch(held::contains)
                        && (anyOf.isEmpty() || anyOf.stream().anyMatch(held::contains));
                // A user exists in this test, so the first administrator can no longer be created.
                case BOOTSTRAP -> false;
            };
        }
    }

    /** LOGIN is public and answers 401 for wrong credentials, which is not a denial. */
    enum Kind { PUBLIC, LOGIN, ME, AUTHENTICATED, SETUP, BOOTSTRAP, PERMISSION }

    static Rule open() {
        return new Rule(Kind.PUBLIC, Set.of(), Set.of());
    }

    static Rule signedIn() {
        return new Rule(Kind.AUTHENTICATED, Set.of(), Set.of());
    }

    static Rule any(Permission... permissions) {
        return new Rule(Kind.PERMISSION, Set.of(permissions), Set.of());
    }

    static Rule requiresAll(Permission... permissions) {
        return new Rule(Kind.PERMISSION, Set.of(), Set.of(permissions));
    }

    static final Map<String, Rule> RULES = new TreeMap<>();

    static {
        RULES.put("GET /health", open());
        RULES.put("GET /api/v1/auth/csrf", open());
        RULES.put("GET /api/v1/auth/providers", open());
        RULES.put("POST /api/v1/auth/login", new Rule(Kind.LOGIN, Set.of(), Set.of()));
        RULES.put("GET /api/v1/auth/me", new Rule(Kind.ME, Set.of(), Set.of()));
        RULES.put("POST /api/v1/auth/logout", open());
        RULES.put("POST /api/v1/auth/change-password", signedIn());
        RULES.put("GET /api/setup/status", open());
        RULES.put("POST /api/setup/test-connection", new Rule(Kind.SETUP, Set.of(), Set.of()));
        RULES.put("POST /api/setup/apply", new Rule(Kind.SETUP, Set.of(), Set.of()));
        RULES.put("POST /api/setup/admin", new Rule(Kind.BOOTSTRAP, Set.of(), Set.of()));
        RULES.put("POST /api/setup/reset-demo", any(Permission.PLATFORM_SETTINGS));

        RULES.put("GET /api/v1/overview", any(Permission.REPORT_VIEW, Permission.ONBOARDING_VIEW, Permission.RESIDENT_VIEW));
        RULES.put("GET /api/v1/states", any(Permission.STATE_PACK_VIEW));
        RULES.put("GET /api/v1/states/{state}", any(Permission.STATE_PACK_VIEW));
        RULES.put("GET /api/v1/batches", any(Permission.ONBOARDING_VIEW));
        RULES.put("GET /api/v1/batches/{batchId}", any(Permission.ONBOARDING_VIEW));
        RULES.put("GET /api/v1/batches/{batchId}/rejection-report", any(Permission.BATCH_VIEW));
        RULES.put("GET /api/v1/audit-reports/final-rejected", any(Permission.AUDIT_VIEW));
        RULES.put("GET /api/v1/audit-reports/final-rejected/summary", any(Permission.AUDIT_VIEW));
        RULES.put("GET /api/v1/audit-reports/final-rejected/export",
                requiresAll(Permission.AUDIT_VIEW, Permission.REPORT_EXPORT));
        RULES.put("GET /api/v1/dq/rules", any(Permission.DATA_QUALITY_VIEW));
        RULES.put("GET /api/v1/dq/findings", any(Permission.DATA_QUALITY_VIEW));
        RULES.put("GET /api/v1/canonical-residents", any(Permission.CANONICAL_VIEW));
        RULES.put("GET /api/v1/canonical-residents/{id}", any(Permission.CANONICAL_VIEW));
        RULES.put("GET /api/v1/raw-records/{id}", any(Permission.LINEAGE_VIEW));
        RULES.put("GET /api/v1/matching-runs", any(Permission.MATCH_EVIDENCE_VIEW));
        RULES.put("GET /api/v1/matching-runs/{runId}", any(Permission.MATCH_EVIDENCE_VIEW));
        RULES.put("GET /api/v1/matching-runs/{runId}/pairs", any(Permission.MATCH_EVIDENCE_VIEW));
        RULES.put("GET /api/v1/matching-runs/{runId}/pairs/{canonicalA}/{canonicalB}", any(Permission.MATCH_EVIDENCE_VIEW));
        RULES.put("GET /api/v1/golden-residents", any(Permission.RESIDENT_VIEW));
        RULES.put("GET /api/v1/golden-residents/{id}", any(Permission.RESIDENT_VIEW));
        RULES.put("GET /api/v1/golden-residents/by-uddp-id/{uddpId}", any(Permission.RESIDENT_VIEW));
        RULES.put("GET /api/v1/golden-residents/{id}/history", any(Permission.RESIDENT_VIEW));
        RULES.put("GET /api/v1/golden-residents/{id}/families", any(Permission.FAMILY_VIEW));
        RULES.put("GET /api/v1/golden-residents/{id}/benefits", any(Permission.BENEFIT_VIEW));
        RULES.put("GET /api/v1/golden-residents/{id}/applications", any(Permission.BENEFIT_VIEW));
        RULES.put("POST /api/v1/golden-residents/{id}/reveal", any(Permission.RESIDENT_VIEW_FULL));
        RULES.put("GET /api/v1/privacy/reveal-purposes", any(Permission.RESIDENT_VIEW));
        RULES.put("GET /api/v1/retention/policy", any(Permission.PLATFORM_SETTINGS));
        RULES.put("GET /api/v1/users", any(Permission.USER_MANAGE));
        RULES.put("GET /api/v1/users/{id}", any(Permission.USER_MANAGE));
        RULES.put("POST /api/v1/users", any(Permission.USER_MANAGE));
        RULES.put("POST /api/v1/users/{id}/enable", any(Permission.USER_MANAGE));
        RULES.put("POST /api/v1/users/{id}/disable", any(Permission.USER_MANAGE));
        RULES.put("POST /api/v1/users/{id}/unlock", any(Permission.USER_MANAGE));
        RULES.put("POST /api/v1/users/{id}/reset-password", any(Permission.USER_MANAGE));
        RULES.put("PUT /api/v1/users/{id}/roles", any(Permission.USER_MANAGE));
        RULES.put("PUT /api/v1/users/{id}/department-scope", any(Permission.USER_MANAGE));
        RULES.put("GET /api/v1/roles", any(Permission.USER_MANAGE));
        RULES.put("POST /api/v1/retention/purge", any(Permission.PLATFORM_SETTINGS));
        RULES.put("GET /api/v1/families", any(Permission.FAMILY_VIEW));
        RULES.put("GET /api/v1/families/{id}", any(Permission.FAMILY_VIEW));
        RULES.put("GET /api/v1/families/by-uddp-id/{uddpId}", any(Permission.FAMILY_VIEW));
        RULES.put("GET /api/v1/families/{id}/history", any(Permission.FAMILY_VIEW));
        RULES.put("GET /api/v1/onboarding-runs", any(Permission.ONBOARDING_VIEW));
        RULES.put("GET /api/v1/onboarding-runs/{runId}", any(Permission.ONBOARDING_VIEW));
        RULES.put("GET /api/v1/schemes", any(Permission.BENEFIT_VIEW, Permission.REPORT_VIEW));
        RULES.put("GET /api/v1/benefits", any(Permission.BENEFIT_VIEW));
        RULES.put("GET /api/v1/benefits/{id}", any(Permission.BENEFIT_VIEW));
        RULES.put("GET /api/v1/applications/{id}", any(Permission.BENEFIT_VIEW));
        RULES.put("GET /api/v1/golden-residents/{id}/assets", any(Permission.BENEFIT_VIEW));
        RULES.put("GET /api/v1/reports/duplicate-benefits", any(Permission.REPORT_VIEW));
        RULES.put("GET /api/v1/reports/eligible-not-enrolled", any(Permission.REPORT_VIEW));
        RULES.put("GET /api/v1/audit-events", any(Permission.AUDIT_VIEW));
        RULES.put("GET /api/v1/audit-events/actions", any(Permission.AUDIT_VIEW));
        RULES.put("GET /api/v1/audit-events/export", requiresAll(Permission.AUDIT_VIEW, Permission.REPORT_EXPORT));
        RULES.put("GET /api/v1/stewardship/reviews", any(Permission.STEWARDSHIP_VIEW));
        RULES.put("GET /api/v1/stewardship/reviews/{id}", any(Permission.STEWARDSHIP_VIEW));
        RULES.put("GET /api/v1/stewardship/decisions", any(Permission.STEWARDSHIP_VIEW));
        RULES.put("POST /api/stewardship/reviews/{id}/decision", any(Permission.STEWARDSHIP_DECIDE));
        RULES.put("POST /api/stewardship/merge", any(Permission.STEWARDSHIP_DECIDE));
        RULES.put("POST /api/stewardship/split", any(Permission.STEWARDSHIP_DECIDE));
        RULES.put("POST /api/stewardship/proposals", any(Permission.STEWARDSHIP_DECIDE));
        RULES.put("GET /api/stewardship/proposals", any(Permission.STEWARDSHIP_VIEW));
        RULES.put("GET /api/v1/held-records", any(Permission.STEWARD_HELD_VIEW));
        RULES.put("GET /api/v1/held-records/groups", any(Permission.STEWARD_HELD_VIEW));
        RULES.put("GET /api/v1/held-records/bulk-actions", any(Permission.STEWARD_HELD_VIEW));
        RULES.put("GET /api/v1/held-records/{id}", any(Permission.STEWARD_HELD_VIEW));
        RULES.put("POST /api/v1/held-records/{id}/rectify", any(Permission.STEWARD_HELD_DECIDE));
        RULES.put("POST /api/v1/held-records/{id}/release-override", any(Permission.STEWARD_HELD_DECIDE));
        RULES.put("POST /api/v1/held-records/{id}/reject", any(Permission.STEWARD_HELD_DECIDE));
        RULES.put("POST /api/v1/held-records/{id}/reopen",
                requiresAll(Permission.STEWARD_HELD_DECIDE, Permission.PLATFORM_SETTINGS));
        RULES.put("POST /api/v1/held-records/groups/{batch}/{reason}/reject", any(Permission.STEWARD_HELD_DECIDE));
        RULES.put("POST /api/v1/held-records/groups/{batch}/{reason}/reprocess", any(Permission.STEWARD_HELD_DECIDE));
        RULES.put("POST /api/v1/held-records/bulk-actions/{actionId}/approve", any(Permission.STEWARD_HELD_DECIDE));
        RULES.put("POST /api/v1/held-records/bulk-actions/{actionId}/decline", any(Permission.STEWARD_HELD_DECIDE));
        // Track A: Rules Studio and Scheme Pack APIs
        // Approvers read and test a version before they decide on it.
        RULES.put("GET /api/rule-sets", any(Permission.PLATFORM_SETTINGS, Permission.RULES_APPROVE));
        RULES.put("GET /api/rule-sets/history", any(Permission.PLATFORM_SETTINGS, Permission.RULES_APPROVE));
        RULES.put("POST /api/rule-sets/evaluate", any(Permission.PLATFORM_SETTINGS));
        RULES.put("GET /api/rule-sets/fields", any(Permission.PLATFORM_SETTINGS, Permission.RULES_APPROVE));
        RULES.put("POST /api/rule-sets/table", any(Permission.PLATFORM_SETTINGS));
        RULES.put("POST /api/rule-sets/{id}/evaluate", any(Permission.PLATFORM_SETTINGS, Permission.RULES_APPROVE));
        RULES.put("GET /api/rule-sets/{id}/test-cases", any(Permission.PLATFORM_SETTINGS, Permission.RULES_APPROVE));
        RULES.put("GET /api/scheme-packs", any(Permission.PLATFORM_SETTINGS, Permission.RULES_APPROVE));
        RULES.put("GET /api/scheme-packs/{schemeId}/versions", any(Permission.PLATFORM_SETTINGS, Permission.RULES_APPROVE));
        RULES.put("GET /api/scheme-packs/{schemeId}/effective", any(Permission.PLATFORM_SETTINGS));
        RULES.put("POST /api/scheme-packs", any(Permission.PLATFORM_SETTINGS));
        RULES.put("PUT /api/scheme-packs/{schemeId}/versions/{versionId}", any(Permission.PLATFORM_SETTINGS));
        RULES.put("POST /api/scheme-packs/{schemeId}/versions/{versionId}/copy", any(Permission.PLATFORM_SETTINGS));
        RULES.put("GET /api/scheme-packs/ai-draft/status", any(Permission.PLATFORM_SETTINGS));
        RULES.put("GET /api/scheme-packs/{schemeId}/versions/{versionId}/data-availability",
                any(Permission.PLATFORM_SETTINGS, Permission.RULES_APPROVE));
        RULES.put("GET /api/scheme-packs/{schemeId}/versions/{versionId}/impact", any(Permission.PLATFORM_SETTINGS));
        RULES.put("GET /api/scheme-packs/{schemeId}/eligibility-results", any(Permission.PLATFORM_SETTINGS));
        RULES.put("POST /api/scheme-packs/{schemeId}/versions/{versionId}/eligibility/ai-draft", any(Permission.PLATFORM_SETTINGS));
        RULES.put("GET /api/scheme-packs/{schemeId}/versions/{versionId}/eligibility/{entityId}",
                any(Permission.PLATFORM_SETTINGS));
        RULES.put("GET /api/scheme-packs/{schemeId}/versions/{versionId}/impact-preview",
                any(Permission.PLATFORM_SETTINGS, Permission.RULES_APPROVE));
        // Track A maker-checker: authors submit, a different RULES_APPROVE holder decides
        RULES.put("POST /api/rule-sets", any(Permission.PLATFORM_SETTINGS));
        RULES.put("POST /api/rule-sets/{id}/submit", any(Permission.PLATFORM_SETTINGS));
        RULES.put("POST /api/rule-sets/{id}/approve", any(Permission.RULES_APPROVE));
        RULES.put("POST /api/rule-sets/{id}/reject", any(Permission.RULES_APPROVE));
        RULES.put("GET /api/identity/identifier-types", any(Permission.IDENTITY_VIEW));
        RULES.put("GET /api/identity/identifier-type-changes", any(Permission.IDENTITY_VIEW));
        RULES.put("POST /api/identity/identifier-type-changes", any(Permission.IDENTITY_MANAGE));
        RULES.put("POST /api/identity/identifier-type-changes/{id}/submit", any(Permission.IDENTITY_MANAGE));
        RULES.put("POST /api/identity/identifier-type-changes/{id}/approve", any(Permission.IDENTITY_APPROVE));
        RULES.put("POST /api/identity/identifier-type-changes/{id}/reject", any(Permission.IDENTITY_APPROVE));
        RULES.put("GET /api/identity/policies", any(Permission.IDENTITY_VIEW));
        RULES.put("GET /api/identity/coverage", any(Permission.IDENTITY_VIEW));
        RULES.put("GET /api/identity/recommendation", any(Permission.IDENTITY_VIEW));
        RULES.put("POST /api/identity/preview", any(Permission.IDENTITY_VIEW));
        RULES.put("POST /api/identity/policies", any(Permission.IDENTITY_MANAGE));
        RULES.put("POST /api/identity/policies/{id}/submit", any(Permission.IDENTITY_MANAGE));
        RULES.put("POST /api/identity/policies/{id}/approve", any(Permission.IDENTITY_APPROVE));
        RULES.put("POST /api/identity/policies/{id}/reject", any(Permission.IDENTITY_APPROVE));
        RULES.put("GET /api/geography/entity-types", any(Permission.GEOGRAPHY_VIEW));
        RULES.put("GET /api/geography/coverage", any(Permission.GEOGRAPHY_VIEW));
        RULES.put("GET /api/scheme-packs/{schemeId}/versions/{versionId}/geography-impact",
                any(Permission.PLATFORM_SETTINGS));
        RULES.put("GET /api/geography/entities", any(Permission.GEOGRAPHY_VIEW));
        RULES.put("GET /api/geography/entities/{type}/{code}", any(Permission.GEOGRAPHY_VIEW));
        RULES.put("GET /api/geography/entities/{type}/{code}/ancestors", any(Permission.GEOGRAPHY_VIEW));
        RULES.put("GET /api/geography/entities/{type}/{code}/descendants", any(Permission.GEOGRAPHY_VIEW));
        RULES.put("GET /api/geography/validate", any(Permission.GEOGRAPHY_VIEW));
        RULES.put("GET /api/geography/sync/status", any(Permission.GEOGRAPHY_VIEW));
        RULES.put("GET /api/geography/sync/runs", any(Permission.GEOGRAPHY_VIEW));
        RULES.put("POST /api/geography/sync/runs", any(Permission.GEOGRAPHY_SYNC));
        RULES.put("GET /api/geography/sync/schedule", any(Permission.GEOGRAPHY_VIEW));
        RULES.put("PUT /api/geography/sync/schedule", any(Permission.GEOGRAPHY_SYNC));
        RULES.put("GET /api/geography/changes", any(Permission.GEOGRAPHY_VIEW));
        RULES.put("GET /api/geography/snapshots/template", any(Permission.GEOGRAPHY_UPLOAD));
        RULES.put("POST /api/geography/snapshots/validate", any(Permission.GEOGRAPHY_UPLOAD));
        RULES.put("POST /api/geography/snapshots", any(Permission.GEOGRAPHY_UPLOAD));
        RULES.put("GET /api/reference-data", any(Permission.STATE_PACK_VIEW));
        RULES.put("GET /api/reference-data/{referenceSet}/values", any(Permission.STATE_PACK_VIEW));
        RULES.put("POST /api/scheme-packs/{schemeId}/versions/{versionId}/submit", any(Permission.PLATFORM_SETTINGS));
        RULES.put("POST /api/scheme-packs/{schemeId}/versions/{versionId}/approve", any(Permission.RULES_APPROVE));
        RULES.put("POST /api/scheme-packs/{schemeId}/versions/{versionId}/reject", any(Permission.RULES_APPROVE));

        // Matching Studio (Track B)
        RULES.put("GET /api/v1/matching/profiles", any(Permission.MATCHING_VIEW));
        RULES.put("GET /api/v1/matching/profiles/{id}", any(Permission.MATCHING_VIEW));
        RULES.put("GET /api/v1/matching/active-model", any(Permission.MATCHING_VIEW));
        RULES.put("POST /api/v1/matching/profiles", any(Permission.MATCHING_MANAGE));
        RULES.put("PUT /api/v1/matching/profiles/{id}", any(Permission.MATCHING_MANAGE));
        RULES.put("POST /api/v1/matching/profiles/{id}/train", any(Permission.MATCHING_MANAGE));
        RULES.put("GET /api/v1/matching/profiles/{id}/training-jobs", any(Permission.MATCHING_VIEW));
        RULES.put("POST /api/v1/matching/profiles/{id}/blocking-estimate", any(Permission.MATCHING_VIEW));
        RULES.put("POST /api/v1/matching/profiles/{id}/submit", any(Permission.MATCHING_MANAGE));
        RULES.put("POST /api/v1/matching/profiles/{id}/approve", any(Permission.MATCHING_APPROVE));
        RULES.put("POST /api/v1/matching/profiles/{id}/reject", any(Permission.MATCHING_APPROVE));
        RULES.put("GET /api/v1/matching/labels/sample", any(Permission.MATCHING_VIEW));
        RULES.put("POST /api/v1/matching/labels", any(Permission.MATCHING_MANAGE));
        RULES.put("GET /api/v1/matching/labels/summary", any(Permission.MATCHING_VIEW));
        RULES.put("GET /api/v1/matching/thresholds/suggestion", any(Permission.MATCHING_VIEW));
        RULES.put("POST /api/v1/matching/profiles/{id}/impact-preview", any(Permission.MATCHING_VIEW));
        RULES.put("GET /api/v1/matching/readiness", any(Permission.MATCHING_VIEW));
        RULES.put("GET /api/v1/matching/pairs/explanation", any(Permission.MATCHING_VIEW, Permission.MATCH_EVIDENCE_VIEW));
        RULES.put("GET /api/v1/matching/monitoring/metrics", any(Permission.MATCHING_VIEW));
        RULES.put("GET /api/v1/matching/monitoring/alerts", any(Permission.MATCHING_VIEW));
        RULES.put("POST /api/v1/matching/monitoring/alerts/{alertId}/acknowledge", any(Permission.MATCHING_MANAGE));
        RULES.put("GET /api/v1/matching/monitoring/bounds", any(Permission.MATCHING_VIEW));
        RULES.put("PUT /api/v1/matching/monitoring/bounds/{metric}", any(Permission.MATCHING_MANAGE));
        RULES.put("GET /api/v1/matching/monitoring/proposals", any(Permission.MATCHING_VIEW));
        RULES.put("POST /api/v1/matching/monitoring/proposals/refresh", any(Permission.MATCHING_MANAGE));
        RULES.put("POST /api/v1/matching/monitoring/proposals/{proposalId}/approve", any(Permission.MATCHING_APPROVE));
        RULES.put("POST /api/v1/matching/monitoring/proposals/{proposalId}/reject", any(Permission.MATCHING_APPROVE));
        RULES.put("POST /api/stewardship/proposals/{proposalId}/approve", any(Permission.STEWARDSHIP_APPROVE));
        RULES.put("POST /api/stewardship/proposals/{proposalId}/reject", any(Permission.STEWARDSHIP_APPROVE));

        RULES.put("GET /api/studio/status", any(Permission.STATE_PACK_VIEW));
        RULES.put("GET /api/studio/mapping-targets", any(Permission.STATE_PACK_VIEW));
        RULES.put("GET /api/studio/states", any(Permission.STATE_PACK_VIEW));
        RULES.put("POST /api/studio/drafts", any(Permission.STATE_PACK_CREATE));
        RULES.put("GET /api/studio/drafts/{draftId}", any(Permission.STATE_PACK_VIEW));
        RULES.put("PUT /api/studio/drafts/{draftId}/details", any(Permission.STATE_PACK_EDIT));
        RULES.put("POST /api/studio/drafts/{draftId}/upload", any(Permission.SOURCE_UPLOAD));
        RULES.put("POST /api/studio/drafts/{draftId}/delivery", any(Permission.SOURCE_UPLOAD));
        RULES.put("GET /api/studio/connections", any(Permission.SOURCE_UPLOAD));
        RULES.put("PUT /api/studio/connections/{name}", any(Permission.SOURCE_UPLOAD));
        RULES.put("POST /api/studio/drafts/{draftId}/database-source", any(Permission.SOURCE_UPLOAD));
        RULES.put("GET /api/studio/drafts/{draftId}/profile", any(Permission.SOURCE_PROFILE));
        RULES.put("GET /api/studio/drafts/{draftId}/suggestions", any(Permission.SOURCE_PROFILE));
        RULES.put("PUT /api/studio/drafts/{draftId}/configuration", any(Permission.STATE_PACK_EDIT));
        RULES.put("GET /api/studio/drafts/{draftId}/shaping", any(Permission.SOURCE_PROFILE));
        RULES.put("POST /api/studio/drafts/{draftId}/shaping/preview", any(Permission.SOURCE_PROFILE));
        RULES.put("PUT /api/studio/drafts/{draftId}/shaping", any(Permission.STATE_PACK_EDIT));
        RULES.put("GET /api/shaping/word-lists", any(Permission.SHAPING_VIEW));
        RULES.put("GET /api/shaping/word-lists/{id}", any(Permission.SHAPING_VIEW));
        RULES.put("POST /api/shaping/word-lists/{id}/proposals", any(Permission.SHAPING_MANAGE));
        RULES.put("POST /api/shaping/word-lists/test", any(Permission.SHAPING_VIEW));
        RULES.put("GET /api/shaping/operations", any(Permission.SHAPING_VIEW));
        RULES.put("GET /api/shaping/plugins", any(Permission.SHAPING_VIEW));
        RULES.put("POST /api/shaping/plugins/{sha256}/proposals", any(Permission.SHAPING_MANAGE));
        RULES.put("GET /api/shaping/changes", any(Permission.SHAPING_VIEW));
        RULES.put("POST /api/shaping/changes/{id}/approve", any(Permission.SHAPING_APPROVE));
        RULES.put("POST /api/shaping/changes/{id}/reject", any(Permission.SHAPING_APPROVE));
        RULES.put("GET /api/studio/drafts/{draftId}/preview", any(Permission.SOURCE_PROFILE));
        RULES.put("POST /api/studio/drafts/{draftId}/dry-run", any(Permission.SOURCE_PROFILE));
        RULES.put("GET /api/studio/drafts/{draftId}/dry-run", any(Permission.SOURCE_PROFILE));
        RULES.put("GET /api/studio/drafts/{draftId}/generated", any(Permission.STATE_PACK_VIEW));
        RULES.put("POST /api/studio/drafts/{draftId}/publish", any(Permission.STATE_PACK_EDIT));
        RULES.put("POST /api/studio/drafts/{draftId}/onboard", any(Permission.ONBOARDING_RUN));
        RULES.put("GET /api/studio/portability", any(Permission.PORTABILITY_VIEW));
        RULES.put("GET /api/studio/approvals", any(Permission.STATE_PACK_VIEW));
        RULES.put("POST /api/studio/approvals/{approvalId}/approve", any(Permission.STATE_PACK_APPROVE));
        RULES.put("POST /api/studio/approvals/{approvalId}/reject", any(Permission.STATE_PACK_APPROVE));

        RULES.put("POST /api/onboarding/state-packs/{packId}/register", any(Permission.STATE_PACK_PUBLISH));
        RULES.put("POST /api/onboarding/state-packs/{packId}/{version}/transitions/{target}",
                any(Permission.STATE_PACK_PUBLISH));
        RULES.put("POST /api/onboarding/runs", any(Permission.ONBOARDING_RUN));
        RULES.put("GET /api/onboarding/runs/{runId}", any(Permission.ONBOARDING_VIEW));
        RULES.put("GET /api/onboarding/states/{state}/runs", any(Permission.ONBOARDING_VIEW));
        RULES.put("POST /api/onboarding/runs/{runId}/next", any(Permission.ONBOARDING_RUN));
        RULES.put("GET /api/operations/sources/{state}/{source}/schedule", any(Permission.ONBOARDING_VIEW));
        RULES.put("PUT /api/operations/sources/{state}/{source}/schedule", any(Permission.ONBOARDING_RUN));
        RULES.put("GET /api/operations/sources/{state}/{source}/schedule/triggers", any(Permission.ONBOARDING_VIEW));
        RULES.put("GET /api/operations/sources/{state}/{source}/delivery-policy", any(Permission.ONBOARDING_VIEW));
        RULES.put("PUT /api/operations/sources/{state}/{source}/delivery-policy", any(Permission.ONBOARDING_RUN));
        RULES.put("GET /api/operations/sources/{state}/{source}/connector", any(Permission.ONBOARDING_VIEW));
        RULES.put("PUT /api/operations/sources/{state}/{source}/connector", any(Permission.ONBOARDING_RUN));
        RULES.put("POST /api/operations/sources/{state}/{source}/connector/test", any(Permission.ONBOARDING_RUN));
        RULES.put("GET /api/operations/sources/{state}/{source}/connector/preview", any(Permission.SOURCE_PROFILE));
        RULES.put("GET /api/operations/sources/{state}/{source}/connector/profile", any(Permission.SOURCE_PROFILE));
        RULES.put("GET /api/operations/sources/{state}/{source}/gates", any(Permission.ONBOARDING_VIEW));
        RULES.put("PUT /api/operations/sources/{state}/{source}/gates", any(Permission.ONBOARDING_RUN));
        RULES.put("POST /api/operations/sources/{state}/{source}/gates/dry-run", any(Permission.ONBOARDING_RUN));
        RULES.put("GET /api/operations/sources/{state}/{source}/runs/{runId}/release", any(Permission.ONBOARDING_VIEW));
        RULES.put("POST /api/operations/sources/{state}/{source}/runs/{runId}/release/approve", any(Permission.ONBOARDING_APPROVE));
        RULES.put("POST /api/operations/sources/{state}/{source}/runs/{runId}/release/reject", any(Permission.ONBOARDING_APPROVE));
        RULES.put("GET /api/inbox/items", any(Permission.ONBOARDING_VIEW));
        RULES.put("GET /api/inbox/counts", any(Permission.ONBOARDING_VIEW));
        RULES.put("GET /api/monitoring/overview", any(Permission.ONBOARDING_VIEW));
        RULES.put("GET /api/monitoring/sources/{state}/{source}/trend", any(Permission.ONBOARDING_VIEW));
        RULES.put("GET /api/monitoring/notifications", any(Permission.ONBOARDING_VIEW));
        RULES.put("POST /api/monitoring/notifications/{id}/read", any(Permission.ONBOARDING_VIEW));
        RULES.put("GET /api/monitoring/alert-rules", any(Permission.ONBOARDING_VIEW));
        RULES.put("PUT /api/monitoring/alert-rules", any(Permission.ONBOARDING_RUN));
        RULES.put("DELETE /api/monitoring/alert-rules/{id}", any(Permission.ONBOARDING_RUN));
        RULES.put("GET /api/monitoring/webhook", any(Permission.PLATFORM_SETTINGS));
        RULES.put("PUT /api/monitoring/webhook", any(Permission.PLATFORM_SETTINGS));
        RULES.put("POST /api/onboarding/runs/{runId}/stages/{stage}", any(Permission.ONBOARDING_RUN));
        RULES.put("POST /api/onboarding/runs/{runId}/resume", any(Permission.ONBOARDING_RUN));
        RULES.put("GET /api/onboarding/runs/{runId}/rejected-rows.csv", any(Permission.ONBOARDING_RUN));
        RULES.put("GET /api/onboarding/state-packs/{packId}/sources/{sourceSystem}/landing",
                any(Permission.ONBOARDING_VIEW));
        RULES.put("POST /api/onboarding/state-packs/{packId}/sources/{sourceSystem}/landing/start",
                any(Permission.ONBOARDING_RUN));

        RULES.put("POST /api/golden-residents/build", any(Permission.ONBOARDING_RUN));
        RULES.put("GET /api/golden-residents", any(Permission.RESIDENT_VIEW));
        RULES.put("GET /api/golden-residents/{id}", any(Permission.RESIDENT_VIEW));
        RULES.put("GET /api/golden-residents/{id}/history", any(Permission.RESIDENT_VIEW));
        RULES.put("GET /api/state-packs", any(Permission.STATE_PACK_VIEW));
        RULES.put("GET /api/state-packs/{state}", any(Permission.STATE_PACK_VIEW));
        RULES.put("GET /api/v1/departments", any(Permission.STATE_PACK_VIEW));
        RULES.put("GET /api/v1/departments/{departmentCode}", any(Permission.STATE_PACK_VIEW));
        RULES.put("GET /api/v1/source-packs", any(Permission.STATE_PACK_VIEW));
        RULES.put("GET /api/v1/source-packs/{packId}", any(Permission.STATE_PACK_VIEW));
        RULES.put("GET /api/v1/state-profile", signedIn());
        RULES.put("PUT /api/v1/state-profile", any(Permission.PLATFORM_SETTINGS));
        RULES.put("POST /api/canonical/residents/process/batches/{batchId}", any(Permission.ONBOARDING_RUN));
        RULES.put("GET /api/dq/batches/{batchId}", any(Permission.DATA_QUALITY_VIEW));
    }

    static final List<String> ROLES = List.of("PLATFORM_ADMIN", "STATE_ADMIN", "DATA_ENGINEER", "DATA_STEWARD",
            "PROGRAM_USER", "AUDITOR");

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Autowired
    UserAccountStore users;

    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void oneUserExists() {
        if (users.count() == 0) {
            users.create(new NewUser("existing.admin", "Existing", encoder.encode("Existing-Admin-1x"), false, "LOCAL",
                    null, List.of("PLATFORM_ADMIN"), List.of("ALL")));
        }
    }

    record Endpoint(String method, String pattern, HandlerMethod handler) {
        String key() {
            return method + " " + pattern;
        }
    }

    List<Endpoint> endpoints() {
        List<Endpoint> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            // Every platform controller, including the shaping administration in gov.uddp.shaping.admin.web.
            if (!handler.getBeanType().getName().startsWith("gov.uddp.")) {
                continue;
            }
            for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
                for (String pattern : entry.getKey().getPatternValues()) {
                    out.add(new Endpoint(method.name(), pattern, handler));
                }
            }
        }
        return out;
    }

    @Test
    void theRuleTableCoversExactlyTheEndpointsTheApplicationServes() {
        Set<String> served = new TreeSet<>(endpoints().stream().map(Endpoint::key).toList());
        assertEquals(new TreeSet<>(RULES.keySet()), served,
                "every endpoint needs an expected rule here (and every rule an endpoint)");
    }

    @Test
    void everyPlatformEndpointDeclaresAnAuthorizationRule() {
        List<String> missing = endpoints().stream()
                .filter(endpoint -> !DefaultDenyInterceptor.hasRule(endpoint.handler()))
                .map(Endpoint::key)
                .toList();
        assertEquals(List.of(), missing, "default deny: these endpoints have no @PreAuthorize");
    }

    @Test
    void everyWriteIsAudited() {
        List<String> missing = endpoints().stream()
                .filter(endpoint -> !endpoint.method().equals("GET"))
                .filter(endpoint -> endpoint.handler().getMethodAnnotation(Audited.class) == null)
                .map(Endpoint::key)
                .toList();
        assertEquals(List.of(), missing, "these writes have no @Audited");
    }

    static Stream<String> callers() {
        return Stream.concat(ROLES.stream(), Stream.of("ANONYMOUS"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("callers")
    void everyEndpointAllowsOrDeniesTheCallerAsItsRuleSays(String caller) throws Exception {
        boolean anonymous = caller.equals("ANONYMOUS");
        Set<Permission> held = anonymous ? Set.of() : permissionsOf(caller);
        RequestPostProcessor as = anonymous ? anonymous()
                : TestSecurity.as(TestSecurity.principal("test-" + caller.toLowerCase(), Set.of(caller), held,
                List.of("ALL")));
        List<String> problems = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            Rule rule = RULES.get(endpoint.key());
            if (rule == null) {
                continue;
            }
            int status = status(request(endpoint).with(as));
            boolean allowed = rule.allows(held, anonymous);
            boolean credentialsRefused = rule.kind() == Kind.LOGIN && status == 401;
            if (allowed && !credentialsRefused && (status == 401 || status == 403)) {
                problems.add(endpoint.key() + " should be allowed but answered " + status);
            }
            int expectedDenial = anonymous ? 401 : 403;
            if (!allowed && status != expectedDenial) {
                problems.add(endpoint.key() + " should answer " + expectedDenial + " but answered " + status);
            }
        }
        assertEquals(List.of(), problems, caller);
    }

    @Test
    void theRolesHaveTheirDocumentedPermissions() {
        assertTrue(permissionsOf("PLATFORM_ADMIN").containsAll(EnumSet.allOf(Permission.class)));
        assertFalse(permissionsOf("DATA_ENGINEER").contains(Permission.STATE_PACK_PUBLISH), "engineers submit, never approve");
        assertTrue(permissionsOf("STATE_ADMIN").contains(Permission.STATE_PACK_PUBLISH));
        assertFalse(permissionsOf("PROGRAM_USER").contains(Permission.RESIDENT_VIEW_FULL), "program users see masked PII");
        assertFalse(permissionsOf("PROGRAM_USER").contains(Permission.LINEAGE_VIEW));
        assertTrue(permissionsOf("AUDITOR").contains(Permission.AUDIT_VIEW));
        assertFalse(permissionsOf("AUDITOR").contains(Permission.STEWARDSHIP_DECIDE), "auditors only read");
        assertTrue(permissionsOf("DATA_STEWARD").contains(Permission.STEWARDSHIP_DECIDE));
        assertTrue(permissionsOf("DATA_STEWARD").contains(Permission.RULES_APPROVE));
        assertFalse(permissionsOf("DATA_ENGINEER").contains(Permission.RULES_APPROVE), "engineers author, never approve");
        assertTrue(permissionsOf("PLATFORM_ADMIN").contains(Permission.GEOGRAPHY_UPLOAD));
        for (String role : List.of("STATE_ADMIN", "DATA_ENGINEER", "DATA_STEWARD", "AUDITOR", "PROGRAM_USER")) {
            assertFalse(permissionsOf(role).contains(Permission.GEOGRAPHY_UPLOAD), role + " cannot upload the geography master");
        }
    }

    /**
     * The response status; a handler that fails on the sample input after passing security (an exception no advice
     * turns into a status) counts as 500.
     */
    private int status(MockHttpServletRequestBuilder request) throws Exception {
        try {
            return mvc.perform(request).andReturn().getResponse().getStatus();
        } catch (jakarta.servlet.ServletException e) {
            if (e.getCause() instanceof org.springframework.security.access.AccessDeniedException) {
                return 403;
            }
            return 500;
        }
    }

    private Set<Permission> permissionsOf(String role) {
        var definition = users.roles().stream().filter(r -> r.roleCode().equals(role)).findFirst().orElseThrow();
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        definition.permissions().forEach(code -> permissions.add(Permission.valueOf(code)));
        return permissions;
    }

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");

    private static MockHttpServletRequestBuilder request(Endpoint endpoint) {
        Matcher matcher = VARIABLE.matcher(endpoint.pattern());
        StringBuilder path = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(path, Matcher.quoteReplacement(sample(matcher.group(1))));
        }
        matcher.appendTail(path);
        if (endpoint.pattern().endsWith("/upload") || endpoint.pattern().endsWith("/ai-draft")
                || endpoint.pattern().endsWith("/delivery")) {
            return MockMvcRequestBuilders.multipart(path.toString())
                    .file(new MockMultipartFile("file", "x.csv", "text/csv", "a,b\n1,2\n".getBytes()));
        }
        var builder = MockMvcRequestBuilders.request(HttpMethod.valueOf(endpoint.method()), path.toString());

        // Keep authorization probes structurally valid so MVC argument validation
        // does not return 400 before Spring Security can return the expected 403.
        if (endpoint.key().equals("GET /api/rule-sets/history")) {
            builder.param("type", "ELIGIBILITY")
                    .param("ownerType", "SCHEME")
                    .param("ownerId", "test-owner");
        } else if (endpoint.key().equals("GET /api/scheme-packs/{schemeId}/effective")) {
            builder.param("on", "2026-01-01");
        } else if (endpoint.key().equals("GET /api/geography/validate")) {
            builder.param("type", "DISTRICT").param("code", "0");
        } else if (endpoint.key().equals("GET /api/scheme-packs/{schemeId}/versions/{versionId}/geography-impact")) {
            builder.param("dimension", "BLOCK");
        }

        if (!endpoint.method().equals("GET")) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return builder;
    }

    private static String sample(String variable) {
        return switch (variable) {
            case "state", "packId" -> "unknown-pack";
            case "version" -> "1.0.0";
            case "target" -> "VALIDATED";
            case "stage" -> "INGESTED";
            case "name" -> "sample-connection";
            default -> UUID.randomUUID().toString();
        };
    }
}
