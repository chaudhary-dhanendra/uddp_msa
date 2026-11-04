package gov.uddp.identity;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Starts a matching run directly (the onboarding saga uses the matching-requested command instead). */
@RestController
@RequestMapping("/api/identity/matching-runs")
public class MatchingController {

    private final MatchingRuns runs;

    public MatchingController(MatchingRuns runs) {
        this.runs = runs;
    }

    /** Body {@code {"mode": "FULL" | "INCREMENTAL", "correlationId": uuid?}}; returns the completed run's id. */
    @PostMapping
    public Map<String, Object> start(@RequestBody(required = false) Map<String, String> body) {
        String mode = body == null ? "FULL" : body.getOrDefault("mode", "FULL");
        String correlation = body == null ? null : body.get("correlationId");
        UUID runId = runs.run("INCREMENTAL".equals(mode), correlation == null ? null : UUID.fromString(correlation));
        return Map.of("matchingRunId", runId, "mode", mode);
    }
}
