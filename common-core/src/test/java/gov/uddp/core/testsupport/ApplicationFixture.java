package gov.uddp.core.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.CrosswalkDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.StatePackManifest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Synthetic Application deliveries: a code-built pack with Application rules over one invented CSV layout, and raw
 * records for it. Names no state, portal or real scheme code.
 */
public final class ApplicationFixture {
    public static final String HEADER = "app,scheme,who,stage,status,next,filed,acted,mode,ch";

    public static final List<MappingRule> RULES = List.of(
            rule("app", "sourceApplicationId", null, null),
            rule("scheme", "schemeCode", "scheme", null),
            rule("who", "applicantSourceRecordKey", null, null),
            rule("stage", "currentStage", null, null),
            rule("status", "currentStatus", null, null),
            rule("next", "nextStage", null, null),
            rule("filed", "submittedOn", null, "date:YYYYMMDD"),
            rule("acted", "lastActionDate", null, "date:YYYYMMDD"),
            rule("mode", "attributes.mode", null, null),
            rule("ch", "extensions.channel", null, null));

    private static final ObjectMapper JSON = new ObjectMapper();

    private ApplicationFixture() {
    }

    public static MappingRule rule(String field, String target, String crosswalk, String conversion) {
        return new MappingRule(field, target, conversion, crosswalk, null);
    }

    /** A pack whose {@code src} source has the given Application rules (none when null). */
    public static LoadedStatePack pack(String version, List<MappingRule> applicationRules) {
        Map<String, List<MappingRule>> entities = applicationRules == null
                ? Map.of("Resident", List.of())
                : Map.of("Resident", List.of(), "Application", applicationRules);
        return new LoadedStatePack(new StatePackManifest("fx", "FX", version, "1.0.0", "draft", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(new SourceDefinition("src", "file", "generic-file-ingest.hpl", "c", "x.csv", "app", "1.0")),
                List.of(new MappingDefinition("2.0.0", "src", entities)),
                Map.of("scheme", new CrosswalkDefinition("scheme", Map.of("OAP", "OLD-AGE-PENSION", "PDS", "PDS-RATION",
                        "GHOST", "GHOST-SCHEME"))),
                List.of(), List.of(), List.of());
    }

    public static RawRecord raw(UUID rawRecordId, UUID batchId, String stateCode, String header, String line) {
        String payload = JSON.createObjectNode().put("csvHeader", header).put("rawLine", line).toString();
        return new RawRecord(rawRecordId, batchId, stateCode, "src", OffsetDateTime.now(), payload,
                Map.of("sourceEntity", "src"), "1.0.0", "1.0", "generic-file-ingest.hpl");
    }
}
