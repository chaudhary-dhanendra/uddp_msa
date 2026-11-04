package gov.uddp.core.read;

import gov.uddp.core.read.statepack.StatePackQueryService;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.security.StateScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/** Platform-wide and per-state counts for the overview dashboard. */
@Service
public class OverviewReadService {
    /**
     * The current decision of each canonical pair: the decision of the most recently started completed matching run
     * that scored it, for pairs whose two records are both latest canonical versions.
     */
    static final String CURRENT_PAIRS = "with latest as (select distinct on (p.canonical_record_a, p.canonical_record_b)"
            + " p.canonical_record_a, p.canonical_record_b, p.decision from resident_match_pair p"
            + " join matching_run r on r.run_id = p.run_id where r.status = 'COMPLETED'"
            + " order by p.canonical_record_a, p.canonical_record_b, r.started_at desc, r.run_id desc)"
            + " select l.decision, ca.state_code as state_a, cb.state_code as state_b from latest l"
            + " join canonical_resident_current ca on ca.canonical_resident_id = l.canonical_record_a"
            + " join canonical_resident_current cb on cb.canonical_resident_id = l.canonical_record_b";

    public record DecisionCounts(long autoMatch, long possibleMatch, long noMatch) {
        DecisionCounts add(String decision) {
            return switch (decision) {
                case "AUTO_MATCH" -> new DecisionCounts(autoMatch + 1, possibleMatch, noMatch);
                case "POSSIBLE_MATCH" -> new DecisionCounts(autoMatch, possibleMatch + 1, noMatch);
                default -> new DecisionCounts(autoMatch, possibleMatch, noMatch + 1);
            };
        }
    }

    /**
     * Counts for one state. {@code goldenResidents} counts active Golden identities with at least one current member
     * from this state, so a cross-state identity counts in each of its states; {@code matchDecisions} counts current
     * pair decisions touching a record of this state.
     */
    public record StateStats(String stateCode, String packId, long batches, long rawRecords, long canonicalResidents,
                             long currentCanonicalResidents, long processingErrors, long dqErrors, long dqWarnings,
                             long goldenResidents, DecisionCounts matchDecisions) {
    }

    /**
     * Platform totals. {@code reduction} is current canonical records minus active Golden identities, and
     * {@code reductionPercent} that difference as a percentage of current canonical records.
     */
    public record OverviewTotals(long states, long batches, long rawRecords, long canonicalResidents,
                                 long currentCanonicalResidents, long processingErrors, long dqErrors, long dqWarnings,
                                 long goldenResidents, long retiredGoldenResidents, long crossStateGoldenResidents,
                                 long reduction, double reductionPercent, DecisionCounts matchDecisions,
                                 long matchingRuns, long onboardingRuns) {
    }

    /**
     * Identity (Track E): current canonical records in scope that carry each identifier type, active Golden Residents
     * and Families with a UDDP ID, and the version of the approved Identity Policy (null when none is approved).
     */
    public record IdentityStats(Map<String, Long> recordsWithIdentifier, long residentsWithUddpId,
                                long familiesWithUddpId, Integer identityPolicyVersion) {
    }

    public record OverviewStats(OverviewTotals totals, List<StateStats> states, IdentityStats identity) {
    }

    private final Db db;
    private final StatePackQueryService packs;

    public OverviewReadService(Db db, StatePackQueryService packs) {
        this.db = db;
        this.packs = packs;
    }

