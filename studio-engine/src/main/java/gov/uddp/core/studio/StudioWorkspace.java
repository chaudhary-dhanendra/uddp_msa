package gov.uddp.core.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import gov.uddp.core.config.StudioProperties;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.studio.StudioModels.Draft;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * The Studio workspace (gitignored, default {@code studio-workspace/}): uploaded source files (the landing folder)
 * in {@code uploads/<draftId>/} and portability evidence in {@code evidence/}. Drafts live in PostgreSQL.
 */
@Component
public class StudioWorkspace {
    private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9._-]{1,120}");
    private static final Pattern DRAFT_ID = Pattern.compile("[a-f0-9-]{36}");

    private final Path root;
    private final ObjectMapper json;
    private final PostgresConnectionFactory connections;

    public StudioWorkspace(StudioProperties properties, ObjectMapper json, PostgresConnectionFactory connections) {
        this.root = properties.workspace();
        this.json = json.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.connections = connections;
    }

    public Path root() {
        return root;
    }

    public synchronized void save(Draft draft) {
        try(var c=connections.open();var s=c.prepareStatement("insert into studio_draft(draft_id,pack_id,state_code,draft) values(?::uuid,?,?,?::jsonb) on conflict(draft_id) do update set pack_id=excluded.pack_id,state_code=excluded.state_code,draft=excluded.draft,updated_at=now()")){s.setString(1,draft.draftId());s.setString(2,draft.packId());s.setString(3,draft.stateCode());s.setString(4,json.writeValueAsString(draft));s.executeUpdate();}catch(Exception e){throw new IllegalStateException("Cannot save Studio draft",e);}
    }

    public synchronized Optional<Draft> find(String draftId) {
        if (!DRAFT_ID.matcher(draftId).matches()) {
            return Optional.empty();
        }
        try(var c=connections.open();var s=c.prepareStatement("select draft::text from studio_draft where draft_id=?::uuid")){s.setString(1,draftId);var r=s.executeQuery();return r.next()?Optional.of(json.readValue(r.getString(1),Draft.class)):Optional.empty();}catch(Exception e){throw new IllegalStateException("Cannot read Studio draft",e);}
    }

    public synchronized List<Draft> all() {
        try(var c=connections.open();var s=c.prepareStatement("select draft::text from studio_draft order by created_at")){var r=s.executeQuery();List<Draft> out=new ArrayList<>();while(r.next())out.add(json.readValue(r.getString(1),Draft.class));return out;}catch(Exception e){throw new IllegalStateException("Cannot read Studio drafts",e);}
    }

    /**
     * Why a file named .xlsx is not a workbook the Studio can read, or null. An .xlsx is a ZIP package; a
     * password-protected workbook is stored as an encrypted OLE2 container instead (as is an old .xls renamed).
     */
    private static String workbookProblem(Path file) throws IOException {
        byte[] head = new byte[8];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(head, 0, head.length);
        }
        if (read == 8 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF && (head[2] & 0xFF) == 0x11
                && (head[3] & 0xFF) == 0xE0) {
            return "This workbook is password-protected (or an old .xls file renamed). Remove the password and save it "
                    + "as .xlsx, or export the sheets as CSV";
        }
        if (read < 2 || head[0] != 'P' || head[1] != 'K') {
            return "The file is not a valid .xlsx workbook";
        }
        return null;
    }

    /** Stores an uploaded file as the draft's only source file; returns its absolute path. */
    public Path storeUpload(String draftId, String originalName, InputStream content, long limit) {
        String name = Path.of(originalName == null ? "source.csv" : originalName).getFileName().toString();
        if (!SAFE_FILE.matcher(name).matches()) {
            name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".xlsm")) {
            throw new IllegalArgumentException("Macro-enabled workbooks (.xlsm) are not accepted; save the workbook as "
                    + ".xlsx (without macros) or export the sheets as CSV");
        }
        if (!lower.endsWith(".csv") && !lower.endsWith(".xlsx")) {
            throw new IllegalArgumentException("Only .csv and .xlsx files can be uploaded");
        }
        Path directory = root.resolve("uploads").resolve(draftId);
        try {
            if (Files.isDirectory(directory)) {
                try (Stream<Path> old = Files.list(directory)) {
                    for (Path file : old.toList()) {
                        Files.delete(file);
                    }
                }
            }
            Files.createDirectories(directory);
            Path target = directory.resolve(name);
            long size;
            try (InputStream limited = content) {
                size = Files.copy(limited, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (size > limit) {
                Files.delete(target);
                throw new IllegalArgumentException("The file is larger than the upload limit of " + limit
                        + " bytes. Copy large deliveries into the source's landing folder instead");
            }
            if (size == 0) {
                Files.delete(target);
                throw new IllegalArgumentException("The file is empty");
            }
            if (lower.endsWith(".xlsx")) {
                String problem = workbookProblem(target);
                if (problem != null) {
                    Files.delete(target);
                    throw new IllegalArgumentException(problem);
                }
            }
            return target.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store the uploaded file", e);
        }
    }

    public Path evidenceFile() {
        return root.resolve("evidence").resolve("platform-fingerprints.json");
    }

}
