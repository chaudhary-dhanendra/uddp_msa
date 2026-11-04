package gov.uddp.core.read;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.scheme.SchemeCatalogue.Scheme;
import gov.uddp.core.scheme.SchemeCatalogue;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Benefits (M15, docs/benefit-domain.md): each source benefit with its platform scheme and department, its present
 * statement, the Golden Residents and Golden Family that hold it now, observations and disbursements; and a Golden
 * Resident's benefits across departments and states (Resident 360). Golden links are resolved from the current
 * Golden state on every read (ADR-0011). Read-only.
 */
@Service
public class BenefitReadService {
    /** A disbursement (payment, ration lifting) with lineage to the observation that reported it. */
    public record DisbursementView(LocalDate disbursedOn, BigDecimal amount, BigDecimal quantity, String unit,
                                   String reference, UUID canonicalBenefitObservationId, UUID rawRecordId) {
    }

    /**
     * A benefit and its present statement. {@code status} is ACTIVE, SUSPENDED or CLOSED as the source currently says,
     * or ENDED when no current delivery names it any more.
     */
    public record BenefitSummary(UUID benefitId, String benefitRecordKey, String schemeId, String schemeName,
                                 String departmentCode, String departmentName, String benefitType, String benefitForm,
                                 String beneficiaryLevel, String exclusivityGroup, String stateCode,
                                 String sourceSystem, String sourceSchemeCode, String sourceBenefitId, String status,
                                 LocalDate startDate, JsonNode entitlement, String sourceFamilyId,
                                 UUID goldenFamilyId, DisbursementView lastDisbursement, OffsetDateTime createdAt,
                                 OffsetDateTime observedAt) {
    }

    /** The canonical observation (and its raw record) that links a Golden Resident to a benefit. */
    public record BenefitLineage(String linkedVia, String residentRecordKey, UUID canonicalBenefitObservationId,
                                 UUID canonicalResidentId, UUID rawRecordId, UUID batchId) {
    }

    /** One benefit a Golden Resident holds, directly (RESIDENT), through a household (FAMILY), or both. */
    public record ResidentBenefit(BenefitSummary benefit, List<String> linkedVia, UUID goldenFamilyId,
                                  List<BenefitLineage> lineage) {
    }

    /** Resident 360 benefits: everything a Golden Resident currently holds, across departments and states. */
    public record ResidentBenefits(UUID goldenResidentId, String status, String name, int benefitCount,
                                   List<String> departments, List<String> states, List<String> schemes,
                                   List<ResidentBenefit> benefits) {
    }

    /** A resident whose current delivery names the benefit, with its Golden Resident (null before a Golden build). */
    public record BenefitBeneficiary(String residentRecordKey, UUID goldenResidentId, String name, String status,
                                     UUID canonicalBenefitObservationId, UUID canonicalResidentId, UUID rawRecordId,
                                     UUID batchId) {
    }

    /** The Golden Family holding a FAMILY-level benefit and its current members. */
    public record BenefitHousehold(UUID goldenFamilyId, String familyRecordKey, String sourceFamilyId, String status,
                                   List<UUID> memberGoldenResidentIds) {
    }

    public record BenefitObservationView(UUID canonicalBenefitObservationId, OffsetDateTime observedAt,
                                         boolean current, String benefitStatus, LocalDate startDate,
                                         JsonNode entitlement, JsonNode payload, String residentRecordKey,
                                         UUID canonicalResidentId, UUID rawRecordId, UUID batchId,
                                         String statePackVersion, String mappingVersion,
                                         String schemeCatalogueVersion) {
    }

    public record BenefitDetail(BenefitSummary benefit, List<BenefitBeneficiary> beneficiaries,
                                BenefitHousehold household, List<BenefitObservationView> observations,
                                List<DisbursementView> disbursements) {
    }

