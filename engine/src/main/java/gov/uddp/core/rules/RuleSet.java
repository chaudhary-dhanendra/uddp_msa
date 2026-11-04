package gov.uddp.core.rules;

import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

/** Immutable, versioned DMN configuration. The owner identifier is deliberately opaque to keep this cross-domain. */
public record RuleSet(UUID id, Type type, OwnerType ownerType, String ownerId, int version, Status status,
                      LocalDate effectiveFrom, LocalDate effectiveTo, String dmnXml, List<String> requiredAttributes) {
    public RuleSet(UUID id, Type type, OwnerType ownerType, String ownerId, int version, Status status,
                   LocalDate effectiveFrom, LocalDate effectiveTo, String dmnXml) { this(id,type,ownerType,ownerId,version,status,effectiveFrom,effectiveTo,dmnXml,List.of()); }
    public RuleSet { requiredAttributes = List.copyOf(requiredAttributes == null ? List.of() : requiredAttributes); }
    public enum Type { DATA_QUALITY, SURVIVORSHIP, ELIGIBILITY }
    public enum OwnerType { DEPARTMENT, SCHEME, SOURCE_PACK }
    public enum Status { DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, RETIRED }
}
