package gov.uddp.shaping.api;

import java.util.Map;

/** A named, versioned parser turning one cell value into one or more typed field values. */
public interface FieldParser {

    String name();

    String version();

    ParseResult parse(String value, Map<String, Object> config);
}