    static final Set<String> STATUSES = Set.of("ACTIVE", "SUSPENDED", "CLOSED", "ENDED", "ALL");
    private static final Map<String, String> SORT = Map.of(
            "sourceBenefitId", "s.source_benefit_id", "schemeId", "s.scheme_id", "stateCode", "s.state_code",
            "status", "s.status", "createdAt", "s.created_at", "observedAt", "s.observed_at");
    private static final String SUMMARY_COLUMNS = "select s.benefit_id, s.benefit_record_key, s.state_code,"
            + " s.source_system, s.scheme_id, s.source_benefit_id, s.created_at, s.source_scheme_code, s.status,"
            + " s.start_date, s.entitlement::text as entitlement_text, s.source_family_id, s.observed_at,"
            + " (select f.golden_family_id from golden_family f where f.family_record_key = s.family_record_key)"
            + " as golden_family_id, d.disbursed_on, d.amount, d.quantity, d.unit, d.disbursement_reference,"
            + " d.canonical_benefit_observation_id as disbursement_observation_id, d.raw_record_id as disbursement_raw"
            + " from benefit_state s left join lateral (select x.*, o.raw_record_id from canonical_benefit_disbursement x"
            + " join canonical_benefit_observation o"
            + " on o.canonical_benefit_observation_id = x.canonical_benefit_observation_id"
            + " where x.benefit_id = s.benefit_id order by x.disbursed_on desc, x.created_at desc limit 1) d on true";

    private final Db db;
    private final BatchReadService batches;
    private final SchemeCatalogue catalogue;
    private final GoldenVisibility goldens;

    public BenefitReadService(Db db, BatchReadService batches, SchemeCatalogue catalogue, GoldenVisibility goldens) {
        this.db = db;
        this.batches = batches;
        this.catalogue = catalogue;
        this.goldens = goldens;
    }