    /**
     * For a user who does not see every state (ADR-0019), only the states in scope, totals over those states, Golden
     * counts over identities with a member in scope and decisions on pairs with both records in scope.
     */
    public OverviewStats overview() {
        StateScope scope = CurrentUser.scope();
        Map<String, String> packIds = new TreeMap<>();
        packs.list().forEach(pack -> packIds.put(pack.stateCode(), pack.packId()));
        var codes = new TreeSet<>(packIds.keySet());
        var batches = grouped("select state_code, count(*) from ingestion_batch group by state_code");
        var raw = grouped("select state_code, count(*) from raw_record group by state_code");
        var canonical = grouped("select state_code, count(*) from canonical_resident"
                + " where canonical_entity_type = 'Resident' group by state_code");
        var current = grouped("select state_code, count(*) from canonical_resident_current group by state_code");
        var errors = grouped("select b.state_code, count(*) from canonical_processing_error e"
                + " join ingestion_batch b on b.batch_id = e.batch_id group by b.state_code");
        String latestEvaluations = "(select distinct on (batch_id) * from dq_batch_evaluation"
                + " order by batch_id, evaluated_at desc, dq_evaluation_id desc) e";
        var dqErrors = grouped("select e.state_code, sum(e.error_count) from " + latestEvaluations + " group by e.state_code");
        var dqWarnings = grouped("select e.state_code, sum(e.warning_count) from " + latestEvaluations
                + " group by e.state_code");
        var golden = grouped("select c.state_code, count(distinct m.golden_resident_id) from golden_resident_membership m"
                + " join golden_resident g on g.golden_resident_id = m.golden_resident_id and g.status = 'ACTIVE'"
                + " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id"
                + " where m.effective_to is null group by c.state_code");
        List.of(batches, raw, canonical).forEach(map -> codes.addAll(map.keySet()));
        List.of(batches, raw, canonical, current, errors, dqErrors, dqWarnings, golden)
                .forEach(map -> map.keySet().removeIf(code -> !scope.allows(code)));

        Map<String, DecisionCounts> decisions = new TreeMap<>();
        DecisionCounts total = new DecisionCounts(0, 0, 0);
        for (var pair : db.list(CURRENT_PAIRS, r -> new String[] {r.getString(1), r.getString(2), r.getString(3)})) {
            if (!scope.allows(pair[1]) || !scope.allows(pair[2])) {
                continue;
            }
            total = total.add(pair[0]);
            decisions.merge(pair[1], new DecisionCounts(0, 0, 0).add(pair[0]), OverviewReadService::sum);
            if (!pair[2].equals(pair[1])) {
                decisions.merge(pair[2], new DecisionCounts(0, 0, 0).add(pair[0]), OverviewReadService::sum);
            }
        }
        codes.addAll(decisions.keySet());
        codes.removeIf(code -> !scope.allows(code));

        List<StateStats> states = new ArrayList<>();
        for (String code : codes) {
            states.add(new StateStats(code, packIds.get(code), get(batches, code), get(raw, code), get(canonical, code),
                    get(current, code), get(errors, code), get(dqErrors, code), get(dqWarnings, code), get(golden, code),
                    decisions.getOrDefault(code, new DecisionCounts(0, 0, 0))));
        }
        long currentTotal = sum(current);
        long activeGolden = db.count("select count(*) from golden_resident g where status = 'ACTIVE'"
                + GoldenVisibility.visible("g"), Db.scopeParameters());
        long reduction = currentTotal - activeGolden;
        double percent = currentTotal == 0 ? 0 : Math.round(1000.0 * reduction / currentTotal) / 10.0;
        var totals = new OverviewTotals(states.size(), sum(batches), sum(raw), sum(canonical), currentTotal, sum(errors),
                sum(dqErrors), sum(dqWarnings), activeGolden,
                db.count("select count(*) from golden_resident g where status = 'RETIRED'" + GoldenVisibility.visible("g"),
                        Db.scopeParameters()),
                db.count("select count(*) from (select m.golden_resident_id from golden_resident_membership m"
                        + " join golden_resident g on g.golden_resident_id = m.golden_resident_id and g.status = 'ACTIVE'"
                        + " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id"
                        + " where m.effective_to is null" + Db.inScope("c.state_code") + " group by m.golden_resident_id"
                        + " having count(distinct c.state_code) > 1) x", Db.scopeParameters()),
                reduction, percent, total,
                db.count("select count(*) from matching_run"),
                db.count("select count(*) from onboarding_run where true" + Db.inScope("state_code"),
                        Db.scopeParameters()));
        return new OverviewStats(totals, states, identity());
    }

    private IdentityStats identity() {
        Map<String, Long> byType = new TreeMap<>();
        db.list("select t.type, count(*) from canonical_resident_current c,"
                + " jsonb_object_keys(coalesce(c.canonical_payload->'extensions'->'uddp.identifiers', '{}'::jsonb)) t(type)"
                + " where true" + Db.inScope("c.state_code") + " group by t.type", r -> {
                    byType.put(r.getString(1), r.getLong(2));
                    return null;
                }, Db.scopeParameters());
        long residents = db.count("select count(*) from golden_resident g join uddp_id u on u.kind = 'RESIDENT'"
                + " and u.golden_id = g.golden_resident_id where g.status = 'ACTIVE'" + GoldenVisibility.visible("g"),
                Db.scopeParameters());
        long families = db.count("select count(*) from golden_family f join uddp_id u on u.kind = 'FAMILY'"
                + " and u.golden_id = f.golden_family_id where f.status = 'ACTIVE'" + Db.inScope("f.state_code"),
                Db.scopeParameters());
        Integer policy = db.one("select version from identity_policy where status = 'APPROVED'", r -> r.getInt(1))
                .orElse(null);
        return new IdentityStats(byType, residents, families, policy);
    }

    private Map<String, Long> grouped(String sql) {
        Map<String, Long> out = new TreeMap<>();
        db.list(sql, r -> {
            out.put(r.getString(1), r.getLong(2));
            return null;
        });
        return out;
    }

    private static long get(Map<String, Long> counts, String code) {
        return counts.getOrDefault(code, 0L);
    }

    private static long sum(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    private static DecisionCounts sum(DecisionCounts a, DecisionCounts b) {
        return new DecisionCounts(a.autoMatch() + b.autoMatch(), a.possibleMatch() + b.possibleMatch(),
                a.noMatch() + b.noMatch());
    }
}
