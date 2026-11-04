package gov.uddp.core.golden;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Turns a matching run into the groups a Golden build should hold, as sets of latest canonical versions keyed by
 * source record. FULL: the run's clusters; INCREMENTAL: the current Golden groups, with members that have a newer
 * canonical version detached, joined by the run's clusters. Participants in neither are singletons. Clusters are
 * applied by source record key, so a newer version of a clustered record stays in its cluster. Stewardship link
 * constraints (M13, ADR-0010) then separate {@code CANNOT_LINK} pairs and join {@code MUST_LINK} pairs.
 */
public final class GoldenTargetGroups {
    public static final String FULL = "FULL";
    public static final String INCREMENTAL = "INCREMENTAL";

    /** A latest canonical version: the only kind of canonical that takes part in a build. */
    public record Participant(String key, CanonicalObservation observation) {
    }

    /** One row of the run's cluster table, already resolved to its source record key. */
    public record ClusterRow(String clusterId, String key) {
    }

    /** A current Golden membership. */
    public record CurrentMember(UUID goldenId, UUID canonicalId, String key) {
    }

    /** An effective stewardship constraint on a pair of source record keys; {@code order} is the decision order. */
    public record LinkConstraint(String keyA, String keyB, boolean mustLink, long order) {
    }

    /**
     * The groups, how many constraints applied to current participants, and how many {@code MUST_LINK} joins were
     * skipped because they would have put a {@code CANNOT_LINK} pair together.
     */
    public record Result(List<List<Participant>> groups, int constraintsInForce, int linksSkipped) {
    }

    private GoldenTargetGroups() {
    }

    /** Groups of participants, each sorted by canonical ID; the groups are sorted by their smallest key. */
    public static List<List<Participant>> build(String mode, List<Participant> participants, List<ClusterRow> clusters,
                                                List<CurrentMember> current) {
        return build(mode, participants, clusters, current, List.of()).groups();
    }

    /** As {@link #build(String, List, List, List)}, then applies the stewardship constraints. */
    public static Result build(String mode, List<Participant> participants, List<ClusterRow> clusters,
                               List<CurrentMember> current, List<LinkConstraint> constraints) {
        Map<String, Participant> byKey = new LinkedHashMap<>();
        for (Participant participant : participants) {
            byKey.put(participant.key(), participant);
        }
        DisjointSets sets = new DisjointSets(byKey.keySet());
        if (INCREMENTAL.equals(mode)) {
            Map<UUID, String> anchor = new HashMap<>();
            for (CurrentMember member : current) {
                Participant latest = byKey.get(member.key());
                boolean unchanged = latest != null && latest.observation().id().equals(member.canonicalId());
                if (!unchanged) {
                    continue;
                }
                String first = anchor.putIfAbsent(member.goldenId(), member.key());
                if (first != null) {
                    sets.union(first, member.key());
                }
            }
        } else if (!FULL.equals(mode)) {
            throw new IllegalArgumentException("Unknown matching mode: " + mode);
        }
        Map<String, String> clusterAnchor = new HashMap<>();
        for (ClusterRow row : clusters) {
            if (!byKey.containsKey(row.key())) {
                continue;
            }
            String first = clusterAnchor.putIfAbsent(row.clusterId(), row.key());
            if (first != null) {
                sets.union(first, row.key());
            }
        }
        List<LinkConstraint> inForce = constraints.stream()
                .filter(c -> byKey.containsKey(c.keyA()) && byKey.containsKey(c.keyB()) && !c.keyA().equals(c.keyB()))
                .sorted(Comparator.comparingLong(LinkConstraint::order))
                .toList();
        int skipped = 0;
        if (!inForce.isEmpty()) {
            var constrained = new Constrained(inForce);
            sets = constrained.separate(byKey.keySet(), sets);
            skipped = constrained.join(sets);
        }
        Map<String, List<Participant>> components = new TreeMap<>();
        for (Participant participant : byKey.values()) {
            components.computeIfAbsent(sets.find(participant.key()), x -> new ArrayList<>()).add(participant);
        }
        List<List<Participant>> groups = new ArrayList<>();
        for (List<Participant> component : components.values()) {
            component.sort(Comparator.comparing(p -> p.observation().id()));
            groups.add(component);
        }
        groups.sort(Comparator.comparing(group -> group.stream().map(Participant::key).min(String::compareTo).orElseThrow()));
        return new Result(groups, inForce.size(), skipped);
    }

