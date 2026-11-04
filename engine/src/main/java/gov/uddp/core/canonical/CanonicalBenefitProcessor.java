package gov.uddp.core.canonical;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.mapping.BenefitMappingEngine;
import gov.uddp.core.mapping.BenefitMappingEngine.BenefitMapping;
import gov.uddp.core.mapping.BenefitMappingEngine.MappedBenefit;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.statepack.LoadedStatePack;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes the canonical Benefit observation of one raw record (M15, ADR-0011): the benefit the delivery names for the
 * resident it describes, the stable platform identity of that benefit (created on first sight) and the disbursement
 * the delivery reports. Append-only and idempotent per raw record and State Pack version. A Benefit mapping failure is
 * recorded in {@code canonical_benefit_processing_error} and never affects the Resident or its Family observation.
 */
@Service
public class CanonicalBenefitProcessor {
    private final PostgresConnectionFactory connections;
    private final BenefitMappingEngine mapper;
    private final ObjectMapper json;

    public CanonicalBenefitProcessor(PostgresConnectionFactory connections, BenefitMappingEngine mapper, ObjectMapper json) {
        this.connections = connections;
        this.mapper = mapper;
        this.json = json;
    }

    /** Outcome of observing one raw record. */
    public enum Outcome { OBSERVED, NO_BENEFIT_RULES, ALREADY_OBSERVED, FAILED }

    /** stateCode|sourceSystem|sourceEntity|schemeId|sourceBenefitId: a benefit as one source names it. */
    public static String benefitRecordKey(RawRecord raw, String schemeId, String sourceBenefitId) {
        return String.join("|", raw.stateCode(), raw.sourceSystem(), raw.sourceEntity(), schemeId, sourceBenefitId);
    }

