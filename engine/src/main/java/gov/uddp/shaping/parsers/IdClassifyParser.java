package gov.uddp.shaping.parsers;

import gov.uddp.core.identity.IdentifierTypeRegistry;
import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.ParseResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code id-classify@1.0} for a column that holds one of several identifier kinds. Config {@code types} lists the
 * registry types to try in order; the first whose validation accepts the value wins. Outputs: {@code type} (the code, or
 * {@code INVALID} when none matches) and the value under that code, so {@code outputs} can route each type to its own
 * field. An unclassified value is not passed on, and the warning never holds it.
 */
@Component
public class IdClassifyParser implements FieldParser {
    public static final String INVALID = "INVALID";

    private final IdentifierTypeRegistry registry;

    public IdClassifyParser(IdentifierTypeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "id-classify";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public ParseResult parse(String value, Map<String, Object> config) {
        List<?> types = config.get("types") instanceof List<?> list ? list : List.of();
        String text = value == null ? "" : value.strip();
        for (Object candidate : types) {
            var type = registry.find(String.valueOf(candidate));
            if (type.isPresent() && registry.check(type.get(), text).valid()) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("type", type.get().code());
                values.put(type.get().code(), text);
                return new ParseResult(values, 1.0, List.of());
            }
        }
        return new ParseResult(Map.of("type", INVALID), 0.0,
                List.of("id-classify: the value matches none of " + types));
    }
}
