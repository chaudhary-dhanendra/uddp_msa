package gov.uddp.core.security;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The record-level scope (ADR-0019, R1b): every source ({@code ALL}) or the source codes (the {@code state_code} of
 * canonical records) a user may see and act on. Users are assigned departments ({@link DepartmentScope});
 * {@link DepartmentScopeResolver} turns them into this scope on every request. Read services filter lists, counts and
 * reports with {@link #sqlCodes()} and treat a record outside the scope as not found.
 */
public record StateScope(boolean all, Set<String> states) implements Serializable {
    /** The scope value that grants every state, present and future. */
    public static final String ALL = "ALL";

    private static final StateScope EVERY_STATE = new StateScope(true, Set.of());
    private static final StateScope NO_STATE = new StateScope(false, Set.of());

    public StateScope {
        states = all ? Set.of() : Set.copyOf(new TreeSet<>(states));
    }

    public static StateScope everyState() {
        return EVERY_STATE;
    }

    public static StateScope none() {
        return NO_STATE;
    }

    /** A scope from stored values: {@code ALL} anywhere grants every state; codes are upper-cased. */
    public static StateScope of(Collection<String> values) {
        Set<String> codes = new TreeSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String code = value.strip().toUpperCase(Locale.ROOT);
            if (code.equals(ALL)) {
                return EVERY_STATE;
            }
            codes.add(code);
        }
        return new StateScope(false, codes);
    }

    public boolean allows(String stateCode) {
        return all || (stateCode != null && states.contains(stateCode.toUpperCase(Locale.ROOT)));
    }

    /** True when at least one of the codes is in scope (a Golden Resident with members in several states). */
    public boolean allowsAny(Collection<String> stateCodes) {
        return all || stateCodes.stream().anyMatch(this::allows);
    }

    /** True when every code is in scope. */
    public boolean allowsAll(Collection<String> stateCodes) {
        return all || stateCodes.stream().allMatch(this::allows);
    }

    /**
     * The codes for a SQL filter {@code (cast(? as text[]) is null or x.state_code = any(cast(? as text[])))}: null
     * for every state, otherwise the codes (an empty array matches nothing).
     */
    public String[] sqlCodes() {
        return all ? null : states.toArray(String[]::new);
    }

    /** The stored form: {@code [ALL]} or the sorted codes. */
    public List<String> values() {
        return all ? List.of(ALL) : List.copyOf(states);
    }
}
