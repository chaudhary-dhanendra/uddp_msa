package gov.uddp.core.identity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Applies an {@link IdentityPolicy} ladder to canonical records (Track E3). Pure: the Golden build turns the result into
 * link constraints, and the Studio preview reports it; matching's deterministic pre-pass follows the same rules.
 */
public final class IdentityPolicyEvaluator {
    private IdentityPolicyEvaluator() {
    }

    /** One canonical record as identity sees it: protected identifier values by type, date of birth and gender. */
    public record Subject(String key, String stateCode, String source, Map<String, String> identifiers,
                          LocalDate dateOfBirth, String gender) {
        /** Reads {@code uddp.identifiers} (and the P1 {@code uddp.nationalIds} extension) from a canonical payload. */
        public static Subject of(String key, String stateCode, String source, JsonNode payload) {
            Map<String, String> ids = new TreeMap<>();
            JsonNode extensions = payload.path("extensions");
            extensions.path("uddp.nationalIds").fields().forEachRemaining(entry -> {
                if (entry.getValue().hasNonNull("keyedHash")) {
                    ids.put(entry.getKey(), entry.getValue().get("keyedHash").asText());
                }
            });
            extensions.path(IdentifierProtection.EXTENSION).fields().forEachRemaining(entry -> {
                if (entry.getValue().hasNonNull("value")) {
                    ids.put(entry.getKey(), entry.getValue().get("value").asText());
                }
            });
            LocalDate dob = null;
            String text = payload.path("dateOfBirth").asText(null);
            if (text != null && !text.isBlank()) {
                try {
                    dob = LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
                } catch (DateTimeParseException ignored) {
                    dob = null;
                }
            }
            String gender = payload.path("gender").asText(null);
            return new Subject(key, stateCode, source, Map.copyOf(ids), dob, gender);
        }
    }

    /** A pair of source record keys, ordered; {@code type} is the identifier that decided it (null if none). */
    public record Pair(String keyA, String keyB, String type) {
        public static Pair of(String a, String b, String type) {
            return a.compareTo(b) <= 0 ? new Pair(a, b, type) : new Pair(b, a, type);
        }
    }

    /** A pair matching proposed, with its probability; {@code autoMatch} when matching decided AUTO_MATCH. */
    public record Candidate(String keyA, String keyB, double probability, boolean autoMatch) {
    }

    /**
     * What the ladder decides: deterministic auto-matches, deterministic pairs sent to review by a DOB/gender conflict,
     * never-merge pairs, candidate auto-matches the stricter no-identifier or base-source rule sends to review, how
     * many records carry each policy type, and how many records only probabilistic matching can place.
     */
    public record Evaluation(List<Pair> autoMatches, List<Pair> conflicts, List<Pair> neverMerge,
                             List<Pair> probabilisticReviews, Map<String, Integer> coverage,
                             int withDeterministicIdentifier, int probabilisticOnly, int evidencePairs) {
    }

