package gov.uddp.core.inbox;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.inbox.ExceptionSource.Item;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** The exception sources that exist on this branch: runs, gates, mapping, stewardship and approvals (Track C4). */
public final class PlatformExceptionSources {
    private PlatformExceptionSources() {
    }

    interface Row {
        Item read(java.sql.ResultSet r) throws java.sql.SQLException;
    }

    static List<Item> query(PostgresConnectionFactory connections, String sql, Row row) {
        List<Item> items = new ArrayList<>();
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            var r = s.executeQuery();
            while (r.next()) {
                items.add(row.read(r));
            }
            return items;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the exception items", e);
        }
    }

    static Instant at(java.sql.ResultSet r, int column) throws java.sql.SQLException {
        return r.getTimestamp(column).toInstant();
    }

    /** Runs waiting for a release decision, and the failed gates behind them. */
    @Component
    public static class PausedRuns implements ExceptionSource {
        private final PostgresConnectionFactory connections;

        public PausedRuns(PostgresConnectionFactory connections) {
            this.connections = connections;
        }

        @Override
        public List<String> types() {
            return List.of("PAUSED_RUN", "GATE_FAILURE");
        }

        @Override
        public List<Item> items() {
            List<Item> items = query(connections, "select r.onboarding_run_id,r.state_code,r.source_system,"
                    + "coalesce((select d.reasons from run_release_decision d where d.onboarding_run_id="
                    + "r.onboarding_run_id and d.decision='PENDING' order by d.decided_at desc limit 1),''),"
                    + "r.started_at from onboarding_run r where r.status='PAUSED' order by r.started_at desc",
                    r -> new Item("PAUSED_RUN:" + r.getString(1), "PAUSED_RUN",
                            "Run waiting for release: " + r.getString(3), r.getString(4), "HIGH", r.getString(2),
                            r.getString(3), at(r, 5), "/onboarding-runs/" + r.getString(1)));
            items.addAll(query(connections, "select g.gate_result_id,g.state_code,g.source_system,g.gate_type,"
                    + "g.observed,g.threshold,g.evaluated_at,g.onboarding_run_id from quality_gate_result g"
                    + " join onboarding_run r on r.onboarding_run_id=g.onboarding_run_id where r.status='PAUSED'"
                    + " and g.status='FAIL' order by g.evaluated_at desc",
                    r -> new Item("GATE_FAILURE:" + r.getString(1), "GATE_FAILURE",
                            "Quality gate failed: " + r.getString(4) + " on " + r.getString(3),
                            "Measured " + r.getString(5) + " against a limit of " + r.getString(6), "HIGH",
                            r.getString(2), r.getString(3), at(r, 7), "/onboarding-runs/" + r.getString(8))));
            return items;
        }
    }

    /** Runs that failed; those started by a schedule are reported as FAILED_SCHEDULED_RUN. */
    @Component
    public static class FailedRuns implements ExceptionSource {
        private final PostgresConnectionFactory connections;

        public FailedRuns(PostgresConnectionFactory connections) {
            this.connections = connections;
        }

        @Override
        public List<String> types() {
            return List.of("FAILED_RUN", "FAILED_SCHEDULED_RUN");
        }

        @Override
        public List<Item> items() {
            return query(connections, "select r.onboarding_run_id,r.state_code,r.source_system,"
                    + "coalesce(r.failure_message,''),r.started_at,exists(select 1 from schedule_trigger t where"
                    + " t.onboarding_run_id=r.onboarding_run_id) from onboarding_run r where r.status='FAILED'"
                    + " order by r.started_at desc limit 200",
                    r -> {
                        String type = r.getBoolean(6) ? "FAILED_SCHEDULED_RUN" : "FAILED_RUN";
                        return new Item(type + ":" + r.getString(1), type,
                                (r.getBoolean(6) ? "Scheduled run failed: " : "Run failed: ") + r.getString(3),
                                r.getString(4), "HIGH", r.getString(2), r.getString(3), at(r, 5),
                                "/onboarding-runs/" + r.getString(1));
                    });
        }
    }

    /** Crosswalk values the pack does not map, from each source's latest delivery. */
    @Component
    public static class UnmappedCodes implements ExceptionSource {
        private final PostgresConnectionFactory connections;

        public UnmappedCodes(PostgresConnectionFactory connections) {
            this.connections = connections;
        }

        @Override
        public List<String> types() {
            return List.of("UNMAPPED_CODE");
        }

        @Override
        public List<Item> items() {
            return query(connections, "select b.batch_id,b.state_code,b.source_system,count(*),"
                    + "min(substring(e.error_message from 'No crosswalk value for ([^:]+)')),max(e.occurred_at)"
                    + " from canonical_processing_error e join ingestion_batch b on b.batch_id=e.batch_id"
                    + " where e.error_message like 'No crosswalk value%' and b.batch_id=(select x.batch_id from"
                    + " ingestion_batch x where x.state_code=b.state_code and x.source_system=b.source_system"
                    + " order by x.started_at desc limit 1) group by b.batch_id,b.state_code,b.source_system",
                    r -> new Item("UNMAPPED_CODE:" + r.getString(1), "UNMAPPED_CODE",
                            r.getInt(4) + " unmapped code value(s) in the latest delivery of " + r.getString(3),
                            "For example in crosswalk " + r.getString(5), "MEDIUM", r.getString(2), r.getString(3),
                            at(r, 6), "/onboarding-studio"));
        }
    }

    /** Benefit scheme codes kept UNMAPPED because the pack's crosswalk lacks them; mapping them reprocesses those rows. */
    @Component
    public static class UnmappedBenefitCodes implements ExceptionSource {
        private final PostgresConnectionFactory connections;

        public UnmappedBenefitCodes(PostgresConnectionFactory connections) {
            this.connections = connections;
        }

        @Override
        public List<String> types() {
            return List.of("UNMAPPED_BENEFIT_CODE");
        }

        @Override
        public List<Item> items() {
            return query(connections, "select b.batch_id,b.state_code,b.source_system,count(*),"
                    + "min(w.source_code),max(w.created_at) from canonical_benefit_warning w"
                    + " join ingestion_batch b on b.batch_id=w.batch_id where w.resolved_at is null"
                    + " and w.warning_type='UNMAPPED_CODE' group by b.batch_id,b.state_code,b.source_system",
                    r -> new Item("UNMAPPED_BENEFIT_CODE:" + r.getString(1), "UNMAPPED_BENEFIT_CODE",
                            r.getInt(4) + " benefit row(s) with an unmapped scheme code in " + r.getString(3),
                            "For example code " + r.getString(5) + "; map it in the crosswalk to reprocess these rows",
                            "MEDIUM", r.getString(2), r.getString(3), at(r, 6), "/onboarding-studio"));
        }
    }

    /** Possible-match review tasks that no steward decided yet. */
    @Component
    public static class ReviewTasks implements ExceptionSource {
        private final PostgresConnectionFactory connections;

        public ReviewTasks(PostgresConnectionFactory connections) {
            this.connections = connections;
        }

        @Override
        public List<String> types() {
            return List.of("REVIEW_TASK");
        }

        @Override
        public List<Item> items() {
            return query(connections, "select t.review_task_id,c.state_code,c.source_system,t.match_probability,"
                    + "t.created_at from stewardship_review_task t join canonical_resident c on"
                    + " c.canonical_resident_id=t.canonical_record_a where not exists (select 1 from"
                    + " stewardship_decision d where d.review_task_id=t.review_task_id)"
                    + " order by t.created_at desc limit 200",
                    r -> new Item("REVIEW_TASK:" + r.getString(1), "REVIEW_TASK", "Possible match to review",
                            "Match probability " + String.format(java.util.Locale.ROOT, "%.2f", r.getDouble(4)),
                            "LOW", r.getString(2), r.getString(3), at(r, 5), "/stewardship"));
        }
    }

    /** State Pack publications and stewardship proposals waiting for a checker. */
    @Component
    public static class PendingApprovals implements ExceptionSource {
        private final PostgresConnectionFactory connections;

        public PendingApprovals(PostgresConnectionFactory connections) {
            this.connections = connections;
        }

        @Override
        public List<String> types() {
            return List.of("PACK_APPROVAL", "PROPOSAL_APPROVAL");
        }

        @Override
        public List<Item> items() {
            List<Item> items = query(connections, "select approval_id,state_code,pack_id,version,submitted_at,"
                    + "submitted_by from state_pack_approval where status='PENDING_APPROVAL' order by submitted_at desc",
                    r -> new Item("PACK_APPROVAL:" + r.getString(1), "PACK_APPROVAL",
                            "State Pack " + r.getString(3) + " " + r.getString(4) + " awaits approval",
                            "Submitted by " + r.getString(6), "MEDIUM", r.getString(2), null, at(r, 5),
                            "/onboarding-studio/approvals"));
            items.addAll(query(connections, "select proposal_id,state_codes[1],action,proposed_at,proposed_by"
                    + " from stewardship_proposal where status='PENDING_APPROVAL' order by proposed_at desc",
                    r -> new Item("PROPOSAL_APPROVAL:" + r.getString(1), "PROPOSAL_APPROVAL",
                            "Stewardship proposal awaits approval: " + r.getString(3), "Proposed by " + r.getString(5),
                            "MEDIUM", r.getString(2), null, at(r, 4), "/stewardship")));
            return items;
        }
    }
}
