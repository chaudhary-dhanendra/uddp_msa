package gov.uddp.core.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Priority of a held-record group: by its share of the batch (the release gate is HIGH) and by how long it waits. */
class HeldRecordExceptionsTest {
    private final HeldRecordExceptions source = new HeldRecordExceptions(null, null, 5);

    @Test
    void shareAndAgeSetThePriority() {
        assertEquals("HIGH", source.priority(5.0, Duration.ZERO), "at the release gate share");
        assertEquals("HIGH", source.priority(0.1, Duration.ofDays(3)), "three days old");
        assertEquals("MEDIUM", source.priority(1.0, Duration.ofHours(1)));
        assertEquals("MEDIUM", source.priority(0.1, Duration.ofHours(25)));
        assertEquals("LOW", source.priority(0.5, Duration.ofHours(2)));
    }
}
