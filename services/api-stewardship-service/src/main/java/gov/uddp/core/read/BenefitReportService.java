package gov.uddp.core.read;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.read.BenefitReadService.ResidentBenefit;
import gov.uddp.core.scheme.EligibilityEvaluator.Outcome;
import gov.uddp.core.scheme.EligibilityEvaluator;
import gov.uddp.core.scheme.SchemeCatalogue.Eligibility;
import gov.uddp.core.scheme.SchemeCatalogue.Scheme;
import gov.uddp.core.scheme.SchemeCatalogue;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The scheme catalogue as the API shows it, and the M15 benefit reports (docs/benefit-domain.md sections 5 and 6):
 * duplicate benefits per exclusivity group and eligible-but-not-enrolled residents per scheme with eligibility.
 * Both are computed on request from current Golden identities and current benefit observations. Read-only.
 */
@Service
public class BenefitReportService {
    public record SchemeView(String schemeId, String name, String departmentCode, String departmentName,
                             String benefitType, String benefitForm, String beneficiaryLevel, String unit,
                             String frequency, String exclusivityGroup, Eligibility eligibility, long benefitCount,
                             long activeBenefitCount) {
    }

    public record SchemeCatalogueView(String catalogueVersion, List<SchemeCatalogue.Department> departments,
                                      List<SchemeView> schemes) {
    }

    /** A Golden Resident holding more than one current ACTIVE benefit in one exclusivity group. */
    public record DuplicateBenefitFinding(UUID goldenResidentId, String name, String exclusivityGroup,
                                          List<String> schemeIds, List<String> stateCodes, int benefitCount,
                                          List<ResidentBenefit> benefits) {
    }

    public record DuplicateBenefitReport(OffsetDateTime generatedAt, String catalogueVersion, int findingCount,
                                         List<DuplicateBenefitFinding> findings) {
    }

    public record NotEnrolledResident(UUID goldenResidentId, String name, LocalDate dateOfBirth, Integer ageYears,
                                      String gender, List<String> stateCodes) {
    }

    /**
     * One scheme's eligibility outcome over every active Golden Resident: eligible residents who hold no current
     * ACTIVE benefit in the scheme's exclusivity group are not enrolled; residents missing a needed value are
     * undetermined and not listed.
     */
    public record SchemeEligibilityResult(String schemeId, String schemeName, String departmentCode,
                                          String exclusivityGroup, Eligibility eligibility, int residentsEvaluated,
                                          int eligible, int enrolled, int undetermined, int notEnrolledCount,
                                          List<NotEnrolledResident> notEnrolled) {
    }

    public record EligibleNotEnrolledReport(LocalDate asOf, String catalogueVersion,
                                            List<SchemeEligibilityResult> schemes) {
    }

    private record Holding(UUID goldenResidentId, UUID benefitId, String schemeId) {
    }

    private record Person(UUID goldenResidentId, JsonNode payload) {
    }

    /**
     * A scheme the report evaluates: either the catalogue's declarative eligibility, or the eligibility rule set of
     * the scheme's Scheme Pack version in force on the as-of date (approved in Scheme Studio), which wins when both
     * exist because it is the one a maker and a checker signed off.
     */
    private record Target(String schemeId, String name, String department, String group, Eligibility eligibility,
                          gov.uddp.core.scheme.SchemePackService.Version pack) {
    }

    private final Db db;
    private final SchemeCatalogue catalogue;
    private final BenefitReadService benefits;
    private final GoldenVisibility goldens;
    private final Clock clock;
    private final gov.uddp.core.scheme.SchemePackService packs;
    private final gov.uddp.core.scheme.SchemeEligibilityService packEligibility;

    @Autowired
    public BenefitReportService(Db db, SchemeCatalogue catalogue, BenefitReadService benefits,
                                GoldenVisibility goldens, gov.uddp.core.scheme.SchemePackService packs,
                                gov.uddp.core.scheme.SchemeEligibilityService packEligibility) {
        this(db, catalogue, benefits, goldens, Clock.systemUTC(), packs, packEligibility);
    }

