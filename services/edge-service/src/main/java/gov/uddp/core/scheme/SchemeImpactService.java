package gov.uddp.core.scheme;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenBuildCompletedEvent;
import gov.uddp.core.rules.RuleSetService;
import gov.uddp.core.scheme.SchemeEligibilityService.Outcome;
import gov.uddp.core.statepack.StatePackLoader;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Track A5: per-scheme data availability, the impact preview (no writes) and eligibility recomputed after every
 * Golden build with stored explanations. Generic over schemes: everything comes from the Scheme Pack, its eligibility
 * rule set's required attributes and the Source Packs' mappings.
 */
@Service
public class SchemeImpactService {
    private static final Logger log = LoggerFactory.getLogger(SchemeImpactService.class);
    static final String DISTRICT_PATH = "address.districtLGD";

    private final PostgresConnectionFactory db;
    private final ObjectMapper json;
    private final RuleSetService rules;
    private final SchemePackService schemes;
    private final SchemeEligibilityService eligibility;
    private final StatePackLoader packs;

    public SchemeImpactService(PostgresConnectionFactory db, ObjectMapper json, RuleSetService rules,
                               SchemePackService schemes, SchemeEligibilityService eligibility, StatePackLoader packs) {
        this.db = db;
        this.json = json;
        this.rules = rules;
        this.schemes = schemes;
        this.eligibility = eligibility;
        this.packs = packs;
    }

    // ---- (1) data availability --------------------------------------------------------------------------------

    public record AttributeAvailability(String attribute, List<String> suppliedBy, int withValue, int population,
                                        double percent) {
    }

    public record DataAvailability(String schemeId, UUID versionId, String level, int population,
                                   List<AttributeAvailability> attributes) {
    }

    public DataAvailability dataAvailability(SchemePackService.Version scheme) {
        boolean household = household(scheme);
        List<String> attributes = scheme.eligibilityRuleSetId() == null ? List.of()
                : rules.getById(scheme.eligibilityRuleSetId()).requiredAttributes();
        String entity = household ? "Family" : "Resident";
        int population = population(scheme).size();
        List<AttributeAvailability> out = new ArrayList<>();
        for (String attribute : attributes) {
            List<String> sources = new ArrayList<>();
            for (var pack : packs.all()) {
                for (var mapping : pack.mappings()) {
                    if (mapping.entities() == null) {
                        continue;
                    }
                    boolean supplies = mapping.entities().getOrDefault(entity, List.of()).stream()
                            .anyMatch(rule -> attribute.equals(rule.target()));
                    String source = pack.manifest().packId() + "/" + (mapping.sourceId() == null ? "" : mapping.sourceId());
                    if (supplies && !sources.contains(source)) {
                        sources.add(source);
                    }
                }
            }
            int withValue = withValue(household, attribute);
            out.add(new AttributeAvailability(attribute, List.copyOf(sources), withValue, population,
                    population == 0 ? 0 : Math.round(1000.0 * withValue / population) / 10.0));
        }
        return new DataAvailability(scheme.schemeId(), scheme.id(), household ? "HOUSEHOLD" : "INDIVIDUAL", population,
                List.copyOf(out));
    }

    // ---- (2) impact preview -----------------------------------------------------------------------------------

    public record Counts(int eligible, int enrolled, int eligibleNotEnrolled, int enrolledNotEligible, int indeterminate) {
        Counts add(boolean isEligible, boolean isEnrolled, boolean isIndeterminate) {
            return new Counts(eligible + (isEligible ? 1 : 0), enrolled + (isEnrolled ? 1 : 0),
                    eligibleNotEnrolled + (isEligible && !isEnrolled ? 1 : 0),
                    enrolledNotEligible + (isEnrolled && !isEligible && !isIndeterminate ? 1 : 0),
                    indeterminate + (isIndeterminate ? 1 : 0));
        }
    }

    public record Impact(String schemeId, UUID versionId, int evaluated, Counts totals, int exclusivityOverlaps,
                         Map<String, Counts> byDistrict) {
    }
    /** Requested F6 geography aggregation; DISTRICT retains the established impact contract. */
    public enum GeographyDimension { STATE, DISTRICT, BLOCK, VILLAGE }

