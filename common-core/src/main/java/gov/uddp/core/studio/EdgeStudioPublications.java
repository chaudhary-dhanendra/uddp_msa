package gov.uddp.core.studio;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.events.StatePackPublisher;
import gov.uddp.core.gates.AutoApprovalPolicy;
import gov.uddp.core.onboarding.StatePackLifecycleService;
import gov.uddp.core.security.SecurityProperties;
import gov.uddp.core.statepack.DatabaseStatePackRepository;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.studio.StudioModels.Approval;
import gov.uddp.core.studio.StudioModels.Draft;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The Studio's publications in the edge application: the pack document, the edge's pack lifecycle, the maker-checker
 * setting and auto-approval policy; with statepack-service (MSA, {@link StatePackPublisher}) every publication and
 * decision is recorded there as well, its system of record.
 */
@Component
public class EdgeStudioPublications implements StudioPublications {

    private final StatePackLoader packs;
    private final DatabaseStatePackRepository documents;
    private final StatePackLifecycleService lifecycle;
    private final PostgresConnectionFactory connections;
    private final SecurityProperties security;
    private AutoApprovalPolicy autoApproval;
    private StatePackPublisher statePackService;

    public EdgeStudioPublications(StatePackLoader packs, DatabaseStatePackRepository documents,
                                  StatePackLifecycleService lifecycle, PostgresConnectionFactory connections,
                                  SecurityProperties security) {
        this.packs = packs;
        this.documents = documents;
        this.lifecycle = lifecycle;
        this.connections = connections;
        this.security = security;
    }

    /** Enables auto-approval of routine pack edits (Track C3); without it every publication needs a checker. */
    @Autowired(required = false)
    public void setAutoApproval(AutoApprovalPolicy autoApproval) {
        this.autoApproval = autoApproval;
    }

    @Autowired(required = false)
    public void setStatePackService(StatePackPublisher statePackService) {
        this.statePackService = statePackService;
    }

    @Override
    public Collection<LoadedStatePack> published() {
        return packs.all();
    }

    @Override
    public Optional<LoadedStatePack> find(String packId) {
        return packs.find(packId);
    }

    @Override
    public boolean makerChecker() {
        return security.getMakerChecker().isStatePackPublish();
    }

    @Override
    public boolean autoApproved(Draft draft, LoadedStatePack persisted, String actor) {
        if (autoApproval == null) {
            return false;
        }
        var previous = draft.published() == null ? Optional.<LoadedStatePack>empty() : packs.find(draft.packId());
        if (!autoApproval.packEdit(previous, persisted, true).autoApproved()) {
            return false;
        }
        autoApproval.recordAutoApproved("STATE_PACK", draft.packId(), draft.stateCode(), actor);
        return true;
    }

    @Override
    public void submitted(Approval approval, Map<String, String> files) {
        if (statePackService != null) {
            statePackService.publish(approval.submittedBy(), files);
        }
    }

    @Override
    public void rejected(Approval pending, String checker, String reason, Map<String, String> files) {
        if (statePackService != null) {
            statePackService.publish(pending.submittedBy(), files);
            statePackService.reject(pending.packId(), pending.version(), checker, reason);
        }
    }

    @Override
    public String activate(Draft draft, String version, LoadedStatePack persisted, Approval approval,
                           Map<String, String> files, String actor) {
        if (statePackService != null && approval != null) {
            statePackService.publish(approval.submittedBy(), files);
            statePackService.approveAndActivate(draft.packId(), version, approval.decidedBy());
        } else if (statePackService != null) {
            statePackService.publishActive(actor, files);
        }
        documents.save(draft.displayName(), persisted);
        if (draft.published() != null && !draft.published().version().equals(version)) {
            retire(draft.packId(), draft.published().version());
        }
        lifecycle.register(draft.packId());
        transitionToActive(draft.packId(), version);
        return "postgresql:state_pack_document/" + draft.packId();
    }

    private void transitionToActive(String packId, String version) {
        String status = lifecycleStatus(packId, version);
        if ("DRAFT".equals(status)) {
            lifecycle.transition(packId, version, "VALIDATED");
            status = "VALIDATED";
        }
        if ("VALIDATED".equals(status)) {
            lifecycle.transition(packId, version, "ACTIVE");
        }
    }

    private void retire(String packId, String version) {
        if ("ACTIVE".equals(lifecycleStatus(packId, version))) {
            lifecycle.transition(packId, version, "RETIRED");
        }
    }

    private String lifecycleStatus(String packId, String version) {
        try (var c = connections.open();
                var s = c.prepareStatement("select status from state_pack_lifecycle where pack_id=? and version=?")) {
            s.setString(1, packId);
            s.setString(2, version);
            try (var r = s.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the State Pack lifecycle", e);
        }
    }
}
