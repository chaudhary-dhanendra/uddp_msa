package gov.uddp.core.security;

import gov.uddp.core.department.SourcePackService;
import gov.uddp.core.department.SourcePackService.SourcePack;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns a user's {@link DepartmentScope} into the record-level {@link StateScope}: the source codes (the
 * {@code state_code} of canonical records) of every Source Pack an in-scope department owns. {@code ALL} sees every
 * source, including packs no department owns; nothing department-specific is coded here.
 */
@Component
public class DepartmentScopeResolver {
    private final SourcePackService sourcePacks;

    public DepartmentScopeResolver(SourcePackService sourcePacks) {
        this.sourcePacks = sourcePacks;
    }

    public StateScope sources(DepartmentScope departments) {
        if (departments.all()) {
            return StateScope.everyState();
        }
        if (departments.departments().isEmpty()) {
            return StateScope.none();
        }
        List<String> codes = new ArrayList<>();
        for (SourcePack pack : sourcePacks.all()) {
            if (pack.departmentCode() != null && departments.allows(pack.departmentCode())) {
                codes.add(pack.sourceCode());
            }
        }
        return codes.isEmpty() ? StateScope.none() : StateScope.of(codes);
    }

    /** The principal with its record-level scope resolved from its departments. */
    public UddpPrincipal resolve(UddpPrincipal principal) {
        return principal.withScope(sources(principal.departmentScope()));
    }
}
