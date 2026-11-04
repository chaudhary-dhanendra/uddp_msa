package gov.uddp.core.department;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Repository;

/** Departments, their Source Pack assignments and the deployment's State Profile (V30). */
@Repository
public class DepartmentRepository {
    private final PostgresConnectionFactory connections;

    public DepartmentRepository(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    public record Department(String departmentCode, String name, String status) {
    }

    public record StateProfile(String stateCode, String stateName, String lgdStateCode, List<String> languages) {
    }

    public List<Department> departments() {
        String sql = "select department_code, name, status from department order by department_code";
        try (Connection c = connections.open(); PreparedStatement s = c.prepareStatement(sql);
             ResultSet r = s.executeQuery()) {
            List<Department> out = new ArrayList<>();
            while (r.next()) {
                out.add(new Department(r.getString(1), r.getString(2), r.getString(3)));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read departments", e);
        }
    }

    /** Pack ID to department code, for packs whose manifest declares no department. */
    public Map<String, String> packAssignments() {
        String sql = "select pack_id, department_code from department_source_pack";
        try (Connection c = connections.open(); PreparedStatement s = c.prepareStatement(sql);
             ResultSet r = s.executeQuery()) {
            Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            while (r.next()) {
                out.put(r.getString(1), r.getString(2));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read department assignments", e);
        }
    }

    public Optional<StateProfile> stateProfile() {
        String sql = "select state_code, state_name, lgd_state_code, languages from state_profile where profile_id = 1";
        try (Connection c = connections.open(); PreparedStatement s = c.prepareStatement(sql);
             ResultSet r = s.executeQuery()) {
            if (!r.next()) {
                return Optional.empty();
            }
            Array languages = r.getArray(4);
            return Optional.of(new StateProfile(r.getString(1), r.getString(2), r.getString(3),
                    Arrays.asList((String[]) languages.getArray())));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the State Profile", e);
        }
    }

    public StateProfile saveStateProfile(StateProfile profile) {
        String sql = """
                insert into state_profile (profile_id, state_code, state_name, lgd_state_code, languages)
                values (1, ?, ?, ?, ?)
                on conflict (profile_id) do update set state_code = excluded.state_code,
                    state_name = excluded.state_name, lgd_state_code = excluded.lgd_state_code,
                    languages = excluded.languages, updated_at = now()
                """;
        try (Connection c = connections.open(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, profile.stateCode());
            s.setString(2, profile.stateName());
            s.setString(3, profile.lgdStateCode());
            s.setArray(4, c.createArrayOf("text", profile.languages().toArray(String[]::new)));
            s.executeUpdate();
            return profile;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save the State Profile", e);
        }
    }
}
