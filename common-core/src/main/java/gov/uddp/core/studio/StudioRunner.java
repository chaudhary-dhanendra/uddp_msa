package gov.uddp.core.studio;

import gov.uddp.core.onboarding.OnboardingService;
import gov.uddp.core.setup.OnboardingActivity;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs Studio-started onboarding runs to completion in the background, one at a time, so the UI can poll the
 * run's stage timeline. The run itself is the existing synchronous {@link OnboardingService}.
 */
@Component
public class StudioRunner implements OnboardingActivity {
    private static final Logger log = LoggerFactory.getLogger(StudioRunner.class);

    private final OnboardingService onboarding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "studio-onboarding");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Future<?> current;

    public StudioRunner(OnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    @Override
    public boolean inProgress() {
        Future<?> running = current;
        return running != null && !running.isDone();
    }

    /** Runs every remaining stage; {@code finished} receives the final status (COMPLETED or FAILED). */
    public synchronized void runInBackground(UUID runId, Consumer<String> finished) {
        if (inProgress()) {
            throw new StudioConflictException("Another onboarding run is in progress; wait for it to finish");
        }
        current = executor.submit(() -> {
            try {
                onboarding.runToCompletion(runId);
            } catch (RuntimeException e) {
                // The failure is persisted on the run and its stage; the UI shows it from there.
                log.info("Studio onboarding run {} stopped: {}", runId, e.getClass().getSimpleName());
            }
            String status = String.valueOf(onboarding.detail(runId).get("status"));
            finished.accept(status);
        });
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
