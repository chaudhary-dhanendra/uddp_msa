package gov.uddp.shaping.parsers;

import gov.uddp.core.identity.IdentifierType;
import gov.uddp.core.identity.IdentifierTypeRegistry;
import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.ParseResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code masked-id@1.0} reads an identifier that a source shows only in part ("XXXXXXXX4821", "********7955", "4821")
 * as the last four digits of the registry type that declares {@code partialOf} (config {@code type} picks one when
 * several do). A full valid value of the parent type also emits its last four, so full and partial sources compare.
 * Output keys are type codes, for example {@code AADHAAR_LAST4} and {@code AADHAAR}; a warning never holds the value.
 */
@Component
public class MaskedIdParser implements FieldParser {
    private static final Pattern MASKED = Pattern.compile("[X*]*[0-9]{4}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-./_]");

    private final IdentifierTypeRegistry registry;

    public MaskedIdParser(IdentifierTypeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "masked-id";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public ParseResult parse(String value, Map<String, Object> config) {
        IdentifierType partial = partialType(config);
        if (partial == null) {
            return new ParseResult(Map.of(), 0.0, List.of("masked-id: the registry has no partial identifier type"));
        }
        String cleaned = SEPARATORS.matcher(value == null ? "" : value.strip()).replaceAll("").toUpperCase(Locale.ROOT);
        Map<String, Object> values = new LinkedHashMap<>();
        var full = registry.find(partial.partialOf());
        if (full.isPresent()) {
            var check = registry.check(full.get(), cleaned);
            if (check.valid()) {
                values.put(full.get().code(), check.normalised());
                values.put(partial.code(), last4(check.normalised()));
                return new ParseResult(values, 1.0, List.of());
            }
        }
        if (MASKED.matcher(cleaned).matches() && registry.check(partial, last4(cleaned)).valid()) {
            values.put(partial.code(), last4(cleaned));
            return new ParseResult(values, 1.0, List.of());
        }
        return new ParseResult(Map.of(), 0.0, List.of("masked-id: the value is not a masked or full "
                + partial.partialOf() + " number"));
    }

    private IdentifierType partialType(Map<String, Object> config) {
        Object named = config.get("type");
        if (named != null) {
            return registry.find(named.toString()).filter(type -> type.partialOf() != null).orElse(null);
        }
        return registry.types().stream().filter(type -> type.partialOf() != null).findFirst().orElse(null);
    }

    private static String last4(String text) {
        return text.substring(text.length() - 4);
    }
}
