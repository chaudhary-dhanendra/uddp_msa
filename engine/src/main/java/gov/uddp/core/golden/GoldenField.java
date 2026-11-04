package gov.uddp.core.golden;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
public record GoldenField(JsonNode value, UUID selectedCanonicalId, String rule, List<UUID> contributors) { }
