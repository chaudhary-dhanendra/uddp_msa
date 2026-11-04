package gov.uddp.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The landing area for delivered files: a caller uploads a CSV or workbook once and refers to it by its
 * {@code fileRef} ({@code sha256:<hex>/<file name>}) in ingest commands, so no other service needs to share a disk
 * with this one. Files are stored by content hash and never overwritten.
 */
@Service
public class LandingFiles {

    private static final Pattern REF = Pattern.compile("sha256:([0-9a-f]{64})/([A-Za-z0-9._ -]{1,200})");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._ -]{1,200}");

    private final Path directory;

    public LandingFiles(@Value("${uddp.ingestion.landing-directory}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    /** Stores the content under its hash and returns its fileRef. */
    public String store(String fileName, InputStream content) throws IOException {
        String name = Path.of(fileName).getFileName().toString();
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A file name may contain only letters, digits, space, '.', '_' and '-'");
        }
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "upload-", ".part");
        try {
            MessageDigest sha;
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            try (var in = new DigestInputStream(content, sha)) {
                Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            String hash = HexFormat.of().formatHex(sha.digest());
            Path target = directory.resolve(hash).resolve(name);
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
            return "sha256:" + hash + "/" + name;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The stored file of a fileRef. */
    public Path resolve(String fileRef) {
        var m = REF.matcher(fileRef == null ? "" : fileRef);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a fileRef: " + fileRef);
        }
        Path file = directory.resolve(m.group(1)).resolve(m.group(2)).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No landed file " + fileRef);
        }
        return file;
    }
}
