package gov.uddp.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import gov.uddp.contracts.StatePackClient;
import gov.uddp.core.statepack.StatePackAssets.StateFieldsDefinition;
import gov.uddp.core.statepack.StatePackManifest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Golden state fields of every ACTIVE pack, read from the pack files statepack-service serves (the manifest's
 * {@code extensions} files): state code to the keys a pack carries to the Golden Resident. Only these files are read,
 * so the registry needs no shaping operations to load a pack. When statepack-service cannot be reached the build
 * goes on without state fields and says so in the log.
 */
public class ActivePackStateFields implements Supplier<Map<String, List<String>>> {
    private static final Logger LOG = LoggerFactory.getLogger(ActivePackStateFields.class);
    private final StatePackClient packs;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    public ActivePackStateFields(StatePackClient packs) {
        this.packs = packs;
    }

    @Override
    public Map<String, List<String>> get() {
        try {
            return fromPacks(packs.active());
        } catch (RuntimeException e) {
            LOG.warn("Golden build without state fields: active State Packs could not be read ({})", e.getMessage());
            return Map.of();
        }
    }

    Map<String, List<String>> fromPacks(List<StatePackClient.Pack> active) {
        Map<String, List<String>> out = new TreeMap<>();
        for (var pack : active) {
            try {
                String manifestText = pack.files().get("state.yaml");
                if (manifestText == null) {
                    continue;
                }
                var manifest = yaml.readValue(manifestText, StatePackManifest.class);
                for (String file : manifest.extensions() == null ? List.<String>of() : manifest.extensions()) {
                    String text = pack.files().get(file);
                    if (text == null) {
                        continue;
                    }
                    var definition = yaml.readValue(text, StateFieldsDefinition.class);
                    for (var field : definition.fields() == null ? List.<gov.uddp.core.statepack.StatePackAssets.StateField>of()
                            : definition.fields()) {
                        if (field.toGolden()) {
                            var keys = out.computeIfAbsent(manifest.stateCode(), code -> new ArrayList<>());
                            if (!keys.contains(field.key())) {
                                keys.add(field.key());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("State fields of pack {} skipped: {}", pack.ref().packId(), e.getMessage());
            }
        }
        out.replaceAll((code, keys) -> List.copyOf(keys));
        return out;
    }
}
