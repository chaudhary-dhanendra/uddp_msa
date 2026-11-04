package gov.uddp.shaping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.shaping.api.RowDroppedException;
import gov.uddp.shaping.ops.CarryDownOperation;
import gov.uddp.shaping.ops.HeaderDetectOperation;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Excel rows are shaped with their whole sheet: the header row is consumed and blank cells are carried down. */
class ShapingEngineXlsxTest {
    private final ObjectMapper json = new ObjectMapper();
    private final UUID batch = UUID.randomUUID();
    private final List<RawRecord> rows = new ArrayList<>();

    private RawRecord add(int row, String... cells) throws Exception {
        return add("S1", row, cells);
    }

    private RawRecord add(String sheet, int row, String... cells) throws Exception {
        String payload = json.writeValueAsString(Map.of("format", "xlsx", "file", "book.xlsx", "sheet", sheet,
                "row", row, "cells", List.of(cells)));
        RawRecord raw = new RawRecord(UUID.randomUUID(), batch, "ST1", "pds", OffsetDateTime.now(), payload, Map.of(),
                "1.0.0", "1.0", "generic-excel-ingest.hpl");
        rows.add(raw);
        return raw;
    }

    private ShapingEngine engine() {
        OperationRegistry registry = new OperationRegistry(
                List.of(new HeaderDetectOperation(), new CarryDownOperation()), List.of(), "none", "none.yaml");
        ShapingEngine engine = new ShapingEngine(new RawCsvRecordParser(json), registry);
        engine.setBatchRows(id -> rows);
        return engine;
    }

    private LoadedStatePack pack() {
        StatePackProperties properties = new StatePackProperties();
        properties.setStatePackDirectory("../state-packs");
        LoadedStatePack pack = new StatePackLoader(properties).load(Path.of("../state-packs/state-1"));
        SourceDefinition base = pack.sources().getFirst();
        SourceDefinition excel = new SourceDefinition(base.sourceId(), base.sourceType(), base.hopTemplate(),
                base.connectionRef(), base.location(), base.recordIdField(), base.sourceSchemaVersion(),
                base.recordKeyFields(), null, null, "xlsx", null,
                List.of(Map.of("op", "header-detect@1.0"), Map.of("op", "carry-down@1.0", "columns", List.of("CARD"))),
                null);
        return new LoadedStatePack(pack.manifest(), List.of(excel), pack.mappings(), pack.crosswalks(),
                pack.validations(), pack.matching(), pack.survivorship());
    }

    @Test
    void headerRowIsConsumedAndDataRowsAreNamedByIt() throws Exception {
        RawRecord header = add(1, "CARD", "NAME", "");
        RawRecord first = add(2, "RC1", "Tsering", "");
        RawRecord second = add(3, "", "Dolkar", "");
        ShapingEngine engine = engine();
        LoadedStatePack pack = pack();

        assertThatThrownBy(() -> engine.shape(header, pack, "pds")).isInstanceOf(RowDroppedException.class);
        assertThat(engine.shape(first, pack, "pds").fields()).containsEntry("CARD", "RC1").containsEntry("NAME", "Tsering")
                .doesNotContainKey("");
        assertThat(engine.shape(second, pack, "pds").fields()).containsEntry("CARD", "RC1").containsEntry("NAME", "Dolkar");
        assertThat(engine.shape(second, pack, "pds").provenance().get("NAME").sheet()).isEqualTo("S1");
        assertThat(engine.shape(second, pack, "pds").provenance().get("NAME").row()).isEqualTo(3);
    }

    @Test
    void eachSheetIsReadAndShapedOnItsOwn() throws Exception {
        add("S1", 1, "CARD", "NAME", "");
        RawRecord s1 = add("S1", 2, "RC1", "Thoiba", "");
        add("S2", 1, "CARD", "NAME", "");
        RawRecord s2 = add("S2", 2, "", "Ibemhal", "");
        List<String> requested = new ArrayList<>();
        ShapingEngine engine = engine();
        engine.setBatchRows(new gov.uddp.shaping.api.BatchRows() {
            @Override
            public List<RawRecord> records(UUID id) {
                throw new AssertionError("the whole delivery must not be read");
            }

            @Override
            public List<RawRecord> sheet(UUID id, String source, String file, String sheet) {
                requested.add(sheet);
                return rows.stream().filter(r -> r.rawPayload().contains("\"sheet\":\"" + sheet + "\"")).toList();
            }
        });
        LoadedStatePack pack = pack();

        assertThat(engine.shape(s1, pack, "pds").fields()).containsEntry("CARD", "RC1").containsEntry("NAME", "Thoiba");
        assertThat(engine.shape(s2, pack, "pds").fields()).containsEntry("NAME", "Ibemhal")
                .as("carry-down does not cross into the next sheet").containsEntry("CARD", "");
        engine.shape(s2, pack, "pds");
        assertThat(requested).as("each sheet is read once, then kept while it is in use").containsExactly("S1", "S2");
    }

    @Test
    void shapingStepsChangeTheDigest() {
        assertThat(engine().digest(pack(), "pds")).isNotBlank();
    }
}
