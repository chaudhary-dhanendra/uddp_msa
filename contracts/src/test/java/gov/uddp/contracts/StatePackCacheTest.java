package gov.uddp.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pack cache keeps one version per pack, so loading the whole cache never sees a packId twice. */
class StatePackCacheTest {
    @TempDir
    Path cache;

    private static StatePackClient.Pack pack(String packId, String version, String digest) {
        return new StatePackClient.Pack(new StatePackRef(packId, version, "sha256:" + digest), "XX", "published",
                Map.of("state.yaml", "packId: " + packId + "\nversion: " + version + "\n"));
    }

    @Test
    void aNewVersionReplacesTheEarlierOnesOfTheSamePackOnly() throws Exception {
        StatePackClient.materialize(pack("la-dairy", "1.0.0", "aaaaaaaaaaaa0000"), cache);
        StatePackClient.materialize(pack("la-dairy-members", "1.0.0", "bbbbbbbbbbbb0000"), cache);
        StatePackClient.materialize(pack("la-dairy", "1.0.0", "cccccccccccc0000"), cache);   // same version, new content
        Path latest = StatePackClient.materialize(pack("la-dairy", "1.0.1", "dddddddddddd0000"), cache);

        try (var entries = Files.list(cache)) {
            assertEquals(java.util.Set.of("la-dairy-1.0.1-dddddddddddd", "la-dairy-members-1.0.0-bbbbbbbbbbbb"),
                    entries.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        assertEquals("la-dairy-1.0.1-dddddddddddd", latest.getFileName().toString());
    }
}
