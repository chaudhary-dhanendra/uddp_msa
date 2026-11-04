package gov.uddp.core.mapping;

import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.CrosswalkDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.shaping.api.RecordShaper;
import gov.uddp.shaping.api.TidyRecord;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Applies a State Pack's declarative {@code Asset} mapping rules to one tidy record: which asset the row reports, its
 * type (a platform code), quantity and unit, value and the source benefit it relates to. Nothing here knows a state,
 * a source schema or a scheme. Resident, Family, Benefit and Application mapping are unaffected.
 */
@Service
public class AssetMappingEngine {
    public static final String ENTITY = "Asset";
    public static final Set<String> ASSET_TYPES = Set.of("VEHICLE", "LIVESTOCK", "SOLAR_INSTALLATION", "LAND",
            "EQUIPMENT", "OTHER");

    private static final String EXTENSIONS = "extensions.";
    private static final String ATTRIBUTES = "attributes.";
    private static final Set<String> TARGETS = Set.of("sourceAssetId", "assetType", "description", "quantity", "unit",
            "value", "linkedSourceBenefitId", "holderSourceRecordKey");

    private final RecordShaper shaping;

    public AssetMappingEngine(RawCsvRecordParser parser) {
        this(RecordShaper.passthrough(parser));
    }

    @Autowired
    public AssetMappingEngine(RecordShaper shaping) {
        this.shaping = shaping;
    }

    public record MappedAsset(String sourceAssetId, String assetType, String description, BigDecimal quantity,
                              String unit, BigDecimal value, String linkedSourceBenefitId,
                              String holderSourceRecordKey, Map<String, Object> attributes,
                              Map<String, Object> extensions) {
    }

    /** What one delivery says about an asset; {@code asset} is empty when it names none. */
    public record AssetMapping(String mappingVersion, Optional<MappedAsset> asset) {
    }

    /** Maps the raw record's Asset rules (shaping it first); empty when the source has none. */
    public Optional<AssetMapping> map(RawRecord raw, LoadedStatePack pack, String sourceId) {
        return map(shaping.shape(raw, pack, sourceId), pack, sourceId);
    }

    /**
     * Maps the tidy record's Asset rules; empty when the source has none. A row whose asset ID is blank names no
     * asset (unless the rule is {@code required}); any other problem is an {@link IllegalArgumentException}.
     */
    public Optional<AssetMapping> map(TidyRecord tidy, LoadedStatePack pack, String sourceId) {
        Optional<MappingDefinition> mapping = pack.mappings().stream()
                .filter(m -> m.sourceId().equals(sourceId))
                .findFirst();
        if (mapping.isEmpty() || mapping.get().entities() == null || mapping.get().entities().get(ENTITY) == null) {
            return Optional.empty();
        }
        Map<String, Object> values = tidy.fields();
        Map<String, Object> mapped = new LinkedHashMap<>();
        Map<String, Object> attributes = new LinkedHashMap<>();
        Map<String, Object> extensions = new LinkedHashMap<>();
        for (MappingRule rule : mapping.get().entities().get(ENTITY)) {
            String target = rule.target();
            boolean extension = hasPrefix(target, EXTENSIONS);
            boolean attribute = hasPrefix(target, ATTRIBUTES);
            if (!extension && !attribute && !TARGETS.contains(target)) {
                throw new IllegalArgumentException("Unsupported Asset mapping target: " + target);
            }
            Object value = values.get(rule.sourceField());
            if (value == null || value.toString().isBlank()) {
                if (Boolean.TRUE.equals(rule.required())) {
                    throw new IllegalArgumentException("Required Asset source field is blank: " + rule.sourceField());
                }
                continue;
            }
            String converted = crosswalk(value.toString().strip(), rule, pack);
            if (extension) {
                extensions.put(target.substring(EXTENSIONS.length()), converted);
            } else if (attribute) {
                attributes.put(target.substring(ATTRIBUTES.length()), converted);
            } else {
                mapped.put(target, converted);
            }
        }
        String version = mapping.get().mappingVersion();
        if (!mapped.containsKey("sourceAssetId")) {
            return Optional.of(new AssetMapping(version, Optional.empty()));
        }
        String type = text(mapped, "assetType");
        if (type == null) {
            throw new IllegalArgumentException("An asset needs an assetType");
        }
        type = type.toUpperCase(Locale.ROOT);
        if (!ASSET_TYPES.contains(type)) {
            throw new IllegalArgumentException("Unknown assetType " + type + "; expected one of " + ASSET_TYPES);
        }
        String unit = text(mapped, "unit");
        var asset = new MappedAsset(text(mapped, "sourceAssetId"), type, text(mapped, "description"),
                number(mapped, "quantity"), unit == null ? null : unit.toUpperCase(Locale.ROOT),
                number(mapped, "value"), text(mapped, "linkedSourceBenefitId"), text(mapped, "holderSourceRecordKey"),
                Map.copyOf(attributes), Map.copyOf(extensions));
        return Optional.of(new AssetMapping(version, Optional.of(asset)));
    }

    private static boolean hasPrefix(String target, String prefix) {
        return target != null && target.startsWith(prefix) && target.length() > prefix.length();
    }

    private static String text(Map<String, Object> mapped, String target) {
        Object value = mapped.get(target);
        return value == null ? null : value.toString();
    }

    private static BigDecimal number(Map<String, Object> mapped, String target) {
        String text = text(mapped, target);
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text.replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(target + " is not a number: " + text, e);
        }
    }

    private static String crosswalk(String value, MappingRule rule, LoadedStatePack pack) {
        if (rule.crosswalk() == null) {
            return value;
        }
        CrosswalkDefinition crosswalk = pack.crosswalks().get(rule.crosswalk());
        if (crosswalk == null) {
            throw new IllegalArgumentException("Unknown crosswalk: " + rule.crosswalk());
        }
        String mapped = crosswalk.values().get(value);
        if (mapped == null) {
            throw new IllegalArgumentException("No crosswalk value for " + rule.crosswalk() + ": " + value);
        }
        return mapped;
    }
}
