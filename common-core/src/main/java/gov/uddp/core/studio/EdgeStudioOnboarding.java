package gov.uddp.core.studio;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.onboarding.OnboardingService;
import gov.uddp.core.onboarding.OnboardingService.ProcessingMode;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** The Studio's onboarding runs in the edge application: its onboarding orchestration and background runner. */
@Component
public class EdgeStudioOnboarding implements StudioOnboarding {

    private final OnboardingService onboarding;
    private final StudioRunner runner;
    private final PostgresConnectionFactory connections;

    public EdgeStudioOnboarding(OnboardingService onboarding, StudioRunner runner,
                                PostgresConnectionFactory connections) {
        this.onboarding = onboarding;
        this.runner = runner;
        this.connections = connections;
    }

    @Override
    public String runStatus(String runId) {
        try {
            return String.valueOf(onboarding.detail(UUID.fromString(runId)).get("status"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean inProgress() {
        return runner.inProgress();
    }

    @Override
    public boolean goldenDataExists() {
        if (!connections.isConfigured()) {
            return false;
        }
        try (var c = connections.open();
                var s = c.prepareStatement("select exists(select 1 from golden_resident where status='ACTIVE')");
                var r = s.executeQuery()) {
            return r.next() && r.getBoolean(1);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public StudioModels.OnboardStarted start(String packId, String version, String sourceId, String requestedMode) {
        ProcessingMode mode = requestedMode == null || requestedMode.isBlank()
                ? (goldenDataExists() ? ProcessingMode.INCREMENTAL : ProcessingMode.FULL)
                : ProcessingMode.valueOf(requestedMode.trim().toUpperCase(Locale.ROOT));
        UUID run = onboarding.start(packId, version, sourceId, mode);
        return new StudioModels.OnboardStarted(run.toString(), mode.name());
    }

    @Override
    public void runInBackground(String runId, Consumer<String> finished) {
        runner.runInBackground(UUID.fromString(runId), finished);
    }
}
