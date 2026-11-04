package gov.uddp.shaping.parsers;

import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.ParseResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code name-normalise@1.0} normalises a plain name column (no relation marker) the way {@code name-relation@1.0}
 * normalises the name part: honorifics dropped, spelling variants folded, upper case. Output key {@code nameNormalised}.
 */
@Component
public class NameNormaliseParser implements FieldParser {

    private final NameRelationParsing parsing;

    public NameNormaliseParser(NameRelationParsing parsing) {
        this.parsing = parsing;
    }

    @Override
    public String name() {
        return "name-normalise";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public ParseResult parse(String value, Map<String, Object> config) {
        String normalised = value == null ? "" : parsing.normalise(value);
        if (normalised.isBlank()) {
            return new ParseResult(Map.of(), 0.0, List.of("name-normalise: the value holds no name"));
        }
        return new ParseResult(Map.of("nameNormalised", normalised), 1.0, List.of());
    }
}
