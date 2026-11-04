package gov.uddp.core.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.identity.IdentifierProtection;
import gov.uddp.core.identity.IdentifierType;
import gov.uddp.core.identity.IdentifierTypeRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects a canonical record in which a sensitive identifier appears anywhere except the protected identifier
 * extensions (P1 Part 5b, Track E2): an unmapped column or a free-text field must never carry one into canonical,
 * Golden or audit data. The error names the field, never the value.
 *
 * <p>Which identifiers count comes from the identifier type registry: every type flagged {@code sensitivePattern}
 * contributes its {@code detection} pattern, and a candidate is a leak only when it also passes the type's own
 * validation (format, length, checksum), so ordinary numbers, UUIDs and longer tokens pass. Platform metadata and
 * source references (record IDs and keys the platform itself writes) are not scanned.
 */
public final class NationalIdLeakGuard {
    /** Fields of the canonical payload that hold platform identifiers, not source data. */
    private static final Set<String> PLATFORM_FIELDS = Set.of("metadata", "sourceReferences", "residentId");
    private static volatile NationalIdLeakGuard platform;

    private record Detector(IdentifierType type, Pattern pattern) {
    }

    private final IdentifierTypeRegistry registry;
    private final List<Detector> detectors;

    private NationalIdLeakGuard(IdentifierTypeRegistry registry) {
        this.registry = registry;
        this.detectors = registry.sensitiveTypes().stream()
                .map(type -> new Detector(type, Pattern.compile(type.detection())))
                .toList();
    }

    /** A guard over the registry's sensitive types as they are now. */
    public static NationalIdLeakGuard of(IdentifierTypeRegistry registry) {
        return new NationalIdLeakGuard(registry);
    }

    /** The guard over the platform seed file's types, for static callers. */
    public static NationalIdLeakGuard platform() {
        var guard = platform;
        if (guard == null) {
            Path seed = Path.of(System.getProperty("uddp.platform-directory", "../platform"), "identity",
                    "identifier-types.yaml");
            guard = new NationalIdLeakGuard(IdentifierTypeRegistry.fromSeed(seed));
            platform = guard;
        }
        return guard;
    }

    /** True when the text contains a valid value of a sensitive identifier type. */
    public static boolean looksLikeNationalId(String value) {
        return platform().contains(value);
    }

    /** The sensitive identifier type the whole value is a valid value of (Studio column profiling). */
    public static Optional<String> sensitiveTypeOf(String value) {
        return platform().wholeValueType(value);
    }

    /** The dotted paths of fields holding a sensitive identifier value. */
    public static List<String> leaks(JsonNode payload) {
        return platform().leaksIn(payload);
    }

    /** Throws when the payload leaks a sensitive identifier. */
    public static void require(JsonNode payload) {
        platform().check(payload);
    }

    public boolean contains(String value) {
        if (value == null) {
            return false;
        }
        for (Detector detector : detectors) {
            Matcher matcher = detector.pattern().matcher(value);
            while (matcher.find()) {
                if (registry.check(detector.type(), matcher.group()).valid()) {
                    return true;
                }
            }
        }
        return false;
    }

    public Optional<String> wholeValueType(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (Detector detector : detectors) {
            if (detector.pattern().matcher(value).matches() && registry.check(detector.type(), value).valid()) {
                return Optional.of(detector.type().code());
            }
        }
        return Optional.empty();
    }

    public List<String> leaksIn(JsonNode payload) {
        List<String> paths = new ArrayList<>();
        collect("", payload, paths);
        return paths;
    }

    public void check(JsonNode payload) {
        List<String> paths = leaksIn(payload);
        if (!paths.isEmpty()) {
            throw new IllegalArgumentException("A value that looks like a sensitive identifier is in " + paths
                    + "; map the column to an identifiers.<TYPE> target so only its protected form is kept");
        }
    }

    private void collect(String path, JsonNode node, List<String> paths) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                if (!skipped(path, name)) {
                    collect(path.isEmpty() ? name : path + "." + name, node.get(name), paths);
                }
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collect(path + "[" + i + "]", node.get(i), paths);
            }
        } else if (node.isTextual() || node.isNumber()) {
            if (contains(node.asText())) {
                paths.add(path);
            }
        }
    }

    /** The protected extensions, platform identifiers and platform-written extensions ({@code uddp.*}). */
    private static boolean skipped(String path, String name) {
        if (path.isEmpty()) {
            return PLATFORM_FIELDS.contains(name);
        }
        return path.equals("extensions") && (name.startsWith("uddp.") || name.equals(IdentifierProtection.EXTENSION));
    }
}