    /** Applies effective stewardship constraints to automatic groups. Deterministic: keys and decisions are ordered. */
    private static final class Constrained {
        private final List<LinkConstraint> constraints;
        private final Map<String, Set<String>> cannot = new HashMap<>();
        private final Map<String, Set<String>> must = new HashMap<>();

        Constrained(List<LinkConstraint> constraints) {
            this.constraints = constraints;
            for (LinkConstraint c : constraints) {
                var target = c.mustLink() ? must : cannot;
                target.computeIfAbsent(c.keyA(), x -> new HashSet<>()).add(c.keyB());
                target.computeIfAbsent(c.keyB(), x -> new HashSet<>()).add(c.keyA());
            }
        }

        /**
         * Splits every automatic group that holds a CANNOT_LINK pair: members, in key order, go to the first sub-group
         * holding none of their CANNOT_LINK partners, preferring one that holds a MUST_LINK partner.
         */
        DisjointSets separate(Set<String> keys, DisjointSets automatic) {
            Map<String, List<String>> groups = new TreeMap<>();
            for (String key : new TreeSet<>(keys)) {
                groups.computeIfAbsent(automatic.find(key), x -> new ArrayList<>()).add(key);
            }
            var result = new DisjointSets(keys);
            for (List<String> group : groups.values()) {
                List<Set<String>> subGroups = new ArrayList<>();
                for (String key : group) {
                    Set<String> blocked = cannot.getOrDefault(key, Set.of());
                    Set<String> linked = must.getOrDefault(key, Set.of());
                    Set<String> chosen = null;
                    for (Set<String> sub : subGroups) {
                        if (sub.stream().anyMatch(blocked::contains)) {
                            continue;
                        }
                        if (sub.stream().anyMatch(linked::contains)) {
                            chosen = sub;
                            break;
                        }
                        if (chosen == null) {
                            chosen = sub;
                        }
                    }
                    if (chosen == null) {
                        chosen = new TreeSet<>();
                        subGroups.add(chosen);
                    }
                    chosen.add(key);
                }
                for (Set<String> sub : subGroups) {
                    String first = sub.iterator().next();
                    sub.forEach(key -> result.union(first, key));
                }
            }
            return result;
        }

        /** Unions MUST_LINK pairs in decision order unless that would join a CANNOT_LINK pair; returns the skipped. */
        int join(DisjointSets sets) {
            Map<String, Set<String>> members = new HashMap<>();
            for (String key : sets.keys()) {
                members.computeIfAbsent(sets.find(key), x -> new TreeSet<>()).add(key);
            }
            int skipped = 0;
            for (LinkConstraint c : constraints) {
                if (!c.mustLink()) {
                    continue;
                }
                String rootA = sets.find(c.keyA());
                String rootB = sets.find(c.keyB());
                if (rootA.equals(rootB)) {
                    continue;
                }
                Set<String> a = members.get(rootA);
                Set<String> b = members.get(rootB);
                boolean conflict = a.stream().anyMatch(key -> cannot.getOrDefault(key, Set.of()).stream().anyMatch(b::contains));
                if (conflict) {
                    skipped++;
                    continue;
                }
                sets.union(c.keyA(), c.keyB());
                Set<String> joined = new TreeSet<>(a);
                joined.addAll(b);
                members.remove(rootA);
                members.remove(rootB);
                members.put(sets.find(c.keyA()), joined);
            }
            return skipped;
        }
    }

    /** Union-find over source record keys; the representative of a set is its smallest key. */
    private static final class DisjointSets {
        private final Map<String, String> parent = new HashMap<>();

        DisjointSets(Iterable<String> keys) {
            keys.forEach(key -> parent.put(key, key));
        }

        Set<String> keys() {
            return parent.keySet();
        }

        String find(String key) {
            String root = key;
            while (!parent.get(root).equals(root)) {
                root = parent.get(root);
            }
            String node = key;
            while (!parent.get(node).equals(root)) {
                String next = parent.get(node);
                parent.put(node, root);
                node = next;
            }
            return root;
        }

        void union(String a, String b) {
            String rootA = find(a);
            String rootB = find(b);
            if (rootA.equals(rootB)) {
                return;
            }
            if (rootA.compareTo(rootB) < 0) {
                parent.put(rootB, rootA);
            } else {
                parent.put(rootA, rootB);
            }
        }
    }
}
