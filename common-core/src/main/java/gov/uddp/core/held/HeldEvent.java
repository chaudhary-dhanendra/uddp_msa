package gov.uddp.core.held;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One entry of a held record's append-only history. */
public record HeldEvent(UUID eventId, String actor, String action, JsonNode before, JsonNode after, String note,
                        OffsetDateTime at) {
}
