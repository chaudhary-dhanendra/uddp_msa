package gov.uddp.core.golden;

import java.util.List;
import java.util.Map;

/** Platform-governed, state-neutral Resident conflict policy. */
public record PlatformSurvivorshipPolicy(String entity, String version, Map<String, FieldPolicy> fields) {
    public record FieldPolicy(String strategy, List<String> sourcePriority) { }
}
