package gov.uddp.core.golden;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides which Golden ID each target group carries (ADR-0001). Overlap counts source record keys, never canonical
 * IDs. Pairs are accepted greedily by overlap (largest first), then the oldest identity, then the group holding the
 * identity's longest-standing member; one identity per group and one group per identity. Pure and deterministic.
 */
public final class CarryForward {
    /** An active Golden identity and when each of its current source record keys joined it. */
    public record Identity(UUID id, OffsetDateTime createdAt, Map<String, OffsetDateTime> memberSince) {
    }

    /**
     * Per target group (same index as the input): the inherited Golden ID or {@code null} for a new identity, and
     * for a new identity the identity it split from ({@code null} if none). Per losing identity: the index of the
     * group that now holds most of its source records, or -1 if none does.
     */
    public record Outcome(List<UUID> inherited, List<UUID> splitFrom, Map<UUID, Integer> retiredInto) {
    }

    private record Candidate(int group, Identity identity, int overlap, OffsetDateTime seniority, String seniorKey) {
    }

    private static final Comparator<Identity> OLDEST_FIRST =
            Comparator.comparing(Identity::createdAt).thenComparing(Identity::id);

    private CarryForward() {
    }

    public static Outcome assign(List<Set<String>> groups, List<Identity> identities) {
        List<Candidate> candidates = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            for (Identity identity : identities) {
                int overlap = 0;
                OffsetDateTime seniority = null;
                String seniorKey = null;
                for (String key : groups.get(g)) {
                    OffsetDateTime since = identity.memberSince().get(key);
                    if (since == null) {
                        continue;
                    }
                    overlap++;
                    if (seniority == null || since.isBefore(seniority)
                            || (since.isEqual(seniority) && key.compareTo(seniorKey) < 0)) {
                        seniority = since;
                        seniorKey = key;
                    }
                }
                if (overlap > 0) {
                    candidates.add(new Candidate(g, identity, overlap, seniority, seniorKey));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::overlap).reversed()
                .thenComparing(Candidate::identity, OLDEST_FIRST)
                .thenComparing(Candidate::seniority)
                .thenComparing(Candidate::seniorKey)
                .thenComparingInt(Candidate::group));

        List<UUID> inherited = new ArrayList<>();
        List<UUID> splitFrom = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            inherited.add(null);
            splitFrom.add(null);
        }
        Set<UUID> taken = new HashSet<>();
        for (Candidate candidate : candidates) {
            if (inherited.get(candidate.group()) == null && !taken.contains(candidate.identity().id())) {
                inherited.set(candidate.group(), candidate.identity().id());
                taken.add(candidate.identity().id());
            }
        }
        // A new group that shares members with an identity is a split side of the identity it overlaps most.
        for (Candidate candidate : candidates) {
            int g = candidate.group();
            if (inherited.get(g) == null && splitFrom.get(g) == null) {
                splitFrom.set(g, candidate.identity().id());
            }
        }

        Map<UUID, Integer> retiredInto = new LinkedHashMap<>();
        for (Identity identity : identities.stream().sorted(OLDEST_FIRST).toList()) {
            if (taken.contains(identity.id())) {
                continue;
            }
            retiredInto.put(identity.id(), absorbingGroup(identity, groups, inherited, identities));
        }
        return new Outcome(inherited, splitFrom, retiredInto);
    }

    /** The group holding most of the identity's keys; ties go to the group whose Golden ID is oldest. */
    private static int absorbingGroup(Identity loser, List<Set<String>> groups, List<UUID> inherited,
                                      List<Identity> identities) {
        Map<UUID, Identity> byId = new LinkedHashMap<>();
        identities.forEach(identity -> byId.put(identity.id(), identity));
        int best = -1;
        int bestCount = 0;
        for (int g = 0; g < groups.size(); g++) {
            int count = (int) groups.get(g).stream().filter(loser.memberSince()::containsKey).count();
            if (count == 0) {
                continue;
            }
            if (count > bestCount || (count == bestCount && older(byId.get(inherited.get(g)), byId.get(inherited.get(best))))) {
                best = g;
                bestCount = count;
            }
        }
        return best;
    }

    /** Whether {@code a} (an inherited identity, or {@code null} for a new one) is older than {@code b}. */
    private static boolean older(Identity a, Identity b) {
        if (a == null) {
            return false;
        }
        return b == null || OLDEST_FIRST.compare(a, b) < 0;
    }
}
