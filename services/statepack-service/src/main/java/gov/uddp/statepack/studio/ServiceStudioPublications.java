package gov.uddp.statepack.studio;

import gov.uddp.contracts.StatePackClient;
import gov.uddp.contracts.StatePackRef;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.studio.StudioModels.Approval;
import gov.uddp.core.studio.StudioModels.Draft;
import gov.uddp.core.studio.StudioPublications;
import gov.uddp.statepack.StatePacks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Studio's publications where statepack-service hosts it: a submission is published here by its maker
 * (PENDING_APPROVAL), the checker's decision is this service's approve-and-activate or reject, and the published packs
 * the Studio compares against are this service's ACTIVE versions, loaded by the State Pack loader (cached by digest).
 * The activation event installs the version at the edge.
 */
@Component
public class ServiceStudioPublications implements StudioPublications {

    /** The checker recorded for a version published without a human checker (maker-checker off). */
    public static final String AUTO_CHECKER = "platform-auto-approval";

    private final StatePacks packs;
    private final StatePackLoader loader;
    private final boolean makerChecker;
    private final Map<String, LoadedStatePack> loaded = new ConcurrentHashMap<>();
    private final Path cache;

    public ServiceStudioPublications(StatePacks packs, StatePackLoader loader,
                                     @Value("${uddp.studio.maker-checker:true}") boolean makerChecker) {
        this.packs = packs;
        this.loader = loader;
        this.makerChecker = makerChecker;
        try {
            this.cache = Files.createTempDirectory("uddp-statepack-studio-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Collection<LoadedStatePack> published() {
        List<LoadedStatePack> out = new ArrayList<>();
        for (var version : packs.list(null, "ACTIVE")) {
            out.add(loaded.computeIfAbsent(version.digest(), digest -> load(version.packId(), version.version())));
        }
        return out;
    }

    @Override
    public Optional<LoadedStatePack> find(String packId) {
        return published().stream().filter(p -> p.manifest().packId().equals(packId)).findFirst();
    }

    @Override
    public boolean makerChecker() {
        return makerChecker;
    }

    @Override
    public boolean autoApproved(Draft draft, LoadedStatePack persisted, String actor) {
        return false;
    }

    @Override
    public void submitted(Approval approval, Map<String, String> files) {
        packs.publish(approval.submittedBy(), files);
    }

    @Override
    public void rejected(Approval pending, String checker, String reason, Map<String, String> files) {
        packs.publish(pending.submittedBy(), files);
        packs.reject(pending.packId(), pending.version(), checker, reason);
    }

    @Override
    public String activate(Draft draft, String version, LoadedStatePack persisted, Approval approval,
                           Map<String, String> files, String actor) {
        String maker = approval == null ? actor : approval.submittedBy();
        String checker = approval == null ? (AUTO_CHECKER.equalsIgnoreCase(actor) ? AUTO_CHECKER + "-2" : AUTO_CHECKER)
                : approval.decidedBy();
        var published = packs.publish(maker, files);
        if ("PENDING_APPROVAL".equals(published.status())) {
            packs.approve(published.packId(), published.version(), checker);
        }
        if (!"ACTIVE".equals(packs.get(published.packId(), published.version()).orElseThrow().status())) {
            packs.activate(published.packId(), published.version(), checker);
        }
        return "statepack:" + published.packId() + "/" + published.version();
    }

    private LoadedStatePack load(String packId, String version) {
        var pack = packs.get(packId, version).orElseThrow();
        try {
            Path dir = StatePackClient.materialize(new StatePackClient.Pack(new StatePackRef(pack.packId(),
                    pack.version(), pack.digest()), pack.stateCode(), pack.status(), pack.files()), cache);
            return loader.load(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
