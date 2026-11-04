package gov.uddp.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Direct access to the ingest and replay commands (the onboarding saga sends them over Kafka instead). */
@RestController
@RequestMapping("/api/ingestion")
public class IngestionController {

    private final Ingestions ingestions;

    public IngestionController(Ingestions ingestions) {
        this.ingestions = ingestions;
    }

    /** Body: a cmd.ingestion.ingest-requested payload. */
    @PostMapping("/batches")
    public Map<String, Object> ingest(@RequestBody JsonNode request) {
        return Map.of("batchId", request.path("batchId").asText(), "status", ingestions.ingest(request, null));
    }

    /** Body: a cmd.ingestion.replay-requested payload. */
    @PostMapping("/replays")
    public Map<String, Object> replay(@RequestBody JsonNode request) {
        return Map.of("replayRunId", UUID.fromString(request.path("replayRunId").asText()),
                "recordsPublished", ingestions.replay(request, null));
    }
}
