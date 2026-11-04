package gov.uddp.core.model;

import java.math.BigDecimal;
import java.util.Map;

public record Benefit(String benefitId, String schemeId, String residentId, String sourceReference,
                      BigDecimal amount, String status, RecordMetadata metadata, Map<String, Object> extensions) { }
