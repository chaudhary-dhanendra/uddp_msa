package gov.uddp.core.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.mapping.ResidentMappingEngine;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.CrosswalkDefinition;
import gov.uddp.core.statepack.StatePackAssets.ValidationDefinition;
import gov.uddp.core.statepack.StatePackAssets.ValidationRule;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.validation.ResidentValidator;
import java.util.HashMap;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalResidentProcessingServiceTest {
    private static final String ST1_HEADER = "beneficiary_nm,dob,sex_cd,dist_cd,ration_no,mobile";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void batchProcessingPersistsLineageIsIdempotentAndAllowsNewPackVersion() throws Exception {
        UUID batch = UUID.randomUUID();
        RawRecord raw = raw(UUID.randomUUID(), batch, "ST3", "beneficiaries",
                "person_name,dob,gender,district,family_id,mobile,blood_group,disability_pct",
                "Ram Singh,1980-03-12,M,Patna,F9001,9876543210,B+,20");
        FakeRawRepository raws = new FakeRawRepository(raw);
        FakeCanonicalRepository canonicals = new FakeCanonicalRepository();
        CanonicalResidentProcessingService service = service(raws, canonicals);

        assertEquals(new CanonicalProcessingSummary(batch, 1, 1, 0, 0), service.processBatch(batch));
        CanonicalResidentRecord stored = canonicals.records.values().iterator().next();
        assertEquals(raw.rawRecordId(), stored.rawRecordId());
        assertEquals(batch, stored.batchId());
        assertEquals("ST3", stored.stateCode());
        assertEquals("B+", json.readTree(stored.canonicalPayload()).at("/extensions/blood_group").asText());
        assertEquals("20", json.readTree(stored.canonicalPayload()).at("/extensions/disability_pct").asText());

        assertEquals(new CanonicalProcessingSummary(batch, 1, 0, 1, 0), service.processBatch(batch));
        assertEquals(1, canonicals.records.size());
        assertTrue(canonicals.insert(new CanonicalResidentRecord(UUID.randomUUID(), raw.rawRecordId(), batch, "ST3",
                "beneficiaries", "beneficiaries", "2.0.0", stored.canonicalPayload(), OffsetDateTime.now(), OffsetDateTime.now())));
        assertEquals(2, canonicals.records.size());
    }

    @Test
    void oneBadRawRecordIsRecordedWhileOtherRecordsSucceed() {
        UUID batch = UUID.randomUUID();
        RawRecord good = raw(UUID.randomUUID(), batch, "ST1", "pds", ST1_HEADER, "Ram Singh,12-03-1980,M,101,R1,9876543210");
        RawRecord bad = raw(UUID.randomUUID(), batch, "ST1", "pds", ST1_HEADER, ",12-03-1980,M,101,R2,9876543211");
        FakeCanonicalRepository canonicals = new FakeCanonicalRepository();

        assertEquals(new CanonicalProcessingSummary(batch, 2, 1, 0, 1),
                service(new FakeRawRepository(good, bad), canonicals).processBatch(batch));
        assertEquals(1, canonicals.records.size());
        assertEquals(1, canonicals.errors.size());
        assertEquals(bad.rawRecordId(), canonicals.errors.getFirst().rawRecordId());
    }

    @Test
    void blankNameIsRejectedWithARecordedErrorAndNoCanonicalRow() {
        UUID batch = UUID.randomUUID();
        RawRecord blankName = raw(UUID.randomUUID(), batch, "ST1", "pds", ST1_HEADER, "   ,12-03-1980,M,101,R3,9876543212");
        FakeCanonicalRepository canonicals = new FakeCanonicalRepository();

        assertEquals(new CanonicalProcessingSummary(batch, 1, 0, 0, 1),
                service(new FakeRawRepository(blankName), canonicals).processBatch(batch));
        assertEquals(0, canonicals.records.size());
        assertEquals(blankName.rawRecordId(), canonicals.errors.getFirst().rawRecordId());
        assertTrue(canonicals.errors.getFirst().message().contains("beneficiary_nm"), canonicals.errors.getFirst().message());
    }

    @Test
    void errorRuleRejectsInvalidGenderAndWarningRuleKeepsTheRecord() {
        // Same Java, stricter configuration: gender OTHER is mapped but not allowed, and a blank mobile only warns.
        StatePackLoader strict = loaderReturning(strictPack(loader().find("ST1").orElseThrow()));
        UUID batch = UUID.randomUUID();
        RawRecord invalidGender = raw(UUID.randomUUID(), batch, "ST1", "pds", ST1_HEADER, "Asha Rao,01-02-1990,X,101,R4,9876543213");
        RawRecord blankMobile = raw(UUID.randomUUID(), batch, "ST1", "pds", ST1_HEADER, "Ravi Rao,01-02-1991,M,101,R5,");
        FakeCanonicalRepository canonicals = new FakeCanonicalRepository();
        var service = new CanonicalResidentProcessingService(new FakeRawRepository(invalidGender, blankMobile), canonicals,
                strict, new ResidentMappingEngine(new RawCsvRecordParser(json)), new ResidentValidator(), json);

        assertEquals(new CanonicalProcessingSummary(batch, 2, 1, 0, 1), service.processBatch(batch));
        assertEquals(1, canonicals.errors.size());
        CanonicalProcessingError error = canonicals.errors.getFirst();
        assertEquals(invalidGender.rawRecordId(), error.rawRecordId());
        assertTrue(error.message().startsWith("Validation failed: gender allowedValues (error)"), error.message());
        assertTrue(error.message().contains("'OTHER'"), error.message());

        assertEquals(1, canonicals.records.size());
        CanonicalResidentRecord kept = canonicals.records.values().iterator().next();
        assertEquals(blankMobile.rawRecordId(), kept.rawRecordId());
        assertEquals(1, canonicals.warnings.size());
        CanonicalValidationWarning warning = canonicals.warnings.getFirst();
        assertEquals(kept.canonicalResidentId(), warning.canonicalResidentId());
        assertEquals("mobile", warning.fieldPath());
        assertEquals("required", warning.rule());
    }

    private static LoadedStatePack strictPack(LoadedStatePack pack) {
        var crosswalks = new HashMap<>(pack.crosswalks());
        crosswalks.put("gender", new CrosswalkDefinition("gender", Map.of("M", "MALE", "F", "FEMALE", "X", "OTHER")));
        var rules = List.of(
                new ValidationRule("name", true, null, "error"),
                new ValidationRule("gender", null, List.of("MALE", "FEMALE"), "error"),
                new ValidationRule("mobile", true, null, "warning"));
        return new LoadedStatePack(pack.manifest(), pack.sources(), pack.mappings(), Map.copyOf(crosswalks),
                List.of(new ValidationDefinition("Resident", rules)), pack.matching(), pack.survivorship());
    }

    private static StatePackLoader loaderReturning(LoadedStatePack pack) {
        return new StatePackLoader(properties()) {
            @Override
            public Optional<LoadedStatePack> find(String state) {
                return Optional.of(pack);
            }
        };
    }

    private static StatePackProperties properties() {
        StatePackProperties properties = new StatePackProperties();
        properties.setStatePackDirectory("../../state-packs");
        properties.setFixtureStatePacksEnabled(true);
        return properties;
    }

    private static StatePackLoader loader() {
        var loader = new StatePackLoader(properties());
        loader.reload();
        return loader;
    }

    private CanonicalResidentProcessingService service(RawRecordRepository raws, CanonicalResidentRepository canonicals) {
        return new CanonicalResidentProcessingService(raws, canonicals, loader(),
                new ResidentMappingEngine(new RawCsvRecordParser(json)), new ResidentValidator(), json);
    }

    private RawRecord raw(UUID id, UUID batch, String state, String source, String header, String line) {
        try {
            String payload = json.writeValueAsString(Map.of("csvHeader", header, "rawLine", line, "sourceEntity", source));
            return new RawRecord(id, batch, state, source, OffsetDateTime.parse("2026-10-24T00:00:00Z"), payload,
                    Map.of("sourceEntity", source), "1.0.0", "1.0", "generic-file-ingest.hpl");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static final class FakeRawRepository implements RawRecordRepository {
        private final List<RawRecord> values;

        FakeRawRepository(RawRecord... values) {
            this.values = List.of(values);
        }

        public Optional<RawRecord> findById(UUID id) {
            return values.stream().filter(r -> r.rawRecordId().equals(id)).findFirst();
        }

        public List<RawRecord> findByBatchId(UUID id) {
            return values.stream().filter(r -> r.batchId().equals(id)).toList();
        }
    }

    private static final class FakeCanonicalRepository implements CanonicalResidentRepository {
        final Map<UUID, CanonicalResidentRecord> records = new LinkedHashMap<>();
        final Set<String> versions = new HashSet<>();
        final List<CanonicalProcessingError> errors = new ArrayList<>();
        final List<CanonicalValidationWarning> warnings = new ArrayList<>();

        public boolean insert(CanonicalResidentRecord r) {
            return versions.add(r.rawRecordId() + "|" + r.statePackVersion()) && records.put(r.canonicalResidentId(), r) == null;
        }

        public Optional<CanonicalResidentRecord> findById(UUID id) {
            return Optional.ofNullable(records.get(id));
        }

        public boolean exists(UUID raw, String version) {
            return versions.contains(raw + "|" + version);
        }

        public void recordFailure(CanonicalProcessingError e) {
            errors.add(e);
        }

        public void recordValidationWarning(CanonicalValidationWarning w) {
            warnings.add(w);
        }
    }
}
