package gov.uddp.shaping.admin;

import java.util.List;

/**
 * What the registry found for one jar in the plugins directory at startup. {@code status} is LOADED, NOT_APPROVED
 * (its SHA-256 is not listed in approved-plugins.yaml) or REJECTED (see {@code reason}); {@code provides} are the
 * {@code name@version} keys of the operations and parsers it registered.
 */
public record PluginStatus(String fileName, String sha256, String name, String version, String apiVersion,
                           String status, String reason, List<String> provides) {
    public static final String LOADED = "LOADED";
    public static final String NOT_APPROVED = "NOT_APPROVED";
    public static final String REJECTED = "REJECTED";
}
