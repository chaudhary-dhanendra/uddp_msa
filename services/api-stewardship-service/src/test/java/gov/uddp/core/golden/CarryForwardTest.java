package gov.uddp.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-0001 rules on plain data: largest overlap wins, ties go to the oldest ID, losers retire, splits get new IDs. */
class CarryForwardTest {
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    private final UUID older = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
    private final UUID newer = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void unchangedGroupsKeepTheirIds() {
        var a = identity(older, 0, "a1", "a2");
        var b = identity(newer, 1, "b1");
        var outcome = CarryForward.assign(List.of(Set.of("a1", "a2"), Set.of("b1")), List.of(a, b));
        assertEquals(Arrays.asList(older, newer), outcome.inherited());
        assertEquals(Arrays.asList(null, null), outcome.splitFrom());
        assertEquals(Map.of(), outcome.retiredInto());
    }

    @Test
    void mergeKeepsTheIdWithTheLargestOverlap() {
        var small = identity(older, 0, "s1");
        var large = identity(newer, 1, "l1", "l2");
        var outcome = CarryForward.assign(List.of(Set.of("s1", "l1", "l2")), List.of(small, large));
        assertEquals(List.of(newer), outcome.inherited());
        assertEquals(Map.of(older, 0), outcome.retiredInto());
    }

    @Test
    void mergeTieKeepsTheOldestId() {
        // The newer identity has the smaller UUID, so only the creation-time rule can pick the older one.
        var oldest = identity(older, 0, "a1");
        var youngest = identity(newer, 1, "b1");
        var outcome = CarryForward.assign(List.of(Set.of("a1", "b1")), List.of(youngest, oldest));
        assertEquals(List.of(older), outcome.inherited());
        assertEquals(Map.of(newer, 0), outcome.retiredInto());
    }

    @Test
    void splitKeepsTheIdOnTheLargerSideAndTheOtherSideSplitsFromIt() {
        var merged = identity(older, 0, "a1", "a2", "b1");
        var outcome = CarryForward.assign(List.of(Set.of("b1"), Set.of("a1", "a2")), List.of(merged));
        assertNull(outcome.inherited().get(0));
        assertEquals(older, outcome.inherited().get(1));
        assertEquals(older, outcome.splitFrom().get(0));
        assertNull(outcome.splitFrom().get(1));
    }

    @Test
    void evenSplitKeepsTheIdOnTheSideWithTheLongestStandingMember() {
        var since = new LinkedHashMap<String, OffsetDateTime>();
        since.put("late", T0.plusDays(5));
        since.put("early", T0);
        var merged = new CarryForward.Identity(older, T0, since);
        var outcome = CarryForward.assign(List.of(Set.of("late"), Set.of("early")), List.of(merged));
        assertEquals(Arrays.asList(null, older), outcome.inherited());
        assertEquals(Arrays.asList(older, null), outcome.splitFrom());
    }

    @Test
    void anUnrelatedGroupIsCreatedWithoutSplitPointer() {
        var outcome = CarryForward.assign(List.of(Set.of("x")), List.of(identity(older, 0, "a1")));
        assertEquals(Arrays.asList((UUID) null), outcome.inherited());
        assertEquals(Arrays.asList((UUID) null), outcome.splitFrom());
        assertEquals(-1, outcome.retiredInto().get(older));
    }

    private static CarryForward.Identity identity(UUID id, int day, String... keys) {
        var since = new LinkedHashMap<String, OffsetDateTime>();
        for (String key : keys) {
            since.put(key, T0.plusDays(day));
        }
        return new CarryForward.Identity(id, T0.plusDays(day), since);
    }
}
