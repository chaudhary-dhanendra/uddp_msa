package gov.uddp.core.department;

import gov.uddp.core.department.DepartmentRepository.Department;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.StatePackLoader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Source Packs grouped by department. The loader still calls them State Packs; the department of each comes from its
 * manifest {@code departmentCode}, else from the {@code department_source_pack} table, else it is unassigned.
 */
@Service
public class SourcePackService {
    private final StatePackLoader packs;
    private final DepartmentRepository departments;

    public SourcePackService(StatePackLoader packs, DepartmentRepository departments) {
        this.packs = packs;
        this.departments = departments;
    }

    public record SourcePack(String packId, String departmentCode, String sourceCode, String version, String status,
                             List<String> sourceIds) {
    }

    public record DepartmentWithSources(String departmentCode, String name, String status,
                                        List<SourcePack> sourcePacks) {
    }

    public List<SourcePack> all() {
        Map<String, String> assignments = departments.packAssignments();
        return packs.all().stream().map(pack -> view(pack, assignments)).toList();
    }

    public Optional<SourcePack> find(String packId) {
        return packs.find(packId).map(pack -> view(pack, departments.packAssignments()));
    }

    public List<DepartmentWithSources> departments() {
        List<SourcePack> sourcePacks = all();
        return departments.departments().stream().map(department -> withSources(department, sourcePacks)).toList();
    }

    public Optional<DepartmentWithSources> department(String departmentCode) {
        return departments().stream()
                .filter(department -> department.departmentCode().equalsIgnoreCase(departmentCode))
                .findFirst();
    }

    private static DepartmentWithSources withSources(Department department, List<SourcePack> sourcePacks) {
        List<SourcePack> owned = sourcePacks.stream()
                .filter(pack -> department.departmentCode().equals(pack.departmentCode()))
                .toList();
        return new DepartmentWithSources(department.departmentCode(), department.name(), department.status(), owned);
    }

    private static SourcePack view(LoadedStatePack pack, Map<String, String> assignments) {
        var manifest = pack.manifest();
        String department = manifest.departmentCode();
        if (department == null || department.isBlank()) {
            department = assignments.get(manifest.packId());
        }
        List<String> sourceIds = pack.sources().stream().map(SourceDefinition::sourceId).toList();
        return new SourcePack(manifest.packId(), department, manifest.stateCode(), manifest.version(),
                manifest.status(), sourceIds);
    }
}
