package gov.uddp.core.statepack;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import gov.uddp.core.statepack.StatePackAssets.*;

/** {@code stateFields} are the pack's declared Resident state fields (empty when the pack declares none). */
public record LoadedStatePack(StatePackManifest manifest, List<SourceDefinition> sources,
                              List<MappingDefinition> mappings, Map<String, CrosswalkDefinition> crosswalks,
                              List<ValidationDefinition> validations, List<MatchingDefinition> matching,
                              List<SurvivorshipDefinition> survivorship, List<StateField> stateFields) {

    public LoadedStatePack {
        stateFields = stateFields == null ? List.of() : List.copyOf(stateFields);
    }

    /** A pack without state fields. */
    public LoadedStatePack(StatePackManifest manifest, List<SourceDefinition> sources,
                           List<MappingDefinition> mappings, Map<String, CrosswalkDefinition> crosswalks,
                           List<ValidationDefinition> validations, List<MatchingDefinition> matching,
                           List<SurvivorshipDefinition> survivorship) {
        this(manifest, sources, mappings, crosswalks, validations, matching, survivorship, List.of());
    }

    /** The declared state field with this key, if any. */
    public Optional<StateField> stateField(String key) {
        return stateFields.stream().filter(field -> field.key().equals(key)).findFirst();
    }
}
