package gov.uddp.core.setup;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Decides where the platform database settings come from (ADR-0014):
 * <ol>
 *   <li>{@code ENVIRONMENT}: UDDP_POSTGRES_* environment variables, when they are complete (the production path);</li>
 *   <li>{@code LOCAL_STORE}: the settings saved from the Setup screen in the local settings store;</li>
 *   <li>{@code NONE}: nothing configured, the backend runs in setup mode.</li>
 * </ol>
 * The local store is read once and cached; {@link #reload()} re-reads it after the Setup screen saves new settings.
 */
public class PlatformDatabaseSettings {
    public enum Source { ENVIRONMENT, LOCAL_STORE, NONE }

    static final String URL = "UDDP_POSTGRES_URL";
    static final String HOST = "UDDP_POSTGRES_HOST";
    static final String PORT = "UDDP_POSTGRES_PORT";
    static final String DB = "UDDP_POSTGRES_DB";
    static final String USER = "UDDP_POSTGRES_USER";
    static final String PASSWORD = "UDDP_POSTGRES_PASSWORD";

    private final Function<String, String> environment;
    private final LocalSettingsStore store;
    private volatile Optional<DatabaseConnectionSettings> stored;
    private volatile String storeError;
    private volatile String unavailableReason;

    public PlatformDatabaseSettings(Function<String, String> environment, LocalSettingsStore store) {
        this.environment = environment;
        this.store = store;
        reload();
    }

    public LocalSettingsStore store() {
        return store;
    }

    public Source source() {
        if (environmentComplete()) {
            return Source.ENVIRONMENT;
        }
        return stored.isPresent() && unavailableReason == null ? Source.LOCAL_STORE : Source.NONE;
    }

    /**
     * The saved settings could not be used at startup (for example the database is down): the backend stays in setup
     * mode and the Setup screen shows the reason until settings are applied again.
     */
    public void markUnavailable(String reason) {
        unavailableReason = reason;
    }

    public String unavailableReason() {
        return unavailableReason;
    }

    /** A UDDP_POSTGRES_* setting from the active source; null when that source does not define it. */
    public String lookup(String name) {
        return switch (source()) {
            case ENVIRONMENT -> environment.apply(name);
            case LOCAL_STORE -> stored.map(settings -> fromStore(settings, name)).orElse(null);
            case NONE -> null;
        };
    }

    /** The settings saved from the Setup screen, if any (with their password: never return them from the API). */
    public Optional<DatabaseConnectionSettings> storedSettings() {
        return stored;
    }

    /** Why the local store could not be read at the last reload, or null. */
    public String storeError() {
        return storeError;
    }

    public synchronized void reload() {
        unavailableReason = null;
        try {
            stored = store.platform();
            storeError = null;
        } catch (RuntimeException e) {
            stored = Optional.empty();
            storeError = e.getMessage();
        }
    }

    public synchronized void save(DatabaseConnectionSettings settings) {
        store.savePlatform(settings);
        reload();
    }

    /** True when any UDDP_POSTGRES_* variable is set, even if incomplete (shown on the Settings screen). */
    public boolean environmentPartlySet() {
        return List.of(URL, HOST, PORT, DB, USER, PASSWORD).stream().anyMatch(name -> present(environment.apply(name)));
    }

    private boolean environmentComplete() {
        List<String> required = present(environment.apply(URL))
                ? List.of(USER, PASSWORD)
                : List.of(HOST, PORT, DB, USER, PASSWORD);
        return required.stream().allMatch(name -> present(environment.apply(name)));
    }

    private static String fromStore(DatabaseConnectionSettings settings, String name) {
        Map<String, String> values = Map.of(
                HOST, settings.host(),
                PORT, Integer.toString(settings.port()),
                DB, settings.database(),
                USER, settings.user(),
                PASSWORD, settings.password());
        return values.get(name);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
