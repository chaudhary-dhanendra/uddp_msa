package gov.uddp.shaping.api;

/** Where a tidy value came from and which operation produced it. */
public record Provenance(
        String file,
        String sheet,
        Integer row,
        String cell,
        String operation,
        String version,
        Double confidence) {
}