    /** Catalogue eligibility only, for callers without Scheme Packs. */
    public BenefitReportService(Db db, SchemeCatalogue catalogue, BenefitReadService benefits,
                                GoldenVisibility goldens) {
        this(db, catalogue, benefits, goldens, Clock.systemUTC(), null, null);
    }

    public BenefitReportService(Db db, SchemeCatalogue catalogue, BenefitReadService benefits,
                                GoldenVisibility goldens, Clock clock) {
        this(db, catalogue, benefits, goldens, clock, null, null);
    }

    public BenefitReportService(Db db, SchemeCatalogue catalogue, BenefitReadService benefits,
                                GoldenVisibility goldens, Clock clock, gov.uddp.core.scheme.SchemePackService packs,
                                gov.uddp.core.scheme.SchemeEligibilityService packEligibility) {
        this.db = db;
        this.catalogue = catalogue;
        this.benefits = benefits;
        this.goldens = goldens;
        this.clock = clock;
        this.packs = packs;
        this.packEligibility = packEligibility;
    }

    /** The resident-level Scheme Pack version in force on {@code on} with an eligibility rule set, if any. */
    private java.util.Optional<gov.uddp.core.scheme.SchemePackService.Version> packInForce(String schemeId,
                                                                                            LocalDate on) {
        if (packs == null || packEligibility == null) {
            return java.util.Optional.empty();
        }
        return packs.effective(schemeId, on).filter(v -> v.eligibilityRuleSetId() != null)
                .filter(v -> !"HOUSEHOLD".equals(v.beneficiaryLevel()));
    }

    private Target target(String schemeId, LocalDate on) {
        var listed = catalogue.scheme(schemeId);
        var pack = packInForce(schemeId, on).orElse(null);
        if (pack == null) {
            return listed.filter(s -> s.eligibility() != null)
                    .map(s -> new Target(s.schemeId(), s.name(), s.department(), s.group(), s.eligibility(), null))
                    .orElse(null);
        }
        String group = listed.map(Scheme::group).orElse(pack.exclusivityGroups().isEmpty() ? pack.schemeId()
                : pack.exclusivityGroups().get(0));
        String department = listed.map(Scheme::department).orElse(pack.implementingDepartments().isEmpty() ? null
                : pack.implementingDepartments().get(0));
        return new Target(pack.schemeId(), pack.name() != null ? pack.name() : listed.map(Scheme::name).orElse(null),
                department, group, listed.map(Scheme::eligibility).orElse(null), pack);
    }

    public SchemeCatalogueView catalogue() {
        Map<String, long[]> counts = new HashMap<>();
        db.list("select scheme_id, count(*) as total, count(*) filter (where status = 'ACTIVE') as active"
                        + " from benefit_state where true" + Db.inScope("state_code") + " group by scheme_id",
                r -> counts.put(r.getString("scheme_id"), new long[] {r.getLong("total"), r.getLong("active")}),
                Db.scopeParameters());
        var schemes = catalogue.schemes().stream().map(s -> {
            long[] c = counts.getOrDefault(s.schemeId(), new long[2]);
            var department = catalogue.department(s.department()).orElseThrow();
            return new SchemeView(s.schemeId(), s.name(), department.code(), department.name(), s.benefitType(),
                    s.benefitForm(), s.beneficiaryLevel(), s.unit(), s.frequency(), s.group(), s.eligibility(), c[0],
                    c[1]);
        }).toList();
        return new SchemeCatalogueView(catalogue.version(), catalogue.departments(), schemes);
    }