    /**
     * Impact grouped by the requested geography level. Residents (or households) without a resolved value at that
     * level are counted in {@code geographyUnavailable} and described by {@code caveat}; they are never shown as a
     * zero under some area.
     */
    public record GeographyImpact(Impact impact, GeographyDimension dimension, int geographyAvailable,
                                  int geographyUnavailable, Map<String, Counts> byGeography, String caveat) { }

    /** Evaluates the whole Golden population against this version; reads only. */
    public Impact impact(SchemePackService.Version scheme) {
        Counts totals = new Counts(0, 0, 0, 0, 0);
        Map<String, Counts> byDistrict = new TreeMap<>();
        int overlaps = 0;
        List<UUID> population = population(scheme);
        for (UUID id : population) {
            var result = eligibility.evaluate(scheme, id);
            boolean isEligible = result.outcome() == Outcome.ELIGIBLE;
            boolean isIndeterminate = result.outcome() == Outcome.INDETERMINATE;
            boolean isEnrolled = enrolled(scheme, id);
            totals = totals.add(isEligible, isEnrolled, isIndeterminate);
            byDistrict.merge(district(scheme, id), new Counts(0, 0, 0, 0, 0).add(isEligible, isEnrolled, isIndeterminate),
                    (a, b) -> new Counts(a.eligible() + b.eligible(), a.enrolled() + b.enrolled(),
                            a.eligibleNotEnrolled() + b.eligibleNotEnrolled(),
                            a.enrolledNotEligible() + b.enrolledNotEligible(), a.indeterminate() + b.indeterminate()));
            if (!result.exclusivityConflicts().isEmpty()) {
                overlaps++;
            }
        }
        return new Impact(scheme.schemeId(), scheme.id(), population.size(), totals, overlaps, Map.copyOf(byDistrict));
    }

    /** F6 availability-aware aggregation over canonical resolved geography only. */
    public GeographyImpact impact(SchemePackService.Version scheme, GeographyDimension dimension) {
        Impact base = impact(scheme);
        Map<String, Counts> grouped = new TreeMap<>();
        int available = 0;
        for (UUID id : population(scheme)) {
            String code = geography(scheme, id, dimension);
            if (code == null || code.isBlank()) continue;
            available++;
            var result = eligibility.evaluate(scheme, id);
            boolean eligibleNow = result.outcome() == Outcome.ELIGIBLE;
            boolean indeterminate = result.outcome() == Outcome.INDETERMINATE;
            Counts one = new Counts(0,0,0,0,0).add(eligibleNow, enrolled(scheme, id), indeterminate);
            grouped.merge(code, one, (a,b) -> new Counts(a.eligible()+b.eligible(), a.enrolled()+b.enrolled(),
                    a.eligibleNotEnrolled()+b.eligibleNotEnrolled(), a.enrolledNotEligible()+b.enrolledNotEligible(),
                    a.indeterminate()+b.indeterminate()));
        }
        int unavailable = base.evaluated() - available;
        String caveat = unavailable == 0 ? null : unavailable + " of " + base.evaluated() + " evaluated "
                + (household(scheme) ? "households" : "residents") + " have no resolved "
                + dimension.name().toLowerCase(java.util.Locale.ROOT) + " geography; they are left out of the "
                + "breakdown, not counted as zero.";
        return new GeographyImpact(base, dimension, available, unavailable, Map.copyOf(grouped), caveat);
    }

    // ---- (4) recompute after each Golden build ------------------------------------------------------------------

    public record Computation(UUID computationId, String schemeId, UUID versionId, UUID ruleSetId, int evaluated,
                              int eligible, int notEligible, int indeterminate) {
    }

    @EventListener
    public void afterGoldenBuild(GoldenBuildCompletedEvent event) {
        try {
            recomputeAll(event.matchingRunId(), LocalDate.now());
        } catch (RuntimeException e) {
            log.warn("Scheme eligibility recompute after Golden build failed: {}", e.getMessage());
        }
    }

    /** Recomputes every scheme whose effective approved version has an eligibility rule set; returns the runs. */
    public List<Computation> recomputeAll(UUID matchingRunId, LocalDate on) {
        List<Computation> out = new ArrayList<>();
        for (var logical : schemes.list().stream().map(SchemePackService.Version::schemeId).distinct().toList()) {
            schemes.effective(logical, on)
                    .filter(v -> v.eligibilityRuleSetId() != null)
                    .ifPresent(v -> out.add(recompute(v, matchingRunId)));
        }
        return List.copyOf(out);
    }