    /**
     * @param residentRecordKey    the Resident source record key of the delivery (ADR-0003)
     * @param canonicalResidentId  the canonical Resident that represents this delivery
     */
    public Outcome observe(RawRecord raw, LoadedStatePack pack, String residentRecordKey, UUID canonicalResidentId) {
        Optional<BenefitMapping> mapped;
        try {
            mapped = mapper.map(raw, pack, raw.sourceSystem());
        } catch (RuntimeException error) {
            recordFailure(raw, pack.manifest().version(), error.getMessage());
            return Outcome.FAILED;
        }
        if (mapped.isEmpty()) {
            return Outcome.NO_BENEFIT_RULES;
        }
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                UUID benefitId = null;
                String benefitKey = null;
                var benefit = mapped.get().benefit();
                if (benefit.isPresent()) {
                    benefitKey = benefitRecordKey(raw, benefit.get().scheme().schemeId(), benefit.get().sourceBenefitId());
                    benefitId = benefitIdentity(c, raw, benefitKey, benefit.get());
                }
                UUID observation = UUID.randomUUID();
                if (!insertObservation(c, observation, raw, pack, mapped.get(), residentRecordKey, canonicalResidentId,
                        benefitId, benefitKey)) {
                    c.rollback();
                    return Outcome.ALREADY_OBSERVED;
                }
                if (benefit.isPresent() && benefit.get().disbursement() != null) {
                    insertDisbursement(c, benefitId, observation, benefit.get().disbursement());
                }
                for (String warning : mapped.get().warnings()) {
                    insertWarning(c, raw, pack, benefit.map(MappedBenefit::sourceSchemeCode).orElse(null), warning);
                }
                resolveWarnings(c, raw, pack, benefit.isPresent() && !BenefitMappingEngine.UNMAPPED.equals(
                        benefit.get().scheme().schemeId()));
                c.commit();
                return Outcome.OBSERVED;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist canonical Benefit observation", e);
        }
    }

    /**
     * Reprocesses only the raw records whose benefit code was kept UNMAPPED (open warnings), after the pack maps it.
     * Rows whose code is still unmapped stay as they are; returns how many now carry a mapped scheme.
     */
    public int reprocessUnmapped(LoadedStatePack pack, RawRecordRepository raws) {
        record Open(UUID rawRecordId, String residentKey, UUID residentId) {
        }
        java.util.List<Open> open = new java.util.ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select distinct w.raw_record_id, o.resident_record_key,"
                        + " o.canonical_resident_id from canonical_benefit_warning w join canonical_benefit_observation o"
                        + " on o.raw_record_id = w.raw_record_id and o.scheme_id = 'UNMAPPED'"
                        + " where w.resolved_at is null and w.warning_type = 'UNMAPPED_CODE'");
                var r = s.executeQuery()) {
            while (r.next()) {
                open.add(new Open(r.getObject(1, UUID.class), r.getString(2), r.getObject(3, UUID.class)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read open Benefit warnings", e);
        }
        int mappedNow = 0;
        for (Open row : open) {
            var raw = raws.findById(row.rawRecordId());
            if (raw.isPresent() && observe(raw.get(), pack, row.residentKey(), row.residentId()) == Outcome.OBSERVED
                    && isMapped(raw.get())) {
                mappedNow++;
            }
        }
        return mappedNow;
    }

    private boolean isMapped(RawRecord raw) {
        try (var c = connections.open();
                var s = c.prepareStatement("select count(*) from canonical_benefit_warning where raw_record_id = ?"
                        + " and resolved_at is null")) {
            s.setObject(1, raw.rawRecordId());
            try (var r = s.executeQuery()) {
                r.next();
                return r.getInt(1) == 0;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read Benefit warnings", e);
        }
    }

    private static void insertWarning(Connection c, RawRecord raw, LoadedStatePack pack, String code, String message)
            throws Exception {
        try (var s = c.prepareStatement("insert into canonical_benefit_warning(raw_record_id, batch_id,"
                + " state_pack_version, warning_type, source_code, message) values (?,?,?,'UNMAPPED_CODE',?,?)"
                + " on conflict do nothing")) {
            s.setObject(1, raw.rawRecordId());
            s.setObject(2, raw.batchId());
            s.setString(3, pack.manifest().version());
            s.setString(4, code);
            s.setString(5, message);
            s.executeUpdate();
        }
    }

    private static void resolveWarnings(Connection c, RawRecord raw, LoadedStatePack pack, boolean mapped) throws Exception {
        if (!mapped) {
            return;
        }
        try (var s = c.prepareStatement("update canonical_benefit_warning set resolved_at = now()"
                + " where raw_record_id = ? and resolved_at is null")) {
            s.setObject(1, raw.rawRecordId());
            s.executeUpdate();
        }
    }

    /** The benefit's platform ID: the existing one for its record key, or a new one (never reused). */
    private static UUID benefitIdentity(Connection c, RawRecord raw, String key, MappedBenefit benefit) throws Exception {
        try (var s = c.prepareStatement("insert into benefit(benefit_id, benefit_record_key, state_code, source_system,"
                + " source_entity, scheme_id, source_benefit_id) values (?,?,?,?,?,?,?)"
                + " on conflict (benefit_record_key) do nothing")) {
            s.setObject(1, UUID.randomUUID());
            s.setString(2, key);
            s.setString(3, raw.stateCode());
            s.setString(4, raw.sourceSystem());
            s.setString(5, raw.sourceEntity());
            s.setString(6, benefit.scheme().schemeId());
            s.setString(7, benefit.sourceBenefitId());
            s.executeUpdate();
        }
        try (var s = c.prepareStatement("select benefit_id from benefit where benefit_record_key = ?")) {
            s.setString(1, key);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getObject(1, UUID.class);
            }
        }
    }

    private boolean insertObservation(Connection c, UUID id, RawRecord raw, LoadedStatePack pack, BenefitMapping mapped,
                                      String residentRecordKey, UUID canonicalResidentId, UUID benefitId,
                                      String benefitKey) throws Exception {
        MappedBenefit b = mapped.benefit().orElse(null);
        Map<String, Object> entitlement = new LinkedHashMap<>();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (b != null) {
            putIfPresent(entitlement, "amount", b.amount());
            putIfPresent(entitlement, "quantity", b.quantity());
            putIfPresent(entitlement, "unit", b.unit());
            putIfPresent(entitlement, "frequency", b.frequency());
            payload.put("extensions", b.extensions());
        }
        String sql = "insert into canonical_benefit_observation(canonical_benefit_observation_id, raw_record_id,"
                + " batch_id, state_code, source_system, source_entity, state_pack_version, mapping_version,"
                + " scheme_catalogue_version, resident_record_key, canonical_resident_id, benefit_id, benefit_record_key,"
                + " source_scheme_code, scheme_id, source_benefit_id, beneficiary_level, family_record_key,"
                + " source_family_id, benefit_status, start_date, entitlement, benefit_payload)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb))"
                + " on conflict do nothing";
        try (var s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            s.setObject(2, raw.rawRecordId());
            s.setObject(3, raw.batchId());
            s.setString(4, raw.stateCode());
            s.setString(5, raw.sourceSystem());
            s.setString(6, raw.sourceEntity());
            s.setString(7, pack.manifest().version());
            s.setString(8, mapped.mappingVersion());
            s.setString(9, mapped.catalogueVersion());
            s.setString(10, residentRecordKey);
            s.setObject(11, canonicalResidentId);
            s.setObject(12, benefitId);
            s.setString(13, benefitKey);
            s.setString(14, b == null ? null : b.sourceSchemeCode());
            s.setString(15, b == null ? null : b.scheme().schemeId());
            s.setString(16, b == null ? null : b.sourceBenefitId());
            s.setString(17, b == null ? null : b.scheme().beneficiaryLevel());
            boolean household = b != null && b.sourceFamilyId() != null;
            s.setString(18, household ? CanonicalFamilyProcessor.familyRecordKey(raw, b.sourceFamilyId()) : null);
            s.setString(19, household ? b.sourceFamilyId() : null);
            s.setString(20, b == null ? null : b.status());
            s.setObject(21, b == null || b.startDate() == null ? null : Date.valueOf(b.startDate()));
            s.setString(22, json.writeValueAsString(entitlement));
            s.setString(23, json.writeValueAsString(payload));
            return s.executeUpdate() == 1;
        }
    }

    private static void insertDisbursement(Connection c, UUID benefitId, UUID observation,
                                           BenefitMappingEngine.Disbursement d) throws Exception {
        try (var s = c.prepareStatement("insert into canonical_benefit_disbursement(canonical_benefit_disbursement_id,"
                + " benefit_id, canonical_benefit_observation_id, disbursed_on, amount, quantity, unit,"
                + " disbursement_reference) values (?,?,?,?,?,?,?,?) on conflict do nothing")) {
            s.setObject(1, UUID.randomUUID());
            s.setObject(2, benefitId);
            s.setObject(3, observation);
            s.setObject(4, Date.valueOf(d.date()));
            s.setBigDecimal(5, d.amount());
            s.setBigDecimal(6, d.quantity());
            s.setString(7, d.unit());
            s.setString(8, d.reference());
            s.executeUpdate();
        }
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private void recordFailure(RawRecord raw, String packVersion, String message) {
        try (var c = connections.open();
                var s = c.prepareStatement("insert into canonical_benefit_processing_error(raw_record_id, batch_id,"
                        + " state_pack_version, error_message) values (?,?,?,?)")) {
            s.setObject(1, raw.rawRecordId());
            s.setObject(2, raw.batchId());
            s.setString(3, packVersion);
            s.setString(4, message == null ? "Benefit mapping failed" : message);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record Benefit processing error", e);
        }
    }
}
