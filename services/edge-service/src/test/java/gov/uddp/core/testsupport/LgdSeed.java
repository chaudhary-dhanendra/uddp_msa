package gov.uddp.core.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.geography.FileLgdSourceClient;
import gov.uddp.core.geography.LgdImportService;
import java.nio.file.Path;

/** The platform's demo LGD master (platform/reference-data/lgd), which the sample packs' district codes resolve to. */
public final class LgdSeed {

    public static final Path DEMO = SampleData.REPOSITORY_ROOT.resolve("platform/reference-data/lgd/lgd-demo-snapshot.csv");

    private LgdSeed() {
    }

    public static void load(PostgresConnectionFactory connections) {
        var result = new LgdImportService(connections, new ObjectMapper())
                .importSnapshot(FileLgdSourceClient.read(DEMO), "test-seed");
        if (!"ACTIVE".equals(result.status()) && !"UNCHANGED".equals(result.status())) {
            throw new IllegalStateException("The demo LGD seed did not load: " + result.errors());
        }
    }
}
