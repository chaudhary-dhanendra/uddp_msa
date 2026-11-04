package gov.uddp.core.studio;

import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.studio.StudioModels.Approval;
import gov.uddp.core.studio.StudioModels.Draft;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Where the Studio's State Pack versions are published, decided and activated: the edge's pack documents and
 * lifecycle, or statepack-service's versions and maker-checker. {@code files} are the generated pack files by path.
 */
public interface StudioPublications {

    /** The published (ACTIVE) packs, one per pack. */
    Collection<LoadedStatePack> published();

    Optional<LoadedStatePack> find(String packId);

    /** True when a publication waits for a different user's approval. */
    boolean makerChecker();

    /** True (and recorded) when this routine edit needs no checker; false when nothing decides that here. */
    boolean autoApproved(Draft draft, LoadedStatePack persisted, String actor);

    /** A version submitted for approval by {@code approval.submittedBy()}. */
    void submitted(Approval approval, Map<String, String> files);

    /** The checker rejected the submitted version. */
    void rejected(Approval pending, String checker, String reason, Map<String, String> files);

    /**
     * Persists and activates the version (the pack's previous version retired); {@code approval} is null when no
     * checker was needed. Returns where the version is kept.
     */
    String activate(Draft draft, String version, LoadedStatePack persisted, Approval approval,
                    Map<String, String> files, String actor);
}
