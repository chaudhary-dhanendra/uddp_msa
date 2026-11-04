package gov.uddp.core.model;

import java.util.List;
import java.util.Map;

public record Family(String familyId, List<String> sourceFamilyIds, List<FamilyMember> members,
                     Address address, Map<String, Object> extensions, RecordMetadata metadata) { }
