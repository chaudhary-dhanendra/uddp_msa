package gov.uddp.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The content digest of a State Pack version (ARCHITECTURE.md E-F): SHA-256 over its files in path order, each as
 * {@code path NUL content NUL} with '/' separators and UTF-8 text. statepack-service computes it at publish time and
 * every consumer recomputes it before using a pack, so a pack is used only exactly as it was approved.
 */
public final class StatePackDigest {

    private StatePackDigest() {
    }

    /** {@code sha256:<64 hex>} for the files (relative path to text content). */
    public static String of(Map<String, String> files) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (var entry : new TreeMap<>(files).entrySet()) {
                sha.update(entry.getKey().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
                sha.update(entry.getValue().replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            return "sha256:" + HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
