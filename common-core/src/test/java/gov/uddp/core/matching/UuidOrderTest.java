package gov.uddp.core.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidOrderTest {
    private final UUID low = UUID.fromString("10000000-0000-4000-8000-000000000000");
    private final UUID high = UUID.fromString("f0000000-0000-4000-8000-000000000000");

    @Test
    void ordersLikePostgresNotLikeUuidCompareTo() {
        assertTrue(high.compareTo(low) < 0, "UUID.compareTo sees f0... as negative");
        assertTrue(UuidOrder.compare(low, high) < 0, "PostgreSQL puts 10... first");
        assertEquals(low, UuidOrder.first(high, low));
        assertEquals(high, UuidOrder.second(high, low));
        assertEquals(low, UuidOrder.first(low, high));
    }

    @Test
    void comparesTheLowHalfWhenTheHighHalvesAreEqual() {
        UUID a = UUID.fromString("10000000-0000-4000-1000-000000000000");
        UUID b = UUID.fromString("10000000-0000-4000-9000-000000000000");
        assertTrue(UuidOrder.compare(a, b) < 0);
        assertEquals(0, UuidOrder.compare(a, a));
    }
}