    public DuplicateBenefitReport duplicates() {
        Map<UUID, Map<String, Set<UUID>>> byResident = new TreeMap<>();
        // Only benefits from states in the user's scope can make a duplicate (ADR-0019).
        for (Holding h : activeHoldings(true)) {
            String group = catalogue.scheme(h.schemeId()).map(Scheme::group).orElse(h.schemeId());
            byResident.computeIfAbsent(h.goldenResidentId(), k -> new TreeMap<>())
                    .computeIfAbsent(group, k -> new HashSet<>())
                    .add(h.benefitId());
        }
        List<DuplicateBenefitFinding> findings = new ArrayList<>();
        byResident.forEach((resident, groups) -> groups.forEach((group, ids) -> {
            if (ids.size() < 2) {
                return;
            }
            var held = benefits.benefitsOf(resident);
            var inGroup = held.benefits().stream().filter(b -> ids.contains(b.benefit().benefitId())).toList();
            findings.add(new DuplicateBenefitFinding(resident, held.name(), group,
                    List.copyOf(new TreeSet<>(inGroup.stream().map(b -> b.benefit().schemeId()).toList())),
                    List.copyOf(new TreeSet<>(inGroup.stream().map(b -> b.benefit().stateCode()).toList())),
                    inGroup.size(), inGroup));
        }));
        findings.sort(java.util.Comparator.comparing(DuplicateBenefitFinding::exclusivityGroup)
                .thenComparing(f -> f.name() == null ? "" : f.name())
                .thenComparing(DuplicateBenefitFinding::goldenResidentId));
        return new DuplicateBenefitReport(OffsetDateTime.now(clock), catalogue.version(), findings.size(), findings);
    }

