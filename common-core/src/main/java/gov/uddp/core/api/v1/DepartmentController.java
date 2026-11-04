package gov.uddp.core.api.v1;

import gov.uddp.core.department.DepartmentRepository;
import gov.uddp.core.department.DepartmentRepository.StateProfile;
import gov.uddp.core.department.SourcePackService;
import gov.uddp.core.department.SourcePackService.DepartmentWithSources;
import gov.uddp.core.department.SourcePackService.SourcePack;
import gov.uddp.core.security.Audited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * R1a: one deployment serves one state (its State Profile); departments own Source Packs, each describing one source
 * system. {@code /api/state-packs} remains as a deprecated alias of the Source Pack listing.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Departments")
public class DepartmentController {
    private final SourcePackService sourcePacks;
    private final DepartmentRepository departments;

    public DepartmentController(SourcePackService sourcePacks, DepartmentRepository departments) {
        this.sourcePacks = sourcePacks;
        this.departments = departments;
    }

    @Operation(operationId = "listDepartments", summary = "Departments with the Source Packs they own")
    @GetMapping("/departments")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW') and @access.everyState()")
    public List<DepartmentWithSources> listDepartments() {
        return sourcePacks.departments();
    }

    @Operation(operationId = "getDepartment", summary = "One department with its Source Packs")
    @GetMapping("/departments/{departmentCode}")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW') and @access.everyState()")
    public DepartmentWithSources getDepartment(@PathVariable String departmentCode) {
        return sourcePacks.department(departmentCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Department not found: " + departmentCode));
    }

    @Operation(operationId = "listSourcePacks", summary = "Every Source Pack with its department")
    @GetMapping("/source-packs")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW') and @access.everyState()")
    public List<SourcePack> listSourcePacks() {
        return sourcePacks.all();
    }

    @Operation(operationId = "getSourcePack", summary = "One Source Pack by pack ID or source code")
    @GetMapping("/source-packs/{packId}")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW') and @access.everyState()")
    public SourcePack getSourcePack(@PathVariable String packId) {
        return sourcePacks.find(packId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Source Pack not found: " + packId));
    }

    @Operation(operationId = "getStateProfile", summary = "The state this deployment serves (204 until set)")
    @GetMapping("/state-profile")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StateProfile> getStateProfile() {
        return departments.stateProfile()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(operationId = "setStateProfile", summary = "Sets the state this deployment serves")
    @PutMapping("/state-profile")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "STATE_PROFILE_UPDATE", resource = "STATE_PROFILE")
    public StateProfile setStateProfile(@RequestBody StateProfile profile) {
        if (profile.stateCode() == null || profile.stateCode().isBlank()
                || profile.stateName() == null || profile.stateName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stateCode and stateName are required");
        }
        List<String> languages = profile.languages() == null ? List.of() : profile.languages();
        return departments.saveStateProfile(new StateProfile(profile.stateCode().trim(), profile.stateName().trim(),
                profile.lgdStateCode(), languages));
    }
}
