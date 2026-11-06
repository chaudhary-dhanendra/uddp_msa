package gov.uddp.core.gates;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.gates.QualityGateService.Result;
import gov.uddp.core.onboarding.OnboardingConflictException;
import gov.uddp.core.onboarding.ReleaseCheckpoint;
import gov.uddp.core.security.AuditService;
import gov.uddp.core.security.AuditService.AuditEvent;
import gov.uddp.core.security.CurrentUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The release checkpoint before a run's Golden build (Track C3). The gates are evaluated; a routine re-delivery that
 * passes every gate is AUTO_APPROVED and continues. A new source or any failed gate pauses the run until a person
 * other than the one who started it approves or rejects the release.
 */
@Service
public class ReleaseService implements ReleaseCheckpoint {
    private final PostgresConnectionFactory connections;
    private final QualityGateService gates;
    private final AuditService audit;
    private final boolean enabled;

    public ReleaseService(PostgresConnectionFactory connections, QualityGateService gates, AuditService audit) {
        this(connections, gates, audit, true);
    }

    /**
     * @param enabled false lets every run continue without a gate evaluation; the release API stays available
     */
    @Autowired
    public ReleaseService(PostgresConnectionFactory connections, QualityGateService gates, AuditService audit,
                          @Value("${uddp.operations.release-checkpoint.enabled:true}") boolean enabled) {
        this.connections = connections;
        this.gates = gates;
        this.audit = audit;
        this.enabled = enabled;
    }

    @Override
    public boolean pauses(UUID runId) {
        if (!enabled) {
            return false;
        }
        String latest = latestDecision(runId);
        if ("APPROVED".equals(latest) || "AUTO_APPROVED".equals(latest)) {
            return false;
        }
        Run run = run(runId);
        List<Result> results = gates.evaluate(runId);
        List<String> reasons = new ArrayList<>();
        results.stream().filter(Result::failed).forEach(r -> reasons.add("gate failed: " + r.type()));
        heldShareReason(runId).ifPresent(reasons::add);
        if (!hasEarlierCompletedRun(runId, run)) {
            reasons.add("new source: first completed delivery");
        }
        if (reasons.isEmpty()) {
            decide(runId, "AUTO_APPROVED", null, "SYSTEM");
            audit.record(new AuditEvent("SYSTEM", AutoApprovalPolicy.AUTO_APPROVED, "ONBOARDING_RUN", runId.toString(),
                    run.state(), AuditService.SUCCESS, AuditService.details("sourceSystem", run.source(),
                            "gates", results.size())));
            return false;
        }
        decide(runId, "PENDING", String.join("; ", reasons), "SYSTEM");
        setStatus(runId, "PAUSED", null);
        audit.record(new AuditEvent("SYSTEM", "RUN_PAUSED", "ONBOARDING_RUN", runId.toString(), run.state(),
                AuditService.SUCCESS, AuditService.details("sourceSystem", run.source(), "reasons", reasons)));
        return true;
    }

    private gov.uddp.core.held.HeldStore heldRecords;
    private double maxHeldPercent = 5;

    /**
     * A delivery whose held and rejected rows exceed {@code uddp.processing.max-held-percent} of its rows pauses for
     * approval before activation, whatever gates the source configures.
     */
    @Autowired(required = false)
    public void setHeldGate(gov.uddp.core.held.HeldStore heldRecords,
                            @Value("${uddp.processing.max-held-percent:5}") double maxHeldPercent) {
        this.heldRecords = heldRecords;
        this.maxHeldPercent = maxHeldPercent;
    }

    private java.util.Optional<String> heldShareReason(UUID runId) {
        UUID batch = heldRecords == null ? null : gates.deliveryBatch(runId);
        if (batch == null) {
            return java.util.Optional.empty();
        }
        var share = heldRecords.share(batch);
        if (share.percent() <= maxHeldPercent) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(String.format(java.util.Locale.ROOT,
                "held and rejected rows are %.1f%% of the delivery (limit %.1f%%)", share.percent(), maxHeldPercent));
    }

    /** A person other than the run's starter releases the paused run; it continues with the Golden build. */
    public void approve(UUID runId, String reason) {
        Run run = paused(runId);
        String approver = requireOtherUser(run);
        decide(runId, "APPROVED", blankToNull(reason), approver);
        setStatus(runId, "RUNNING", null);
    }

    public void reject(UUID runId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A rejection needs a reason");
        }
        Run run = paused(runId);
        String approver = requireOtherUser(run);
        decide(runId, "REJECTED", reason.strip(), approver);
        setStatus(runId, "FAILED", "Release rejected: " + reason.strip());
    }

    public List<Map<String, Object>> decisions(UUID runId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select decision,reasons,decided_by,decided_at from run_release_decision"
                        + " where onboarding_run_id=? order by decided_at")) {
            s.setObject(1, runId);
            var r = s.executeQuery();
            while (r.next()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("decision", r.getString(1));
                x.put("reasons", r.getString(2));
                x.put("decidedBy", r.getString(3));
                x.put("decidedAt", r.getObject(4));
                rows.add(x);
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the release decisions", e);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private record Run(String state, String source, String status, String startedBy) {
    }

    private Run run(UUID runId) {
        try (var c = connections.open();
                var s = c.prepareStatement("select state_code,source_system,status,started_by from onboarding_run"
                        + " where onboarding_run_id=?")) {
            s.setObject(1, runId);
            var r = s.executeQuery();
            r.next();
            return new Run(r.getString(1), r.getString(2), r.getString(3), r.getString(4));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the run", e);
        }
    }

    private Run paused(UUID runId) {
        Run run = run(runId);
        if (!"PAUSED".equals(run.status())) {
            throw new OnboardingConflictException("The run is not waiting for a release decision");
        }
        return run;
    }

    private static String requireOtherUser(Run run) {
        String user = CurrentUser.username();
        if (user == null) {
            throw new AccessDeniedException("A release decision needs a signed-in user");
        }
        if (user.equalsIgnoreCase(run.startedBy())) {
            throw new AccessDeniedException("You cannot approve or reject the release of a run you started");
        }
        return user;
    }

    private boolean hasEarlierCompletedRun(UUID runId, Run run) {
        try (var c = connections.open();
                var s = c.prepareStatement("select 1 from onboarding_run where state_code=? and source_system=?"
                        + " and status='COMPLETED' and onboarding_run_id<>? limit 1")) {
            s.setString(1, run.state());
            s.setString(2, run.source());
            s.setObject(3, runId);
            return s.executeQuery().next();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot look for earlier runs", e);
        }
    }

    private String latestDecision(UUID runId) {
        try (var c = connections.open();
                var s = c.prepareStatement("select decision from run_release_decision where onboarding_run_id=?"
                        + " order by decided_at desc, decision_id limit 1")) {
            s.setObject(1, runId);
            var r = s.executeQuery();
            return r.next() ? r.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the release decision", e);
        }
    }

    private void decide(UUID runId, String decision, String reasons, String by) {
        try (var c = connections.open();
                var s = c.prepareStatement("insert into run_release_decision(onboarding_run_id,decision,reasons,decided_by)"
                        + " values(?,?,?,?)")) {
            s.setObject(1, runId);
            s.setString(2, decision);
            s.setString(3, reasons);
            s.setString(4, by);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot store the release decision", e);
        }
    }

    private void setStatus(UUID runId, String status, String failure) {
        try (var c = connections.open();
                var s = c.prepareStatement("update onboarding_run set status=?,failure_message=? where onboarding_run_id=?")) {
            s.setString(1, status);
            s.setString(2, failure);
            s.setObject(3, runId);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot update the run", e);
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
