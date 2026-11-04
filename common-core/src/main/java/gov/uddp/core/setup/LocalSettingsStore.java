package gov.uddp.core.setup;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The connection settings saved from the Setup screen, in {@code uddp.properties} in the settings directory
 * (default {@code ~/.uddp}, outside the repository). Passwords are stored only encrypted ({@link SecretCipher}).
 * Named source connections for database-table sources are stored the same way under {@code connection.<name>.*}.
 */
public class LocalSettingsStore {
    static final String FILE_NAME = "uddp.properties";
    static final String KEY_FILE_NAME = "uddp.key";
    private static final String PLATFORM_PREFIX = "platform.";
    private static final String CONNECTION_PREFIX = "connection.";

    private final Path directory;
    private final SecretCipher cipher;

    public LocalSettingsStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        this.cipher = new SecretCipher(this.directory.resolve(KEY_FILE_NAME));
    }

    public Path file() {
        return directory.resolve(FILE_NAME);
    }

    public synchronized Optional<DatabaseConnectionSettings> platform() {
        return read(PLATFORM_PREFIX, properties());
    }

    public synchronized void savePlatform(DatabaseConnectionSettings settings) {
        Properties properties = properties();
        write(PLATFORM_PREFIX, settings, properties);
        store(properties);
    }

    /** Named source connections (database-table sources), by name. */
    public synchronized Map<String, DatabaseConnectionSettings> connections() {
        Properties properties = properties();
        Map<String, DatabaseConnectionSettings> result = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(CONNECTION_PREFIX) && name.endsWith(".host")) {
                String connection = name.substring(CONNECTION_PREFIX.length(), name.length() - ".host".length());
                read(CONNECTION_PREFIX + connection + ".", properties).ifPresent(s -> result.put(connection, s));
            }
        }
        return result;
    }

    public synchronized void saveConnection(String name, DatabaseConnectionSettings settings) {
        Properties properties = properties();
        write(CONNECTION_PREFIX + name + ".", settings, properties);
        store(properties);
    }

    public synchronized void clearConnections() {
        Properties properties = properties();
        properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith(CONNECTION_PREFIX))
                .forEach(properties::remove);
        store(properties);
    }

    /**
     * A platform secret kept encrypted in this store (never in the database); created with {@code generator} on first
     * use. Used for the national ID hashing key when no environment key is set.
     */
    public synchronized String secret(String name, java.util.function.Supplier<String> generator) {
        Properties properties = properties();
        String key = "secret." + name + ".encrypted";
        String encrypted = properties.getProperty(key);
        if (encrypted != null) {
            return cipher.decrypt(encrypted);
        }
        String value = generator.get();
        properties.setProperty(key, cipher.encrypt(value));
        store(properties);
        return value;
    }

    /** Stores a named credential (a connector password or API header value) encrypted; replaces an earlier value. */
    public synchronized void saveSecret(String name, String value) {
        Properties properties = properties();
        properties.setProperty("secret." + name + ".encrypted", cipher.encrypt(value));
        store(properties);
    }

    /** A credential saved with {@link #saveSecret}; never logged or returned by an API. */
    public synchronized Optional<String> findSecret(String name) {
        String encrypted = properties().getProperty("secret." + name + ".encrypted");
        return encrypted == null ? Optional.empty() : Optional.of(cipher.decrypt(encrypted));
    }

    private Optional<DatabaseConnectionSettings> read(String prefix, Properties properties) {
        String host = properties.getProperty(prefix + "host");
        String encrypted = properties.getProperty(prefix + "password.encrypted");
        if (host == null || encrypted == null) {
            return Optional.empty();
        }
        return Optional.of(new DatabaseConnectionSettings(host,
                Integer.parseInt(properties.getProperty(prefix + "port", "5432")),
                properties.getProperty(prefix + "database"),
                properties.getProperty(prefix + "user"),
                cipher.decrypt(encrypted)));
    }

    private void write(String prefix, DatabaseConnectionSettings settings, Properties properties) {
        properties.setProperty(prefix + "host", settings.host());
        properties.setProperty(prefix + "port", Integer.toString(settings.port()));
        properties.setProperty(prefix + "database", settings.database());
        properties.setProperty(prefix + "user", settings.user());
        properties.setProperty(prefix + "password.encrypted", cipher.encrypt(settings.password()));
    }

    private Properties properties() {
        Properties properties = new Properties();
        if (Files.isRegularFile(file())) {
            try (Reader reader = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + file(), e);
            }
        }
        return properties;
    }

    /** Writes a temporary file and moves it into place, so a crash never leaves a half-written settings file. */
    private void store(Properties properties) {
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "uddp", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "UDDP local settings (passwords are encrypted; never commit this file)");
            }
            Files.move(temporary, file(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file(), e);
        }
    }
}
