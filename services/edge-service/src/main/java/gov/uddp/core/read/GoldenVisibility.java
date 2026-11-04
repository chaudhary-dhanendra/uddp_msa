package gov.uddp.core.read;

import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.security.StateScope;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Which Golden Residents a user may see (ADR-0019): one with at least one member, current or past, from a state in
 * the user's scope. Everything else answers as if the ID did not exist.
 */
@Component
public class GoldenVisibility {
    /** SQL: the Golden Resident {@code alias.golden_resident_id} has a member from a state in scope. */
    static String visible(String alias) {
        return " and (cast(? as text[]) is null or exists (select 1 from golden_resident_membership vm"
                + " join canonical_resident vc on vc.canonical_resident_id = vm.canonical_resident_id"
                + " where vm.golden_resident_id = " + alias + ".golden_resident_id"
                + " and vc.state_code = any(cast(? as text[]))))";
    }

    private final Db db;

    public GoldenVisibility(Db db) {
        this.db = db;
    }

    /** Every state any member of the Golden Resident has come from. */
    public Set<String> states(UUID goldenResidentId) {
        return new TreeSet<>(db.list("select distinct c.state_code from golden_resident_membership m"
                + " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id"
                + " where m.golden_resident_id = ?", r -> r.getString(1), goldenResidentId));
    }

    /** Throws {@link ReadNotFoundException} unless the Golden Resident exists and is visible to the user. */
    public void requireVisible(UUID goldenResidentId) {
        if (db.count("select count(*) from golden_resident where golden_resident_id = ?", goldenResidentId) == 0) {
            throw new ReadNotFoundException("Golden Resident does not exist: " + goldenResidentId);
        }
        StateScope scope = CurrentUser.scope();
        if (!scope.all() && !scope.allowsAny(states(goldenResidentId))) {
            throw new ReadNotFoundException("Golden Resident does not exist: " + goldenResidentId);
        }
    }

    public boolean visible(Collection<String> memberStates) {
        return CurrentUser.scope().allowsAny(memberStates);
    }

    /**
     * For each Golden Resident, the fields of its latest version whose surviving value came from a member in a state
     * outside the user's scope: those values are withheld (empty for a user who sees every state).
     */
    public Map<UUID, Set<String>> withheldFields(Collection<UUID> goldenResidentIds) {
        Map<UUID, Set<String>> withheld = new HashMap<>();
        StateScope scope = CurrentUser.scope();
        if (scope.all() || goldenResidentIds.isEmpty()) {
            return withheld;
        }
        String[] ids = goldenResidentIds.stream().map(UUID::toString).toArray(String[]::new);
        db.list("select v.golden_resident_id, p.field_path, c.state_code from golden_resident_version v"
                        + " join golden_resident_version_provenance p"
                        + " on p.golden_resident_version_id = v.golden_resident_version_id"
                        + " join canonical_resident c on c.canonical_resident_id = p.canonical_resident_id"
                        + " where v.golden_resident_id = any(cast(? as uuid[]))"
                        + " and v.version_number = (select max(x.version_number) from golden_resident_version x"
                        + " where x.golden_resident_id = v.golden_resident_id)",
                r -> {
                    if (!scope.allows(r.getString("state_code"))) {
                        withheld.computeIfAbsent(Db.uuid(r, "golden_resident_id"), key -> new TreeSet<>())
                                .add(r.getString("field_path"));
                    }
                    return null;
                }, (Object) ids);
        return withheld;
    }

    /** True when the field path or one of its parents is withheld ({@code address} withholds {@code address.x}). */
    public static boolean isWithheld(Set<String> withheld, String fieldPath) {
        if (withheld == null || fieldPath == null) {
            return false;
        }
        for (String path : withheld) {
            if (fieldPath.equals(path) || fieldPath.startsWith(path + ".") || path.startsWith(fieldPath + ".")) {
                return true;
            }
        }
        return false;
    }
}
