package gov.uddp.core.read.statepack;

import java.util.List;
import java.util.Optional;

/**
 * Read access to operational State Packs for the APIs and UI (ADR-0008). A repository pack is only a fixture until
 * its lifecycle has been persisted and activated in PostgreSQL; callers must never discover states from directories.
 */
public interface StatePackQueryService {
    /** Every known pack, ordered by pack ID. */
    List<StatePackModels.StatePackListItem> list();

    /** The pack with this pack ID or state code (case-insensitive). */
    Optional<StatePackModels.StatePackView> find(String packIdOrStateCode);

    /**
     * The state code for a pack ID or state code, so list filters accept either; empty when no pack matches.
     */
    default Optional<String> stateCode(String packIdOrStateCode) {
        return find(packIdOrStateCode).map(StatePackModels.StatePackView::stateCode);
    }
}
