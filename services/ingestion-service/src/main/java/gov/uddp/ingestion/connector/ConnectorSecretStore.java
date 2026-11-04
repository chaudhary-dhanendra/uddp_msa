package gov.uddp.ingestion.connector;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Connector credentials owned by ingestion-service: AES-GCM encrypted in its own schema (connector_secret), with the
 * key from UDDP_INGESTION_SECRET_KEY (base64, 32 bytes) or, when that is unset, a key file created once beside the
 * service ({@code uddp.ingestion.secret-key-file}). Values are never logged, returned or sent in events.
 */
@Component
public class ConnectorSecretStore implements ConnectorSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataSource dataSource;
    private final SecretKeySpec key;

    public ConnectorSecretStore(DataSource dataSource,
                                @Value("${uddp.ingestion.secret-key:}") String configuredKey,
                                @Value("${uddp.ingestion.secret-key-file:${user.home}/.uddp-ingestion/secret.key}")
                                String keyFile) {
        this.dataSource = dataSource;
        this.key = new SecretKeySpec(key(configuredKey, Path.of(keyFile)), "AES");
    }

    @Override
    public Optional<String> get(String name) {
        try (var c = dataSource.getConnection();
                var s = c.prepareStatement("select ciphertext from connector_secret where name = ?")) {
            s.setString(1, name);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                byte[] all = Base64.getDecoder().decode(r.getString(1));
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
                return Optional.of(new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read connector credential " + name, e);
        }
    }

    @Override
    public void put(String name, String value) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(encrypted, 0, all, iv.length, encrypted.length);
            try (var c = dataSource.getConnection(); var s = c.prepareStatement("insert into connector_secret (name,"
                    + " ciphertext) values (?, ?) on conflict (name) do update set ciphertext = excluded.ciphertext,"
                    + " updated_at = now()")) {
                s.setString(1, name);
                s.setString(2, Base64.getEncoder().encodeToString(all));
                s.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot store connector credential " + name, e);
        }
    }

    private static byte[] key(String configured, Path file) {
        try {
            if (configured != null && !configured.isBlank()) {
                return Base64.getDecoder().decode(configured.strip());
            }
            if (Files.isRegularFile(file)) {
                return Base64.getDecoder().decode(Files.readString(file).strip());
            }
            byte[] key = new byte[32];
            RANDOM.nextBytes(key);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(key));
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load the connector secret key", e);
        }
    }
}
