package gov.uddp.core.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * R1b (V31): each user's state scope becomes a department scope. ALL stays ALL; a state code becomes the department
 * owning the pack with that source code (the sample packs through V30's assignments, a stored pack through its
 * manifest's departmentCode); a code no department owns grants nothing.
 */
class DepartmentScopeMigrationTest {
    @Test
    void migratesStateScopesToTheDepartmentsOwningTheirSources() throws Exception {
        PostgresConnectionFactory connections = TestDatabase.freshEmpty();
        DatabaseMigrator migrator = new DatabaseMigrator(connections);
        migrator.migrateTo("30");
        UUID twoStates = user(connections, "two.states", "ST1", "ST3");
        UUID everything = user(connections, "everything", "ALL", "ST2");
        UUID unknown = user(connections, "unknown", "ST9");
        UUID stored = user(connections, "stored", "st7", "ST4");
        DatabaseMigratorTest.execute(connections, "insert into state_pack_document(pack_id, version, state_code,"
                + " display_name, configuration) values ('studio-pack', '1.0.0', 'ST7', 'Studio pack',"
                + " '{\"manifest\": {\"packId\": \"studio-pack\", \"stateCode\": \"ST7\", \"departmentCode\": \"DEPT-2\"}}')");

        migrator.migrate();

        assertEquals(List.of("DEPT-1", "DEPT-3"), departments(connections, twoStates));
        assertEquals(List.of("ALL"), departments(connections, everything));
        assertEquals(List.of(), departments(connections, unknown));
        assertEquals(List.of("DEPT-2", "DEPT-4"), departments(connections, stored));
    }

    private static UUID user(PostgresConnectionFactory connections, String username, String... states)
            throws Exception {
        UUID userId = UUID.randomUUID();
        DatabaseMigratorTest.execute(connections, "insert into app_user(user_id, username, display_name, password_hash)"
                + " values ('" + userId + "', '" + username + "', '" + username + "', 'x')");
        for (String state : states) {
            DatabaseMigratorTest.execute(connections, "insert into user_state_scope(user_id, state_code)"
                    + " values ('" + userId + "', '" + state + "')");
        }
        return userId;
    }

    private static List<String> departments(PostgresConnectionFactory connections, UUID userId) throws Exception {
        try (var c = connections.open(); var s = c.prepareStatement("select department_code from user_department_scope"
                + " where user_id = ? order by department_code")) {
            s.setObject(1, userId);
            try (var r = s.executeQuery()) {
                List<String> codes = new ArrayList<>();
                while (r.next()) {
                    codes.add(r.getString(1));
                }
                return codes;
            }
        }
    }
}
