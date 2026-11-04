package gov.uddp.contracts;

/**
 * The exact State Pack an event was produced with. Consumers fetch the same pack by version and digest; the state
 * code on an envelope is data for routing and audit, never a branch condition.
 */
public record StatePackRef(String packId, String version, String digest) {
}
