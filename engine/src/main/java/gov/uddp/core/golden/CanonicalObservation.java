package gov.uddp.core.golden;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** One canonical Resident as seen by survivorship; {@code source} is the State Pack sourceId. */
public record CanonicalObservation(UUID id, String stateCode, String source, JsonNode payload) { }
