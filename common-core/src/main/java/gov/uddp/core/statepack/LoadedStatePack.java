package gov.uddp.core.statepack;

import java.util.List;
import java.util.Map;
import gov.uddp.core.statepack.StatePackAssets.*;

public record LoadedStatePack(StatePackManifest manifest, List<SourceDefinition> sources,
                              List<MappingDefinition> mappings, Map<String, CrosswalkDefinition> crosswalks,
                              List<ValidationDefinition> validations, List<MatchingDefinition> matching,
                              List<SurvivorshipDefinition> survivorship) { }
