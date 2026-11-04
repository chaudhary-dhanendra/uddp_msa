package gov.uddp.shaping.parsers;

import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.ParseResult;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code name-relation-age@1.0} is {@code name-relation@1.0} that also lifts an age token ("Age 67", "Age-71",
 * "(63 Yrs)", "Age:42", "58Y") out of the cell into {@code age.years}.
 */
@Component
public class NameRelationAgeParser implements FieldParser {

    private final NameRelationParsing parsing;

    public NameRelationAgeParser(NameRelationParsing parsing) {
        this.parsing = parsing;
    }

    @Override
    public String name() {
        return "name-relation-age";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public ParseResult parse(String value, Map<String, Object> config) {
        return parsing.parse(value, config, true);
    }
}