    public Computation recompute(SchemePackService.Version scheme, UUID matchingRunId) {
        UUID computation = UUID.randomUUID();
        int eligible = 0;
        int notEligible = 0;
        int indeterminate = 0;
        List<SchemeEligibilityService.Result> results = new ArrayList<>();
        for (UUID id : population(scheme)) {
            var result = eligibility.evaluate(scheme, id);
            results.add(result);
            switch (result.outcome()) {
                case ELIGIBLE -> eligible++;
                case NOT_ELIGIBLE -> notEligible++;
                default -> indeterminate++;
            }
        }
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                try (var s = c.prepareStatement("insert into scheme_eligibility_computation(computation_id,scheme_id,"
                        + "scheme_pack_version_id,rule_set_id,matching_run_id,evaluated,eligible,not_eligible,indeterminate)"
                        + " values (?,?,?,?,?,?,?,?,?)")) {
                    s.setObject(1, computation);
                    s.setString(2, scheme.schemeId());
                    s.setObject(3, scheme.id());
                    s.setObject(4, scheme.eligibilityRuleSetId());
                    s.setObject(5, matchingRunId);
                    s.setInt(6, results.size());
                    s.setInt(7, eligible);
                    s.setInt(8, notEligible);
                    s.setInt(9, indeterminate);
                    s.executeUpdate();
                }
                try (var s = c.prepareStatement("insert into scheme_eligibility_result(computation_id,entity_id,outcome,"
                        + "clause_references,missing_attributes,explanation) values (?,?,?,cast(? as jsonb),cast(? as jsonb),?)")) {
                    for (var result : results) {
                        s.setObject(1, computation);
                        s.setObject(2, result.entityId());
                        s.setString(3, result.outcome().name());
                        s.setString(4, json.writeValueAsString(result.clauseReferences()));
                        s.setString(5, json.writeValueAsString(result.missingAttributes()));
                        s.setString(6, result.explanation());
                        s.addBatch();
                    }
                    s.executeBatch();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot store eligibility results", e);
        }
        return new Computation(computation, scheme.schemeId(), scheme.id(), scheme.eligibilityRuleSetId(),
                results.size(), eligible, notEligible, indeterminate);
    }

    public record StoredResult(UUID entityId, String outcome, List<String> clauseReferences,
                               List<String> missingAttributes, String explanation) {
    }

    public record LatestResults(Computation computation, List<StoredResult> results) {
    }