    /**
     * @param candidates matching's pairs (for the Golden build, the pairs inside each automatic group); never-merge
     *                   and the stricter rules are checked on them and on the deterministic pairs
     * @param firstLoad  true when no Golden Resident exists yet (the base-source rule applies)
     */
    public static Evaluation evaluate(IdentityPolicy policy, Collection<Subject> subjects, Collection<Candidate> candidates,
                                      boolean firstLoad) {
        Map<String, Subject> byKey = new LinkedHashMap<>();
        subjects.forEach(subject -> byKey.put(subject.key(), subject));
        Map<String, Integer> coverage = new LinkedHashMap<>();
        for (String type : policy.personTypes()) {
            coverage.put(type, (int) byKey.values().stream().filter(s -> s.identifiers().containsKey(type)).count());
        }
        if (policy.familyAnchorType() != null) {
            coverage.put(policy.familyAnchorType(), (int) byKey.values().stream()
                    .filter(s -> s.identifiers().containsKey(policy.familyAnchorType())).count());
        }

        // (1) The same deterministic value: each record pairs with the first record (in key order) holding it.
        Set<Pair> auto = new LinkedHashSet<>();
        Set<Pair> conflicts = new LinkedHashSet<>();
        for (String type : policy.deterministicTypes()) {
            Map<String, List<Subject>> byValue = new TreeMap<>();
            byKey.values().stream().sorted(java.util.Comparator.comparing(Subject::key))
                    .filter(s -> s.identifiers().containsKey(type))
                    .forEach(s -> byValue.computeIfAbsent(s.identifiers().get(type), x -> new ArrayList<>()).add(s));
            for (List<Subject> group : byValue.values()) {
                Subject first = group.getFirst();
                for (Subject other : group.subList(1, group.size())) {
                    Pair pair = Pair.of(first.key(), other.key(), type);
                    if (grossConflict(policy, first, other)) {
                        conflicts.add(pair);
                    } else {
                        auto.add(pair);
                    }
                }
            }
        }

        // (2) Different values of a unique type never merge: checked on every pair anything proposes.
        Set<Pair> never = new LinkedHashSet<>();
        List<Pair> proposed = new ArrayList<>(auto);
        candidates.forEach(c -> proposed.add(Pair.of(c.keyA(), c.keyB(), null)));
        Set<String> seen = new java.util.HashSet<>();
        for (Pair pair : proposed) {
            Subject a = byKey.get(pair.keyA());
            Subject b = byKey.get(pair.keyB());
            if (a == null || b == null || !seen.add(pair.keyA() + "|" + pair.keyB())) {
                continue;
            }
            for (String type : policy.neverMergeTypes()) {
                String va = a.identifiers().get(type);
                String vb = b.identifiers().get(type);
                if (va != null && vb != null && !va.equals(vb)) {
                    never.add(Pair.of(a.key(), b.key(), type));
                    break;
                }
            }
        }
        auto.removeIf(pair -> never.stream().anyMatch(n -> n.keyA().equals(pair.keyA()) && n.keyB().equals(pair.keyB())));

        // (3) evidence and (5) the stricter rules apply to matching's own auto-matches.
        int evidencePairs = 0;
        Set<Pair> reviews = new LinkedHashSet<>();
        Set<String> deterministicKeys = new java.util.HashSet<>();
        auto.forEach(pair -> {
            deterministicKeys.add(pair.keyA() + "|" + pair.keyB());
        });
        for (Candidate candidate : candidates) {
            Subject a = byKey.get(candidate.keyA());
            Subject b = byKey.get(candidate.keyB());
            if (a == null || b == null) {
                continue;
            }
            Pair pair = Pair.of(a.key(), b.key(), null);
            if (policy.evidenceTypes().stream().anyMatch(type -> a.identifiers().get(type) != null
                    && a.identifiers().get(type).equals(b.identifiers().get(type)))) {
                evidencePairs++;
            }
            if (!candidate.autoMatch() || deterministicKeys.contains(pair.keyA() + "|" + pair.keyB())
                    || never.stream().anyMatch(n -> n.keyA().equals(pair.keyA()) && n.keyB().equals(pair.keyB()))) {
                continue;
            }
            boolean noIdentifiers = !hasPolicyIdentifier(policy, a) && !hasPolicyIdentifier(policy, b);
            boolean belowStricter = noIdentifiers && policy.noIdentifierAutoMatch() != null
                    && candidate.probability() < policy.noIdentifierAutoMatch();
            boolean outsideBase = firstLoad && policy.baseSource() != null
                    && !policy.isBaseSource(a.stateCode(), a.source()) && !policy.isBaseSource(b.stateCode(), b.source());
            if (belowStricter || outsideBase) {
                reviews.add(pair);
            }
        }
        int withDeterministic = (int) byKey.values().stream()
                .filter(s -> policy.deterministicTypes().stream().anyMatch(s.identifiers()::containsKey)).count();
        return new Evaluation(List.copyOf(auto), List.copyOf(conflicts), List.copyOf(never), List.copyOf(reviews),
                Map.copyOf(coverage), withDeterministic, byKey.size() - withDeterministic, evidencePairs);
    }

    /** Dates of birth further apart than the tolerance, or (when required) two known genders that differ. */
    static boolean grossConflict(IdentityPolicy policy, Subject a, Subject b) {
        if (a.dateOfBirth() != null && b.dateOfBirth() != null
                && Math.abs(ChronoUnit.DAYS.between(a.dateOfBirth(), b.dateOfBirth())) > policy.dobToleranceDays()) {
            return true;
        }
        return policy.genderMustAgree() && known(a.gender()) && known(b.gender()) && !a.gender().equals(b.gender());
    }

    private static boolean known(String gender) {
        return gender != null && !gender.isBlank() && !"UNKNOWN".equals(gender);
    }

    private static boolean hasPolicyIdentifier(IdentityPolicy policy, Subject subject) {
        return policy.personTypes().stream().anyMatch(subject.identifiers()::containsKey);
    }
}
