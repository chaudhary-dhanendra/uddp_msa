package gov.uddp.core.golden;

import java.util.UUID;

/**
 * Outcome of one Golden build. {@code created} counts new Golden identities and {@code reused} carried-forward ones
 * (M6 meaning kept); {@code unchanged} of those got no new version, {@code versioned} did. {@code merged} counts
 * identities that absorbed another, {@code split} new identities split from an existing one, {@code retired} losing
 * identities.
 */
public record GoldenBuildSummary(UUID matchingRunId, int canonicalRecordsConsidered, int acceptedGroups, int singletonGroups,
                                 int created, int reused, String policyVersion, UUID goldenBuildId, String mode,
                                 int unchanged, int versioned, int merged, int split, int retired) { }
