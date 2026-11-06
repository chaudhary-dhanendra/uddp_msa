package gov.uddp.core.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.RawRecordRepository;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.shaping.OperationRegistry;
import gov.uddp.shaping.ops.HeaderDetectOperation;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A workbook delivery is compared by the records its rows shape into, not by the raw cells. */
class DeliveryChangeXlsxTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<UUID, List<RawRecord>> batches = new HashMap<>();
    private gov.uddp.core.canonical.PostgresConnectionFactory connections;

    @Test
    void workbookRowsAreCountedByTheirRecordKey() throws Exception {
        var raws = mock(RawRecordRepository.class);
        when(raws.findByBatchId(any())).thenAnswer(call -> batches.get(call.<UUID>getArgument(0)));
        when(raws.findSheet(any(), anyString(), any(), any())).thenAnswer(call -> batches.get(call.<UUID>getArgument(0)));
        var packs = mock(StatePackLoader.class);
        LoadedStatePack pack = pack();
        when(packs.find(anyString())).thenReturn(Optional.of(pack));
        connections = TestDatabase.freshMigrated();
        var changes = new DeliveryChangeService(connections, raws, packs, json);
        changes.setShaping(new OperationRegistry(List.of(new HeaderDetectOperation()), List.of(), "none", "none.yaml"));

        UUID first = delivery(List.of("DC001", "Valid Member 1"), List.of("DC002", "Valid Member 2"));
        assertThat(changes.evaluate(first, null)).isEqualTo(new DeliveryChangeService.Counts(2, 0, 0, 0));

        UUID second = delivery(List.of("DC001", "Valid Member 1"), List.of("DC002", "Valid Member Two"),
                List.of("DC003", "Valid Member 3"));
        assertThat(changes.evaluate(second, null)).isEqualTo(new DeliveryChangeService.Counts(1, 1, 1, 0));
    }

    @SafeVarargs
    private UUID delivery(List<String>... dataRows) throws Exception {
        UUID batch = UUID.randomUUID();
        try (var c = connections.open(); var s = c.prepareStatement("insert into ingestion_batch(batch_id,state_code,"
                + "source_system,source_type,state_pack_version,pipeline_template,status)"
                + " values (?,'ST1','pds','file','1.0.0','generic-excel-ingest.hpl','COMPLETED')")) {
            s.setObject(1, batch);
            s.executeUpdate();
        }
        List<RawRecord> rows = new ArrayList<>();
        rows.add(row(batch, 1, List.of("MEMBER_ID", "MEMBER_NAME")));
        for (int i = 0; i < dataRows.length; i++) {
            rows.add(row(batch, i + 2, dataRows[i]));
        }
        batches.put(batch, rows);
        return batch;
    }

    private RawRecord row(UUID batch, int row, List<String> cells) throws Exception {
        String payload = json.writeValueAsString(Map.of("format", "xlsx", "file", "members.xlsx", "sheet", "Members",
                "row", row, "cells", cells));
        return new RawRecord(UUID.randomUUID(), batch, "ST1", "pds", OffsetDateTime.now(), payload, Map.of(),
                "1.0.0", "1.0", "generic-excel-ingest.hpl");
    }

    private static LoadedStatePack pack() {
        StatePackProperties properties = new StatePackProperties();
        properties.setStatePackDirectory("../../services/statepack-service/seed/state-packs");
        LoadedStatePack pack = new StatePackLoader(properties)
                .load(Path.of("../../services/statepack-service/seed/state-packs/state-1"));
        SourceDefinition base = pack.sources().getFirst();
        SourceDefinition excel = new SourceDefinition(base.sourceId(), base.sourceType(), base.hopTemplate(),
                base.connectionRef(), base.location(), "MEMBER_ID", base.sourceSchemaVersion(), List.of("MEMBER_ID"),
                null, null, "xlsx", null, List.of(Map.of("op", "header-detect@1.0")), null);
        return new LoadedStatePack(pack.manifest(), List.of(excel), pack.mappings(), pack.crosswalks(),
                pack.validations(), pack.matching(), pack.survivorship());
    }
}
