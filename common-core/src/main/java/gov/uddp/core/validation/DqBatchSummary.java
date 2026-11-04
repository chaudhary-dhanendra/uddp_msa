package gov.uddp.core.validation;

import java.util.List;
import java.util.UUID;

public record DqBatchSummary(UUID batchId, String stateCode, String sourceSystem, String statePackVersion,
                             int recordsEvaluated, int rulesEvaluated, int errorCount, int warningCount,
                             int affectedRecordCount, List<RuleBreakdown> ruleBreakdown, List<Sample> samples) {
    public record RuleBreakdown(String ruleId, String type, String severity, int count) { }
    public record Sample(UUID rawRecordId, UUID canonicalResidentId, String ruleId, String field, String severity, String message) { }
}
