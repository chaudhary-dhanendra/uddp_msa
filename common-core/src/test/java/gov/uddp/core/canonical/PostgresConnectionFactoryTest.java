package gov.uddp.core.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostgresConnectionFactoryTest {
    private static Map<String, String> settings() {
        var values = new HashMap<String, String>();
        values.put("UDDP_POSTGRES_HOST", "db.example");
        values.put("UDDP_POSTGRES_PORT", "6543");
        values.put("UDDP_POSTGRES_DB", "registry");
        values.put("UDDP_POSTGRES_USER", "someone");
        values.put("UDDP_POSTGRES_PASSWORD", "not-a-real-secret");
        return values;
    }

    @Test
    void buildsUrlFromHostPortAndDatabase() {
        assertEquals("jdbc:postgresql://db.example:6543/registry", new PostgresConnectionFactory(settings()::get).url());
    }

    @Test
    void explicitUrlTakesPrecedence() {
        var values = settings();
        values.put("UDDP_POSTGRES_URL", "jdbc:postgresql://other:5432/x");
        assertEquals("jdbc:postgresql://other:5432/x", new PostgresConnectionFactory(values::get).url());
    }

    @Test
    void missingUserFailsWithItsNameInsteadOfFallingBack() {
        var values = settings();
        values.remove("UDDP_POSTGRES_USER");
        var factory = new PostgresConnectionFactory(values::get);
        assertEquals(List.of("UDDP_POSTGRES_USER"), factory.missingVariables());
        var error = assertThrows(IllegalStateException.class, factory::open);
        assertTrue(error.getMessage().contains("UDDP_POSTGRES_USER"));
    }

    @Test
    void missingHostFailsWhenNoUrlIsGiven() {
        var values = settings();
        values.remove("UDDP_POSTGRES_HOST");
        var error = assertThrows(IllegalStateException.class, () -> new PostgresConnectionFactory(values::get).url());
        assertTrue(error.getMessage().contains("UDDP_POSTGRES_HOST"));
    }

    @Test
    void explicitSettingsIgnoreTheEnvironment() {
        var factory = PostgresConnectionFactory.forSettings("jdbc:postgresql://container:1234/test", "u", "p");
        assertEquals("jdbc:postgresql://container:1234/test", factory.url());
        assertTrue(factory.isConfigured());
    }
}
