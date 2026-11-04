package gov.uddp.core.setup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts locally stored secrets with AES-256-GCM under a random key kept in a key file in the settings directory,
 * outside the repository (ADR-0014). The key file is created on first use, readable only by its owner where the file
 * system supports POSIX permissions (the per-user profile directory protects it on Windows).
 */
public final class SecretCipher {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path keyFile;

    public SecretCipher(Path keyFile) {
        this.keyFile = keyFile;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt the stored secret", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"),
                    new GCMParameterSpec(TAG_BITS, data, 0, NONCE_BYTES));
            return new String(cipher.doFinal(data, NONCE_BYTES, data.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("The stored secret cannot be decrypted with " + keyFile
                    + "; enter the connection again on the Setup screen", e);
        }
    }

    private synchronized byte[] key(boolean create) {
        try {
            if (Files.isRegularFile(keyFile)) {
                return Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).strip());
            }
            if (!create) {
                throw new IllegalStateException("The settings key file " + keyFile + " is missing");
            }
            byte[] key = new byte[KEY_BYTES];
            RANDOM.nextBytes(key);
            Files.createDirectories(keyFile.getParent());
            try {
                Files.createFile(keyFile,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException windows) {
                Files.createFile(keyFile);
            }
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key), StandardCharsets.US_ASCII);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or create the settings key file " + keyFile, e);
        }
    }
}
