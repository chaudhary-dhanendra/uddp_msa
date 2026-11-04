package gov.uddp.core.statepack;

import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.testsupport.TestOperationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatePackLoaderTest {
    private StatePackLoader loader() {
        StatePackProperties properties = new StatePackProperties();
        properties.setStatePackDirectory("../state-packs");
        properties.setFixtureStatePacksEnabled(true);
        var loader = new StatePackLoader(properties);
        loader.setOperations(TestOperationRegistry.builtIns());
        loader.reload();
        return loader;
    }
    @Test void sameGenericLoaderLoadsAllConfiguredStatePacks() {
        var packs = loader().loadAll(Path.of("../state-packs"));
        assertEquals(3, packs.size());
        assertEquals("generic-file-ingest.hpl", packs.get("state-1").sources().getFirst().hopTemplate());
        assertEquals("generic-file-ingest.hpl", packs.get("state-2").sources().getFirst().hopTemplate());
        assertEquals("generic-file-ingest.hpl", packs.get("state-3").sources().getFirst().hopTemplate());
        assertNotEquals(packs.get("state-1").manifest().packId(), packs.get("state-2").manifest().packId());
    }
    @Test void invalidPackFailsWithUnderstandableError() throws Exception {
        Path directory = Files.createTempDirectory("uddp-invalid-pack");
        Files.writeString(directory.resolve("state.yaml"), "packId: bad\nstateCode: BAD\nversion: 1.0.0\ncommonModelVersion: 1.0.0\nsources: [sources/bad.yaml]\n");
        StatePackLoadException error = assertThrows(StatePackLoadException.class, () -> loader().load(directory));
        assertTrue(error.getMessage().contains("Missing status"));
    }
    @Test void validationRuleWithUnknownSeverityIsRejected() throws Exception {
        Path directory = Files.createTempDirectory("uddp-bad-validation");
        Files.createDirectories(directory.resolve("validation"));
        Files.writeString(directory.resolve("state.yaml"), "packId: bad\nstateCode: BAD\nversion: 1.0.0\ncommonModelVersion: 1.0.0\nstatus: draft\nvalidation: [validation/resident.yaml]\n");
        Files.writeString(directory.resolve("validation/resident.yaml"), "entity: Resident\nrules:\n  - { field: name, required: true, severity: fatal }\n");
        StatePackLoadException error = assertThrows(StatePackLoadException.class, () -> loader().load(directory));
        assertTrue(error.getMessage().contains("severity error, warning or send_to_steward"), error.getMessage());
    }
    @Test void unknownParserIsRejectedNamingFileAndParser() throws Exception {
        Path directory = Files.createTempDirectory("uddp-bad-shaping");
        Files.createDirectories(directory.resolve("sources"));
        Files.writeString(directory.resolve("state.yaml"), String.join("\n", "packId: bad", "stateCode: BAD",
                "version: 1.0.0", "commonModelVersion: 1.0.0", "status: draft", "sources: [sources/s.yaml]"));
        Files.writeString(directory.resolve("sources/s.yaml"), String.join("\n", "sourceId: s", "sourceType: file",
                "hopTemplate: generic-file-ingest.hpl", "parse:",
                "  - { sourceField: a, parser: nope@1, outputs: { x: y } }"));
        var loader = loader();
        loader.setOperations(new gov.uddp.shaping.OperationRegistry(java.util.List.of(), java.util.List.of(), "none", "none.yaml"));
        StatePackLoadException error = assertThrows(StatePackLoadException.class, () -> loader.load(directory));
        assertTrue(error.getMessage().contains("nope@1") && error.getMessage().contains("s.yaml"), error.getMessage());
    }
}
