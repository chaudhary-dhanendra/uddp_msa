package gov.uddp.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.golden.GoldenTargetGroups.ClusterRow;
import gov.uddp.core.golden.GoldenTargetGroups.LinkConstraint;
import gov.uddp.core.golden.GoldenTargetGroups.Participant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Stewardship constraints on top of automatic groups (M13, ADR-0010): separate, then join, deterministically. */
class GoldenTargetGroupsConstraintTest {
    private static final List<Participant> PARTICIPANTS = List.of(p("a"), p("b"), p("c"), p("d"));

    @Test
    void aSplitDecisionSeparatesExactlyItsTwoSides() {
        // Matching clusters a, b, c; the steward detached c: CANNOT_LINK across, MUST_LINK along the kept side.
        var result = build(List.of(row("1", "a"), row("1", "b"), row("1", "c")),
                cannot("a", "c", 1), cannot("b", "c", 1), must("a", "b", 1));

        assertEquals(List.of(Set.of("a", "b"), Set.of("c"), Set.of("d")), keys(result));
        assertEquals(3, result.constraintsInForce());
        assertEquals(0, result.linksSkipped());
    }

    @Test
    void aMustLinkJoinsGroupsMatchingKeptApart() {
        var result = build(List.of(), must("a", "d", 1));
        assertEquals(List.of(Set.of("a", "d"), Set.of("b"), Set.of("c")), keys(result));
    }

    @Test
    void aMustLinkThatWouldJoinACannotLinkPairIsSkippedAndCounted() {
        var result = build(List.of(), cannot("a", "b", 1), must("b", "c", 2), must("a", "c", 3));
        assertEquals(List.of(Set.of("a"), Set.of("b", "c"), Set.of("d")), keys(result));
        assertEquals(1, result.linksSkipped());
    }

    @Test
    void aCannotLinkInsideALargerClusterKeepsTheRestTogetherByFirstFit() {
        var result = build(List.of(row("1", "a"), row("1", "b"), row("1", "c")), cannot("a", "b", 1));
        assertEquals(List.of(Set.of("a", "c"), Set.of("b"), Set.of("d")), keys(result));
    }

    @Test
    void constraintsOnKeysThatAreNotParticipantsAreIgnored() {
        var result = build(List.of(row("1", "a"), row("1", "b")), cannot("a", "zz", 1), must("c", "yy", 2));
        assertEquals(List.of(Set.of("a", "b"), Set.of("c"), Set.of("d")), keys(result));
        assertEquals(0, result.constraintsInForce());
        assertEquals(keys(result), keys(new GoldenTargetGroups.Result(GoldenTargetGroups.build(
                GoldenTargetGroups.FULL, PARTICIPANTS, List.of(row("1", "a"), row("1", "b")), List.of()), 0, 0)));
    }

    private static GoldenTargetGroups.Result build(List<ClusterRow> clusters, LinkConstraint... constraints) {
        return GoldenTargetGroups.build(GoldenTargetGroups.FULL, PARTICIPANTS, clusters, List.of(), List.of(constraints));
    }

    private static List<Set<String>> keys(GoldenTargetGroups.Result result) {
        return result.groups().stream()
                .map(group -> group.stream().map(Participant::key).collect(Collectors.toSet()))
                .toList();
    }

    private static Participant p(String key) {
        var id = UUID.nameUUIDFromBytes(key.getBytes());
        return new Participant(key, new CanonicalObservation(id, "FX", "src", new ObjectMapper().createObjectNode()));
    }

    private static ClusterRow row(String cluster, String key) {
        return new ClusterRow(cluster, key);
    }

    private static LinkConstraint must(String a, String b, long order) {
        return new LinkConstraint(a, b, true, order);
    }

    private static LinkConstraint cannot(String a, String b, long order) {
        return new LinkConstraint(a, b, false, order);
    }
}
