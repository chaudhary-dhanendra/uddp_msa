package gov.uddp.core.studio;

import java.util.function.Consumer;

/**
 * Where the Studio's onboarding runs happen: the onboarding orchestration of the process that hosts the Studio (the
 * edge application), or, where the Studio is hosted by statepack-service, nowhere (the edge starts those runs and
 * reports them back).
 */
public interface StudioOnboarding {

    /** The run's status (RUNNING, COMPLETED, FAILED...), or null when the run is unknown. */
    String runStatus(String runId);

    /** True while a Studio-started run is still running here. */
    boolean inProgress();

    /** True once any ACTIVE Golden Resident exists (the default onboarding mode is then INCREMENTAL). */
    boolean goldenDataExists();

    /** Creates the run for the pack version and source; {@code requestedMode} null or blank picks the default mode. */
    StudioModels.OnboardStarted start(String packId, String version, String sourceId, String requestedMode);

    /** Runs the created run to its end in the background; {@code finished} receives its final status. */
    void runInBackground(String runId, Consumer<String> finished);

    /** A run of a draft started or finished (status RUNNING, COMPLETED, FAILED...); kept where runs are not local. */
    default void recorded(String draftId, String runId, String status) {
    }
}