    /**
     * @param schemeId one scheme (it must declare eligibility), or every scheme that does when blank
     * @param asOf     ISO date the ages are computed on; today (UTC) when blank
     */
    public EligibleNotEnrolledReport eligibleNotEnrolled(String schemeId, String asOf) {
        LocalDate date;
        try {
            date = asOf == null || asOf.isBlank() ? LocalDate.now(clock) : LocalDate.parse(asOf.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("asOf must be an ISO date (YYYY-MM-DD)");
        }
        List<Target> schemes;
        if (schemeId == null || schemeId.isBlank()) {
            Set<String> ids = new java.util.TreeSet<>();
            catalogue.schemes().forEach(s -> ids.add(s.schemeId()));
            if (packs != null) {
                packs.list().forEach(v -> ids.add(v.schemeId()));
            }
            schemes = ids.stream().map(id -> target(id, date)).filter(java.util.Objects::nonNull).toList();
        } else {
            String id = schemeId.strip();
            boolean known = catalogue.scheme(id).isPresent()
                    || (packs != null && !packs.history(id).isEmpty());
            if (!known) {
                throw new IllegalArgumentException("Unknown scheme: " + id);
            }
            Target scheme = target(id, date);
            if (scheme == null) {
                throw new IllegalArgumentException("Scheme " + id + " declares no eligibility");
            }
            schemes = List.of(scheme);
        }
        // Enrolment counts in every state, so a resident enrolled elsewhere is not reported as missing; the
        // residents listed are only those the user may see, with withheld values left out (ADR-0019).
        Map<String, Set<UUID>> enrolledByGroup = new HashMap<>();
        for (Holding h : activeHoldings(false)) {
            String group = catalogue.scheme(h.schemeId()).map(Scheme::group).orElse(h.schemeId());
            enrolledByGroup.computeIfAbsent(group, k -> new HashSet<>()).add(h.goldenResidentId());
        }
        var people = db.list("select g.golden_resident_id, v.golden_payload::text from golden_resident g"
                        + " join golden_resident_version v on v.golden_resident_id = g.golden_resident_id"
                        + " and v.effective_to is null where g.status = 'ACTIVE'" + GoldenVisibility.visible("g")
                        + " order by g.golden_resident_id",
                r -> new Person(Db.uuid(r, "golden_resident_id"), db.json(r.getString(2))), Db.scopeParameters());
        var withheld = goldens.withheldFields(people.stream().map(Person::goldenResidentId).toList());
        var scope = gov.uddp.core.security.CurrentUser.scope();
        Map<UUID, List<String>> states = new LinkedHashMap<>();
        db.list("select m.golden_resident_id, string_agg(distinct c.state_code, ',' order by c.state_code) as states"
                        + " from golden_resident_membership m join canonical_resident c"
                        + " on c.canonical_resident_id = m.canonical_resident_id where m.effective_to is null"
                        + " group by m.golden_resident_id",
                r -> states.put(Db.uuid(r, "golden_resident_id"), java.util.Arrays.stream(r.getString("states")
                        .split(",")).filter(scope::allows).toList()));
        List<SchemeEligibilityResult> results = new ArrayList<>();
        for (Target scheme : schemes) {
            Set<UUID> enrolledInGroup = enrolledByGroup.getOrDefault(scheme.group(), Set.of());
            int eligible = 0;
            int enrolled = 0;
            int undetermined = 0;
            List<NotEnrolledResident> missing = new ArrayList<>();
            for (Person person : people) {
                LocalDate born = birthDate(person.payload());
                String gender = text(person.payload(), "gender");
                Outcome outcome = scheme.pack() == null
                        ? EligibilityEvaluator.evaluate(scheme.eligibility(), born, gender, date)
                        : packOutcome(scheme.pack(), person.goldenResidentId(), date);
                if (outcome == Outcome.UNDETERMINED) {
                    undetermined++;
                } else if (outcome == Outcome.ELIGIBLE) {
                    eligible++;
                    if (enrolledInGroup.contains(person.goldenResidentId())) {
                        enrolled++;
                    } else {
                        Set<String> hidden = withheld.getOrDefault(person.goldenResidentId(), Set.of());
                        boolean birthWithheld = GoldenVisibility.isWithheld(hidden, "dateOfBirth");
                        missing.add(new NotEnrolledResident(person.goldenResidentId(),
                                GoldenVisibility.isWithheld(hidden, "name") ? null : text(person.payload(), "name"),
                                birthWithheld ? null : born,
                                born == null || birthWithheld ? null : EligibilityEvaluator.ageYears(born, date),
                                GoldenVisibility.isWithheld(hidden, "gender") ? null : gender,
                                states.getOrDefault(person.goldenResidentId(), List.of())));
                    }
                }
            }
            missing.sort(java.util.Comparator.comparing((NotEnrolledResident n) -> n.name() == null ? "" : n.name())
                    .thenComparing(NotEnrolledResident::goldenResidentId));
            results.add(new SchemeEligibilityResult(scheme.schemeId(), scheme.name(), scheme.department(),
                    scheme.group(), scheme.eligibility(), people.size(), eligible, enrolled, undetermined,
                    missing.size(), missing));
        }
        return new EligibleNotEnrolledReport(date, catalogue.version(), results);
    }

    /**
     * Distinct (Golden Resident, benefit) pairs of current ACTIVE benefits, held directly or through a household;
     * {@code scoped} keeps only benefits from states in the user's scope.
     */
    private List<Holding> activeHoldings(boolean scoped) {
        String[] codes = scoped ? gov.uddp.core.security.CurrentUser.scope().sqlCodes() : null;
        return db.list("select distinct h.golden_resident_id, h.benefit_id, s.scheme_id from benefit_holding_current h"
                        + " join benefit_state s on s.benefit_id = h.benefit_id where s.status = 'ACTIVE'"
                        + Db.inScope("s.state_code"),
                r -> new Holding(Db.uuid(r, "golden_resident_id"), Db.uuid(r, "benefit_id"), r.getString("scheme_id")),
                codes, codes);
    }

    private Outcome packOutcome(gov.uddp.core.scheme.SchemePackService.Version pack, UUID resident, LocalDate on) {
        return switch (packEligibility.evaluate(pack, resident, on).outcome()) {
            case ELIGIBLE -> Outcome.ELIGIBLE;
            case NOT_ELIGIBLE -> Outcome.NOT_ELIGIBLE;
            default -> Outcome.UNDETERMINED;
        };
    }

    private static LocalDate birthDate(JsonNode payload) {
        String value = text(payload, "dateOfBirth");
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
