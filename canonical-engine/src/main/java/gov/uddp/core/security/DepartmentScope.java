package gov.uddp.core.security;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The departments a user is assigned (R1b): every department ({@code ALL}) or a set of department codes. Read
 * services do not filter on it directly: {@link DepartmentScopeResolver} turns it into the {@link StateScope} of the
 * source codes the departments' Source Packs carry.
 */
public record DepartmentScope(boolean all, Set<String> departments) implements Serializable {
    /** The scope value that grants every department, present and future, and sources no department owns. */
    public static final String ALL = "ALL";

    private static final DepartmentScope EVERY_DEPARTMENT = new DepartmentScope(true, Set.of());
    private static final DepartmentScope NO_DEPARTMENT = new DepartmentScope(false, Set.of());

    public DepartmentScope {
        departments = all ? Set.of() : Set.copyOf(new TreeSet<>(departments));
    }

    public static DepartmentScope everyDepartment() {
        return EVERY_DEPARTMENT;
    }

    public static DepartmentScope none() {
        return NO_DEPARTMENT;
    }

    /** A scope from stored values: {@code ALL} anywhere grants every department; codes are upper-cased. */
    public static DepartmentScope of(Collection<String> values) {
        Set<String> codes = new TreeSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String code = value.strip().toUpperCase(Locale.ROOT);
            if (code.equals(ALL)) {
                return EVERY_DEPARTMENT;
            }
            codes.add(code);
        }
        return new DepartmentScope(false, codes);
    }

    public boolean allows(String departmentCode) {
        return all || (departmentCode != null && departments.contains(departmentCode.toUpperCase(Locale.ROOT)));
    }

    /** The stored form: {@code [ALL]} or the sorted codes. */
    public List<String> values() {
        return all ? List.of(ALL) : List.copyOf(departments);
    }
}
