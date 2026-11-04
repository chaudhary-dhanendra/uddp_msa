package gov.uddp.registry;

import gov.uddp.core.golden.GoldenBuildSummary;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Starts a Golden build directly (the onboarding saga sends cmd.registry.golden-build-requested instead). */
@RestController
@RequestMapping("/api/registry/golden-builds")
public class RegistryController {

    private final GoldenBuilds builds;

    public RegistryController(GoldenBuilds builds) {
        this.builds = builds;
    }

    /** Body {@code {"matchingRunId": uuid}}, as the monolith's POST /api/golden-residents/build. */
    @PostMapping
    public GoldenBuildSummary build(@RequestBody Map<String, String> body) {
        return builds.build(UUID.fromString(body.get("matchingRunId")));
    }
}