    /** The latest stored computation of a scheme and up to {@code limit} of its results. */
    public Optional<LatestResults> latest(String schemeId, int limit) {
        try (Connection c = db.open(); var s = c.prepareStatement("select computation_id,scheme_pack_version_id,"
                + "rule_set_id,evaluated,eligible,not_eligible,indeterminate from scheme_eligibility_computation"
                + " where scheme_id=? order by computed_at desc limit 1")) {
            s.setString(1, schemeId);
            var r = s.executeQuery();
            if (!r.next()) {
                return Optional.empty();
            }
            var computation = new Computation(r.getObject(1, UUID.class), schemeId, r.getObject(2, UUID.class),
                    r.getObject(3, UUID.class), r.getInt(4), r.getInt(5), r.getInt(6), r.getInt(7));
            List<StoredResult> results = new ArrayList<>();
            try (var q = c.prepareStatement("select entity_id,outcome,clause_references::text,missing_attributes::text,"
                    + "explanation from scheme_eligibility_result where computation_id=? order by entity_id limit ?")) {
                q.setObject(1, computation.computationId());
                q.setInt(2, Math.max(1, Math.min(limit, 500)));
                var rows = q.executeQuery();
                while (rows.next()) {
                    results.add(new StoredResult(rows.getObject(1, UUID.class), rows.getString(2),
                            strings(rows.getString(3)), strings(rows.getString(4)), rows.getString(5)));
                }
            }
            return Optional.of(new LatestResults(computation, List.copyOf(results)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read eligibility results", e);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static boolean household(SchemePackService.Version scheme) {
        return "HOUSEHOLD".equals(scheme.beneficiaryLevel());
    }

    private List<UUID> population(SchemePackService.Version scheme) {
        boolean h = household(scheme);
        String sql = "select " + (h ? "golden_family_id" : "golden_resident_id") + " from "
                + (h ? "golden_family" : "golden_resident") + " where status='ACTIVE' order by 1";
        try (Connection c = db.open(); var s = c.prepareStatement(sql); var r = s.executeQuery()) {
            List<UUID> out = new ArrayList<>();
            while (r.next()) {
                out.add(r.getObject(1, UUID.class));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot list Golden population", e);
        }
    }

    private int withValue(boolean household, String attribute) {
        String sql = household
                ? "select count(*) from golden_family f join golden_family_version v on v.golden_family_id=f.golden_family_id"
                + " and v.effective_to is null where f.status='ACTIVE' and coalesce(v.family_payload #>> ?::text[], '') <> ''"
                : "select count(*) from golden_resident g join golden_resident_version v on v.golden_resident_id=g.golden_resident_id"
                + " and v.effective_to is null where g.status='ACTIVE' and coalesce(v.golden_payload #>> ?::text[], '') <> ''";
        try (Connection c = db.open(); var s = c.prepareStatement(sql)) {
            s.setString(1, "{" + String.join(",", attribute.split("\\.")) + "}");
            var r = s.executeQuery();
            r.next();
            return r.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot measure attribute availability", e);
        }
    }

    private boolean enrolled(SchemePackService.Version scheme, UUID id) {
        String sql = "select 1 from benefit_holding_current h join benefit b on b.benefit_id=h.benefit_id where b.scheme_id=? and "
                + (household(scheme) ? "h.golden_family_id=?" : "h.golden_resident_id=?") + " limit 1";
        try (Connection c = db.open(); var s = c.prepareStatement(sql)) {
            s.setString(1, scheme.schemeId());
            s.setObject(2, id);
            return s.executeQuery().next();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read enrolment", e);
        }
    }

    private String district(SchemePackService.Version scheme, UUID id) {
        boolean h = household(scheme);
        String sql = "select " + (h ? "family_payload" : "golden_payload") + " #>> '{address,districtLGD}' from "
                + (h ? "golden_family_version where golden_family_id=?" : "golden_resident_version where golden_resident_id=?")
                + " and effective_to is null";
        try (Connection c = db.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            var r = s.executeQuery();
            String value = r.next() ? r.getString(1) : null;
            return value == null || value.isBlank() ? "UNKNOWN" : value;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read district", e);
        }
    }

    /**
     * The entity's code at the requested level, read from the resolved canonical geography of its current members
     * (Golden records keep no extensions). The most common code among the members wins; none means unavailable.
     */
    private String geography(SchemePackService.Version scheme, UUID id, GeographyDimension dimension) {
        if (dimension == GeographyDimension.DISTRICT) {
            String code = district(scheme, id);
            return "UNKNOWN".equals(code) ? null : code;
        }
        String level = dimension.name().toLowerCase(java.util.Locale.ROOT);
        String members = household(scheme)
                ? "select m.canonical_resident_id from golden_family_membership f join golden_resident_membership m"
                        + " on m.golden_resident_id=f.golden_resident_id and m.effective_to is null"
                        + " where f.golden_family_id=? and f.effective_to is null"
                : "select canonical_resident_id from golden_resident_membership where golden_resident_id=?"
                        + " and effective_to is null";
        String sql = "select g.code from (select c.canonical_payload->'extensions'->'uddp.geography'->?->>'lgd' as code,"
                + " c.canonical_payload->'extensions'->'uddp.geography'->?->>'status' as status"
                + " from canonical_resident c where c.canonical_resident_id in (" + members + ")) g"
                + " where nullif(g.code,'') is not null and coalesce(g.status,'') <> 'UNKNOWN'"
                + " group by g.code order by count(*) desc, g.code limit 1";
        try (Connection c = db.open(); var s = c.prepareStatement(sql)) {
            s.setString(1, level);
            s.setString(2, level);
            s.setObject(3, id);
            var r = s.executeQuery();
            return r.next() ? r.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read canonical geography", e);
        }
    }

    private List<String> strings(String text) throws Exception {
        return List.of(json.readValue(text, String[].class));
    }
}
