package gov.uddp.core.testsupport;

import static gov.uddp.core.testsupport.ApplicationFixture.rule;

import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.CrosswalkDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.StatePackManifest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Synthetic Asset deliveries: a code-built pack with Asset rules over one invented CSV layout. Names no state. */
public final class AssetFixture {
    public static final String HEADER = "asset,kind,what,qty,unit,worth,ben,holder,color,ch";

    public static final List<MappingRule> RULES = List.of(
            rule("asset", "sourceAssetId", null, null),
            rule("kind", "assetType", "kind", null),
            rule("what", "description", null, null),
            rule("qty", "quantity", null, null),
            rule("unit", "unit", null, null),
            rule("worth", "value", null, null),
            rule("ben", "linkedSourceBenefitId", null, null),
            rule("holder", "holderSourceRecordKey", null, null),
            rule("color", "attributes.color", null, null),
            rule("ch", "extensions.channel", null, null));

    private AssetFixture() {
    }

    public static LoadedStatePack pack(String version, List<MappingRule> assetRules) {
        Map<String, List<MappingRule>> entities = assetRules == null
                ? Map.of("Resident", List.of())
                : Map.of("Resident", List.of(), "Asset", assetRules);
        return new LoadedStatePack(new StatePackManifest("fx", "FX", version, "1.0.0", "draft", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(new SourceDefinition("src", "file", "generic-file-ingest.hpl", "c", "x.csv", "asset", "1.0")),
                List.of(new MappingDefinition("2.0.0", "src", entities)),
                Map.of("kind", new CrosswalkDefinition("kind", Map.of("CAR", "VEHICLE", "COW", "LIVESTOCK",
                        "PV", "SOLAR_INSTALLATION", "PLOT", "LAND", "ODD", "SPACESHIP"))),
                List.of(), List.of(), List.of());
    }

    public static RawRecord raw(UUID rawRecordId, UUID batchId, String stateCode, String header, String line) {
        return ApplicationFixture.raw(rawRecordId, batchId, stateCode, header, line);
    }
}