    /**
     * @param state            pack ID or state code of the benefit's source
     * @param schemeId         platform scheme ID
     * @param department       department code of the scheme
     * @param status           ACTIVE, SUSPENDED, CLOSED, ENDED or ALL (default)
     * @param goldenResidentId benefits this Golden Resident currently holds (directly or through a household)
     */
    public Page<BenefitSummary> list(String state, String schemeId, String department, String status,
                                     UUID goldenResidentId, Integer page, Integer size, String sort) {
        var query = PageQuery.of(page, size, sort, SORT, "sourceBenefitId,asc", "s.benefit_id");
        String s = status == null || status.isBlank() ? "ALL" : status.strip().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(s)) {
            throw new IllegalArgumentException("status must be ACTIVE, SUSPENDED, CLOSED, ENDED or ALL");
        }
        String statusFilter = s.equals("ALL") ? null : s;
        String code = batches.stateCode(state);
        String scheme = blank(schemeId) ? null : schemeId.strip();
        String schemes = null;
        if (!blank(department)) {
            String d = department.strip();
            if (catalogue.department(d).isEmpty()) {
                throw new IllegalArgumentException("Unknown department: " + d);
            }
            schemes = String.join(",", catalogue.schemes().stream()
                    .filter(x -> x.department().equals(d))
                    .map(Scheme::schemeId)
                    .toList());
        }
        String where = " where (cast(? as text) is null or s.status = cast(? as text))"
                + " and (cast(? as text) is null or s.state_code = cast(? as text))"
                + " and (cast(? as text) is null or s.scheme_id = cast(? as text))"
                + " and (cast(? as text) is null or s.scheme_id = any(string_to_array(cast(? as text), ',')))"
                + " and (cast(? as uuid) is null or exists (select 1 from benefit_holding_current h"
                + " where h.benefit_id = s.benefit_id and h.golden_resident_id = cast(? as uuid)))"
                + Db.inScope("s.state_code");
        Object[] parameters = Db.withScope(statusFilter, statusFilter, code, code, scheme, scheme, schemes, schemes,
            goldenResidentId, goldenResidentId);
        long total = db.count("select count(*) from benefit_state s" + where, parameters);
        var items = db.list(SUMMARY_COLUMNS + where + query.sql(), this::summary, parameters);
        return Page.of(items, query, total);
    }

    public BenefitDetail detail(UUID benefitId) {
        var benefit = db.one(SUMMARY_COLUMNS + " where s.benefit_id = ?", this::summary, benefitId)
                .orElseThrow(() -> new ReadNotFoundException("Benefit does not exist: " + benefitId));
        Db.requireInScope(benefit.stateCode(), "Benefit does not exist: " + benefitId);
        var beneficiaries = db.list("select c.resident_record_key, m.golden_resident_id, g.status,"
                        + " (select v.golden_payload->>'name' from golden_resident_version v"
                        + " where v.golden_resident_id = m.golden_resident_id order by v.version_number desc limit 1)"
                        + " as name, c.canonical_benefit_observation_id, c.canonical_resident_id, c.raw_record_id,"
                        + " c.batch_id from canonical_benefit_current c"
                        + " left join golden_resident_membership m on m.source_record_key = c.resident_record_key"
                        + " and m.effective_to is null"
                        + " left join golden_resident g on g.golden_resident_id = m.golden_resident_id"
                        + " where c.benefit_id = ? order by c.resident_record_key",
                r -> new BenefitBeneficiary(r.getString("resident_record_key"), Db.uuid(r, "golden_resident_id"),
                        r.getString("name"), r.getString("status"), Db.uuid(r, "canonical_benefit_observation_id"),
                        Db.uuid(r, "canonical_resident_id"), Db.uuid(r, "raw_record_id"), Db.uuid(r, "batch_id")),
                benefitId);
        BenefitHousehold household = db.one("select f.golden_family_id, f.family_record_key, f.source_family_id,"
                        + " f.status, (select string_agg(m.golden_resident_id::text, ',' order by m.golden_resident_id::text)"
                        + " from golden_family_membership m where m.golden_family_id = f.golden_family_id"
                        + " and m.effective_to is null) as members"
                        + " from benefit_state s join golden_family f on f.family_record_key = s.family_record_key"
                        + " where s.benefit_id = ?",
                r -> new BenefitHousehold(Db.uuid(r, "golden_family_id"), r.getString("family_record_key"),
                        r.getString("source_family_id"), r.getString("status"), uuids(r.getString("members"))),
                benefitId).orElse(null);
        var observations = db.list("select o.canonical_benefit_observation_id, o.observed_at, o.benefit_status,"
                        + " o.start_date, o.entitlement::text, o.benefit_payload::text, o.resident_record_key,"
                        + " o.canonical_resident_id, o.raw_record_id, o.batch_id, o.state_pack_version,"
                        + " o.mapping_version, o.scheme_catalogue_version, exists (select 1 from"
                        + " canonical_benefit_current c where c.canonical_benefit_observation_id"
                        + " = o.canonical_benefit_observation_id) as current"
                        + " from canonical_benefit_observation o where o.benefit_id = ?"
                        + " order by o.observed_at, o.canonical_benefit_observation_id",
                r -> new BenefitObservationView(Db.uuid(r, "canonical_benefit_observation_id"),
                        Db.time(r, "observed_at"), r.getBoolean("current"), r.getString("benefit_status"),
                        date(r, "start_date"), db.json(r.getString(5)), db.json(r.getString(6)),
                        r.getString("resident_record_key"), Db.uuid(r, "canonical_resident_id"),
                        Db.uuid(r, "raw_record_id"), Db.uuid(r, "batch_id"), r.getString("state_pack_version"),
                        r.getString("mapping_version"), r.getString("scheme_catalogue_version")),
                benefitId);
        var disbursements = db.list("select d.disbursed_on, d.amount, d.quantity, d.unit, d.disbursement_reference,"
                        + " d.canonical_benefit_observation_id, o.raw_record_id from canonical_benefit_disbursement d"
                        + " join canonical_benefit_observation o"
                        + " on o.canonical_benefit_observation_id = d.canonical_benefit_observation_id"
                        + " where d.benefit_id = ? order by d.disbursed_on desc, d.created_at desc",
                r -> disbursement(r, "canonical_benefit_observation_id", "raw_record_id"), benefitId);
        return new BenefitDetail(benefit, beneficiaries, household, observations, disbursements);
    }

    /** Resident 360 benefits; 404 for an unknown Golden ID. A retired identity holds nothing. */
    public ResidentBenefits benefitsOf(UUID goldenResidentId) {
        var identity = db.one("select g.status, (select v.golden_payload->>'name' from golden_resident_version v"
                        + " where v.golden_resident_id = g.golden_resident_id order by v.version_number desc limit 1)"
                        + " as name from golden_resident g where g.golden_resident_id = ?",
                r -> new String[] {r.getString("status"), r.getString("name")}, goldenResidentId)
                .orElseThrow(() -> new ReadNotFoundException("Golden Resident does not exist: " + goldenResidentId));
        goldens.requireVisible(goldenResidentId);
        Map<UUID, List<BenefitLineage>> lineage = new LinkedHashMap<>();
        Map<UUID, UUID> families = new LinkedHashMap<>();
        db.list("select h.benefit_id, h.linked_via, h.golden_family_id, h.resident_record_key,"
                        + " h.canonical_benefit_observation_id, o.canonical_resident_id, o.raw_record_id, o.batch_id"
                        + " from benefit_holding_current h join canonical_benefit_observation o"
                        + " on o.canonical_benefit_observation_id = h.canonical_benefit_observation_id"
                        + " where h.golden_resident_id = ? order by h.benefit_id, h.linked_via desc,"
                        + " h.resident_record_key",
                r -> {
                    UUID benefit = Db.uuid(r, "benefit_id");
                    lineage.computeIfAbsent(benefit, k -> new ArrayList<>()).add(new BenefitLineage(
                            r.getString("linked_via"), r.getString("resident_record_key"),
                            Db.uuid(r, "canonical_benefit_observation_id"), Db.uuid(r, "canonical_resident_id"),
                            Db.uuid(r, "raw_record_id"), Db.uuid(r, "batch_id")));
                    if (Db.uuid(r, "golden_family_id") != null) {
                        families.put(benefit, Db.uuid(r, "golden_family_id"));
                    }
                    return benefit;
                },
                goldenResidentId);
        List<ResidentBenefit> held = new ArrayList<>();
        if (!lineage.isEmpty()) {
            // Only the benefits from states in the user's scope (ADR-0019).
            var summaries = db.list(SUMMARY_COLUMNS + " where s.benefit_id = any(cast(? as uuid[]))"
                            + Db.inScope("s.state_code") + " order by s.scheme_id, s.state_code, s.source_benefit_id",
                    this::summary,
                    Db.withScope((Object) lineage.keySet().stream().map(UUID::toString).toArray(String[]::new)));
            for (var summary : summaries) {
                var links = lineage.get(summary.benefitId());
                held.add(new ResidentBenefit(summary,
                        links.stream().map(BenefitLineage::linkedVia).distinct().sorted().toList(),
                        families.get(summary.benefitId()), links));
            }
        }
        var departments = new TreeSet<String>();
        var states = new TreeSet<String>();
        var schemes = new TreeSet<String>();
        held.forEach(b -> {
            if (b.benefit().departmentCode() != null) {
                departments.add(b.benefit().departmentCode());
            }
            states.add(b.benefit().stateCode());
            schemes.add(b.benefit().schemeId());
        });
        boolean nameWithheld = GoldenVisibility.isWithheld(
                goldens.withheldFields(List.of(goldenResidentId)).get(goldenResidentId), "name");
        return new ResidentBenefits(goldenResidentId, identity[0], nameWithheld ? null : identity[1], held.size(),
                List.copyOf(departments), List.copyOf(states), List.copyOf(schemes), held);
    }

    private BenefitSummary summary(ResultSet r) throws SQLException {
        String schemeId = r.getString("scheme_id");
        var scheme = catalogue.scheme(schemeId);
        var department = scheme.flatMap(s -> catalogue.department(s.department()));
        DisbursementView last = r.getObject("disbursed_on") == null ? null
                : disbursement(r, "disbursement_observation_id", "disbursement_raw");
        return new BenefitSummary(Db.uuid(r, "benefit_id"), r.getString("benefit_record_key"), schemeId,
                scheme.map(Scheme::name).orElse(null), department.map(SchemeCatalogue.Department::code).orElse(null),
                department.map(SchemeCatalogue.Department::name).orElse(null),
                scheme.map(Scheme::benefitType).orElse(null), scheme.map(Scheme::benefitForm).orElse(null),
                scheme.map(Scheme::beneficiaryLevel).orElse(null), scheme.map(Scheme::group).orElse(null),
                r.getString("state_code"), r.getString("source_system"), r.getString("source_scheme_code"),
                r.getString("source_benefit_id"), r.getString("status"), date(r, "start_date"),
                db.json(r.getString("entitlement_text")), r.getString("source_family_id"),
                Db.uuid(r, "golden_family_id"), last, Db.time(r, "created_at"), Db.time(r, "observed_at"));
    }

    private static DisbursementView disbursement(ResultSet r, String observationColumn, String rawColumn)
            throws SQLException {
        return new DisbursementView(date(r, "disbursed_on"), r.getBigDecimal("amount"), r.getBigDecimal("quantity"),
                r.getString("unit"), r.getString("disbursement_reference"), Db.uuid(r, observationColumn),
                Db.uuid(r, rawColumn));
    }

    static LocalDate date(ResultSet r, String column) throws SQLException {
        return r.getObject(column, LocalDate.class);
    }

    private static List<UUID> uuids(String joined) {
        return joined == null || joined.isBlank() ? List.of()
                : java.util.Arrays.stream(joined.split(",")).map(UUID::fromString).toList();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
