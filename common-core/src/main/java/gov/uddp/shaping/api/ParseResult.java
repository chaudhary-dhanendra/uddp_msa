package gov.uddp.shaping.api;

import java.util.List;
import java.util.Map;

public record ParseResult(Map<String, Object> values, double confidence, List<String> warnings) {
}
