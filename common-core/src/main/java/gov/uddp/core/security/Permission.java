package gov.uddp.core.security;

/**
 * What a user may do. Roles are named sets of permissions (V15/V16); every API entry point requires one of these
 * (ADR-0018). Stored by name, so constants may be added but never renamed.
 */
public enum Permission {
    STATE_PACK_VIEW,
    STATE_PACK_CREATE,
    STATE_PACK_EDIT,
    /** Approve (or reject) a State Pack publication submitted by someone else, and activate pack versions. */
    STATE_PACK_PUBLISH,
    /** Approve or reject a State Pack version submitted by someone else (maker-checker, never one's own). */
    STATE_PACK_APPROVE,
    SOURCE_UPLOAD,
    SOURCE_PROFILE,
    ONBOARDING_RUN,
    ONBOARDING_VIEW,
    CANONICAL_VIEW,
    MATCH_EVIDENCE_VIEW,
    RESIDENT_VIEW,
    /** See resident PII unmasked, after stating a purpose (audited). */
    RESIDENT_VIEW_FULL,
    FAMILY_VIEW,
    BENEFIT_VIEW,
    STEWARDSHIP_VIEW,
    STEWARDSHIP_DECIDE,
    /** Approve or reject a stewardship proposal made by someone else (maker-checker, never one's own). */
    STEWARDSHIP_APPROVE,
    DATA_QUALITY_VIEW,
    REPORT_VIEW,
    REPORT_EXPORT,
    LINEAGE_VIEW,
    PORTABILITY_VIEW,
    USER_MANAGE,
    ROLE_MANAGE,
    PLATFORM_SETTINGS,
    AUDIT_VIEW,
    /** See matching profiles, training, labels, thresholds and monitoring (Matching Studio). */
    MATCHING_VIEW,
    /** Edit matching profiles, start training, label pairs and propose threshold changes. */
    MATCHING_MANAGE,
    /** Approve or reject a matching profile submitted by someone else (maker-checker, never one's own). */
    MATCHING_APPROVE,
    /** Approve or reject the release of a paused onboarding run started by someone else (maker-checker). */
    ONBOARDING_APPROVE,
    RULES_APPROVE,
    /** See identifier types, identity policies and identifier coverage (Track E). */
    IDENTITY_VIEW,
    /** Propose identifier type changes and identity policies. */
    IDENTITY_MANAGE,
    /** Approve or reject an identifier type change or identity policy submitted by someone else (maker-checker). */
    IDENTITY_APPROVE,
    /** Search the LGD geography master, its sync history and change log (Track F). */
    GEOGRAPHY_VIEW,
    /** Start an LGD master sync by hand (platform administrators, Track F3). */
    GEOGRAPHY_SYNC,
    /** Check and load an uploaded LGD snapshot into the geography master (platform administrators only). */
    GEOGRAPHY_UPLOAD,
    /** See shaping word lists, built-in operations and plug-ins, and test a value on a parser. */
    SHAPING_VIEW,
    /** Propose word list changes and plug-in approvals or revocations. */
    SHAPING_MANAGE,
    /** Approve or reject a shaping change proposed by someone else (maker-checker, never one's own). */
    SHAPING_APPROVE,
    /** See held records, their reasons and history. */
    STEWARD_HELD_VIEW,
    /** Rectify, release, reject and reprocess held records. */
    STEWARD_HELD_DECIDE,
    /** Download the rejection report (the feedback file for the source department) of a batch. */
    BATCH_VIEW;

    /** The Spring Security authority that carries this permission. */
    public String authority() {
        return "PERM_" + name();
    }
}
