package gov.uddp.core.identity;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.privacy.NationalIdProtector;
import gov.uddp.core.setup.LocalSettingsStore;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The {@code identifier_vault} table (V81): AES-GCM ciphertext of VAULT_REF identifiers, keyed by an opaque reference
 * (a keyed hash of the scope key, so a value always gets the same reference). The encryption key comes from
 * {@code UDDP_IDENTIFIER_VAULT_KEY} (base64) or else the encrypted local settings store; never from the database.
 */
@Component
public class PostgresIdentifierVault implements IdentifierVault {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PostgresConnectionFactory connections;
    private final NationalIdProtector hashes;
    private final byte[] key;

    public PostgresIdentifierVault(PostgresConnectionFactory connections, NationalIdProtector hashes,
                                   @Value("${UDDP_IDENTIFIER_VAULT_KEY:}") String environmentKey,
                                   LocalSettingsStore store) {
        this.connections = connections;
        this.hashes = hashes;
        String encoded = environmentKey != null && !environmentKey.isBlank() ? environmentKey.strip()
                : store.secret("identifierVaultKey", PostgresIdentifierVault::newKey);
        this.key = Base64.getDecoder().decode(encoded);
    }

    static String newKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Override
    public String store(IdentifierType type, String referenceKey, String normalised) {
        String reference = "vault:" + hashes.keyedHash("vault|" + referenceKey);
        try (var c = connections.open(); var s = c.prepareStatement("insert into identifier_vault(reference,"
                + " identifier_type, ciphertext) values (?, ?, ?) on conflict (reference) do nothing")) {
            s.setString(1, reference);
            s.setString(2, type.code());
            s.setString(3, encrypt(normalised));
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot store the identifier in the vault", e);
        }
        return reference;
    }

    private String encrypt(String plaintext) throws Exception {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length)
                .put(nonce).put(ciphertext).array());
    }
}
