package gov.uddp.core.database;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Starting the application against a blank database creates the whole schema. */
@SpringBootTest(properties = "uddp.database.migrate-on-startup=true")
class StartupMigrationTest {
    @TestConfiguration
    static class BlankDatabase {
        @Bean
        @Primary
        PostgresConnectionFactory blankConnections() {
            return TestDatabase.freshEmpty();
        }
    }

    @Autowired
    PostgresConnectionFactory connections;

    @Test
    void applicationStartupMigratesABlankDatabase() throws Exception {
        for (String table : DatabaseMigratorTest.TABLES) {
            assertTrue(DatabaseMigratorTest.exists(connections, table), table + " should exist after startup");
        }
    }
}
