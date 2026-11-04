package gov.uddp.shaping.api;

import java.util.Map;

/** One tidy row: field values plus per-field provenance. */
public record TidyRecord(Map<String, Object> fields, Map<String, Provenance> provenance) {
}
