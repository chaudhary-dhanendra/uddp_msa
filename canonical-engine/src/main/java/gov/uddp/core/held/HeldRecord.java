package gov.uddp.core.held;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A record held out of canonical, matching and golden until a steward decides. {@code sourceSystem} is the pack
 * source id; {@code packId} the pack it was mapped with and {@code draftId} the Studio draft of that pack, if any
 * (null for records held before V110).
 */
public record HeldRecord(UUID heldId, UUID batchId, UUID rawRecordId, String sourceSystem, String stateCode,
                         String department, String statePackVersion, List<HeldReason> reasons, String status,
                         OffsetDateTime createdAt, String assignedTo, String decidedBy, OffsetDateTime decidedAt,
                         String decisionReasonCode, String decisionNote, JsonNode originalPayload,
                         JsonNode currentPayload, int correctionVersion, String packId, UUID draftId) {
    public HeldRecord(UUID heldId, UUID batchId, UUID rawRecordId, String sourceSystem, String stateCode,
                      String department, String statePackVersion, List<HeldReason> reasons, String status,
                      OffsetDateTime createdAt, String assignedTo, String decidedBy, OffsetDateTime decidedAt,
                      String decisionReasonCode, String decisionNote, JsonNode originalPayload,
                      JsonNode currentPayload, int correctionVersion) {
        this(heldId, batchId, rawRecordId, sourceSystem, stateCode, department, statePackVersion, reasons, status,
                createdAt, assignedTo, decidedBy, decidedAt, decisionReasonCode, decisionNote, originalPayload,
                currentPayload, correctionVersion, null, null);
    }

    public boolean waiting() {
        return HeldRecordRepository.WAITING.contains(status);
    }

    /** SEND_TO_STEWARD-class holds only: no ERROR reason is among them. */
    public boolean overridable() {
        return !reasons.isEmpty() && reasons.stream().noneMatch(HeldReason::blocksOverride);
    }
}
