package gov.uddp.core.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSettingsStoreTest {
    @TempDir
    Path directory;

    @Test
    void savesSettingsWithThePasswordEncryptedAndReadsThemBack() throws Exception {
        String password = "Pw-" + UUID.randomUUID();
        var store = new LocalSettingsStore(directory);
        var settings = new DatabaseConnectionSettings("localhost", 5433, "uddp_demo", "uddp", password);

        store.savePlatform(settings);

        String file = Files.readString(directory.resolve("uddp.properties"), StandardCharsets.UTF_8);
        assertFalse(file.contains(password), "the settings file holds only the encrypted password");
        assertTrue(file.contains("platform.password.encrypted="), file);
        assertTrue(Files.isRegularFile(directory.resolve("uddp.key")), "the key file sits in the settings directory");
        assertEquals(settings, new LocalSettingsStore(directory).platform().orElseThrow());
        assertFalse(settings.toString().contains(password));
    }

    @Test
    void aStoreWithoutItsKeyCannotDecrypt() throws Exception {
        var store = new LocalSettingsStore(directory);
        store.savePlatform(new DatabaseConnectionSettings("localhost", 5432, "uddp_demo", "uddp", "secret-value"));
        Files.delete(directory.resolve("uddp.key"));

        assertThrows(IllegalStateException.class, () -> new LocalSettingsStore(directory).platform());
    }

    @Test
    void environmentSettingsWinOverTheStoreAndNothingMeansSetupMode() {
        var store = new LocalSettingsStore(directory);
        var none = new PlatformDatabaseSettings(name -> null, store);
        assertEquals(PlatformDatabaseSettings.Source.NONE, none.source());
        assertNull(none.lookup("UDDP_POSTGRES_HOST"));

        none.save(new DatabaseConnectionSettings("db-host", 5432, "uddp_demo", "uddp", "stored-secret"));
        assertEquals(PlatformDatabaseSettings.Source.LOCAL_STORE, none.source());
        assertEquals("db-host", none.lookup("UDDP_POSTGRES_HOST"));
        assertEquals("stored-secret", none.lookup("UDDP_POSTGRES_PASSWORD"));

        var environment = Map.of("UDDP_POSTGRES_HOST", "env-host", "UDDP_POSTGRES_PORT", "5432",
                "UDDP_POSTGRES_DB", "uddp", "UDDP_POSTGRES_USER", "postgres", "UDDP_POSTGRES_PASSWORD", "env-secret");
        var production = new PlatformDatabaseSettings(environment::get, store);
        assertEquals(PlatformDatabaseSettings.Source.ENVIRONMENT, production.source());
        assertEquals("env-host", production.lookup("UDDP_POSTGRES_HOST"));

        production.markUnavailable("down");
        assertEquals(PlatformDatabaseSettings.Source.ENVIRONMENT, production.source(), "environment always wins");
        none.markUnavailable("down");
        assertEquals(PlatformDatabaseSettings.Source.NONE, none.source(), "unusable saved settings mean setup mode");
    }
}
