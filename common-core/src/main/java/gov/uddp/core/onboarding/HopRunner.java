package gov.uddp.core.onboarding;

import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import java.nio.file.Path;
import java.util.UUID;

/** Generic Hop boundary: no state names or schemas are encoded here. */
public interface HopRunner {
    Result ingest(SourceDefinition source, String stateCode, String packVersion, UUID batchId);
    record Result(int exitCode, String output) { public boolean succeeded() { return exitCode == 0; } }
}
