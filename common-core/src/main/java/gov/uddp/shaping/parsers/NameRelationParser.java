package gov.uddp.shaping.parsers;

import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.ParseResult;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code name-relation@1.0} splits "Name S/O Relation Name" into name, relation type, relation name and deceased flag.
 * Config: {@code extraMarkers} (marker to relation type) extends the platform marker list.
 */
@Component
public class NameRelationParser implements FieldParser {

    private final NameRelationParsing parsing;

    public NameRelationParser(NameRelationParsing parsing) {
        this.parsing = parsing;
    }

    @Override
    public String name() {
        return "name-relation";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public ParseResult parse(String value, Map<String, Object> config) {
        return parsing.parse(value, config, false);
    }
}
