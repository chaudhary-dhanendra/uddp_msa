package gov.uddp.shaping;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.ParseResult;
import gov.uddp.shaping.api.RecordShaper;
import gov.uddp.shaping.api.TidyRecord;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShapingEngineTest {
    private static final String HEADER = "beneficiary_nm,dob,ration_no";
    private static final String LINE = "Ram Singh,12-03-1980,R1001";

    private final ObjectMapper json = new ObjectMapper();
    private final RawCsvRecordParser csv = new RawCsvRecordParser(json);

    private static final FieldParser SPLIT = new FieldParser() {
        public String name() { return "name-split"; }
        public String version() { return "1"; }
        public ParseResult parse(String value, Map<String, Object> config) {
            double confidence = value.contains(" ") ? 0.9 : 0.3;
            return new ParseResult(Map.of("first", value.split(" ")[0]), confidence, List.of());
        }
    };

    private final OperationRegistry registry = new OperationRegistry(List.of(), List.of(SPLIT), "none", "none.yaml");

    private RawRecord raw(String header, String line) throws Exception {
        String payload = json.writeValueAsString(Map.of("csvHeader", header, "rawLine", line));
        return new RawRecord(UUID.randomUUID(), UUID.randomUUID(), "ST1", "pds", OffsetDateTime.now(), payload,
                Map.of(), "1.0.0", "1.0", "generic-file-ingest.hpl");
    }

    private LoadedStatePack state1() {
        StatePackProperties properties = new StatePackProperties();
        properties.setStatePackDirectory("../../services/statepack-service/seed/state-packs");
        return new StatePackLoader(properties).load(Path.of("../../services/statepack-service/seed/state-packs/state-1"));
    }

    private LoadedStatePack withParse(LoadedStatePack pack, double minConfidence) {
        SourceDefinition base = pack.sources().getFirst();
        SourceDefinition shaped = new SourceDefinition(base.sourceId(), base.sourceType(), base.hopTemplate(),
                base.connectionRef(), base.location(), base.recordIdField(), base.sourceSchemaVersion(),
                base.recordKeyFields(), null, null, "csv", null, null,
                List.of(Map.of("sourceField", "beneficiary_nm", "parser", "name-split@1",
                        "outputs", Map.of("first", "first_name"), "minConfidence", minConfidence)));
        return new LoadedStatePack(pack.manifest(), List.of(shaped), pack.mappings(), pack.crosswalks(),
                pack.validations(), pack.matching(), pack.survivorship());
    }

    @Test
    void sourceWithoutLayoutOrParseIsPassedThroughExactly() throws Exception {
        LoadedStatePack pack = state1();
        RawRecord raw = raw(HEADER, LINE);
        TidyRecord tidy = new ShapingEngine(csv, registry).shape(raw, pack, "pds");
        assertThat(tidy.fields()).isEqualTo(csv.parse(raw.rawPayload()));
        assertThat(tidy.fields().keySet()).containsExactlyElementsOf(csv.parse(raw.rawPayload()).keySet());
        assertThat(tidy.provenance().values()).allMatch(p -> ShapingEngine.PASSTHROUGH.equals(p.operation()));
    }

    @Test
    void passthroughShaperMatchesTheCsvParser() throws Exception {
        RawRecord raw = raw(HEADER, LINE);
        assertThat(RecordShaper.passthrough(csv).shape(raw, state1(), "pds").fields())
                .isEqualTo(csv.parse(raw.rawPayload()));
    }

    @Test
    void parseOutputsAddNamedFieldsWithProvenance() throws Exception {
        TidyRecord tidy = new ShapingEngine(csv, registry).shape(raw(HEADER, LINE), withParse(state1(), 0.5), "pds");
        assertThat(tidy.fields()).containsEntry("first_name", "Ram").containsEntry("beneficiary_nm", "Ram Singh");
        assertThat(tidy.provenance().get("first_name").operation()).isEqualTo("name-split");
        assertThat(tidy.provenance().get("first_name").confidence()).isEqualTo(0.9);
    }

    @Test
    void lowConfidenceKeepsTheRawValueAndWarns() throws Exception {
        var shaped = new ShapingEngine(csv, registry)
                .shapeWithWarnings(raw(HEADER, "Ram,12-03-1980,R1001"), withParse(state1(), 0.5), "pds");
        assertThat(shaped.record().fields()).containsEntry("beneficiary_nm", "Ram").doesNotContainKey("first_name");
        assertThat(shaped.warnings()).hasSize(1);
        assertThat(shaped.warnings().getFirst().field()).isEqualTo("beneficiary_nm");
    }
}
