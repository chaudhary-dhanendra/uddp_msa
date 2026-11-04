package gov.uddp.core.api;

import gov.uddp.core.statepack.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** @deprecated State Packs are Source Packs owned by departments: use {@code /api/v1/source-packs}. */
@Deprecated
@RestController
@RequestMapping("/api/state-packs")
public class StatePackController {
    private final StatePackLoader loader;
    public StatePackController(StatePackLoader loader) { this.loader = loader; }
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW') and @access.everyState()")
    @GetMapping public List<StatePackSummary> all() { return loader.all().stream().map(StatePackSummary::from).toList(); }
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW') and @access.everyState()")
    @GetMapping("/{state}") public StatePackDetails one(@PathVariable String state) { return loader.find(state).map(StatePackDetails::from).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "State Pack not found: " + state)); }
    public record StatePackSummary(String packId, String stateCode, String version, String status) { static StatePackSummary from(LoadedStatePack p) { var m=p.manifest(); return new StatePackSummary(m.packId(),m.stateCode(),m.version(),m.status()); } }
    public record StatePackDetails(String packId, String stateCode, String version, String commonModelVersion, String status, List<SourceView> sources, int mappingCount, int crosswalkCount, int validationCount, int matchingCount, int survivorshipCount) { static StatePackDetails from(LoadedStatePack p) { var m=p.manifest(); return new StatePackDetails(m.packId(),m.stateCode(),m.version(),m.commonModelVersion(),m.status(),p.sources().stream().map(s->new SourceView(s.sourceId(),s.sourceType(),s.hopTemplate(),s.sourceSchemaVersion())).toList(),p.mappings().size(),p.crosswalks().size(),p.validations().size(),p.matching().size(),p.survivorship().size()); } }
    public record SourceView(String sourceId, String sourceType, String hopTemplate, String sourceSchemaVersion) { }
}
