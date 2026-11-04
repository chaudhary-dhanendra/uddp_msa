package gov.uddp.core.model;

import java.util.Map;

public record Scheme(String schemeId, String name, String stateCode, RecordMetadata metadata,
                     Map<String, Object> extensions) { }
