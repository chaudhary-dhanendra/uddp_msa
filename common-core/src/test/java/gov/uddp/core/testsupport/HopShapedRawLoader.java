package gov.uddp.core.testsupport;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Lands a State Pack file source in Raw exactly as {@code hop/pipelines/generic-file-ingest.hpl} does:
 * one {@code ingestion_batch}, then one {@code raw_record} per physical data line with
 * {@code raw_payload = {"csvHeader":"<header>","rawLine":"<line>"}} built by plain concatenation (Hop
 * does not escape) and {@code source_metadata = {"sourceEntity":"<sourceId>"}}. Generic: everything comes
 * from the pack's source definition; there is no per-state logic. The real Hop pipeline is exercised by
 * {@code scripts/reset-and-run-e2e}.
 */
public final class HopShapedRawLoader {
    private final PostgresConnectionFactory connections;
    private final Path baseDirectory;

    /** @param baseDirectory directory that source {@code location}s are relative to (the repository root) */
    public HopShapedRawLoader(PostgresConnectionFactory connections, Path baseDirectory) {
        this.connections = connections;
        this.baseDirectory = baseDirectory;
    }

    /** Lands every source of the pack; returns the batch IDs in source order. */
    public List<UUID> landAll(LoadedStatePack pack) {
        return pack.sources().stream().map(source -> land(pack, source)).toList();
    }

    public UUID land(LoadedStatePack pack, SourceDefinition source) {
        var lines = readLines(baseDirectory.resolve(source.location()));
        String header = lines.getFirst();
        var data = lines.subList(1, lines.size()).stream().filter(line -> !line.isBlank()).toList();
        UUID batch = UUID.randomUUID();
        String version = pack.manifest().version();
        try (var c = connections.open()) {
            try (var s = c.prepareStatement("insert into ingestion_batch(batch_id,state_code,source_system,source_type,"
                    + "source_schema_version,state_pack_version,pipeline_template,status) values(?,?,?,?,?,?,?,'STARTED')")) {
                s.setObject(1, batch);
                s.setString(2, pack.manifest().stateCode());
                s.setString(3, source.sourceId());
                s.setString(4, source.sourceType());
                s.setString(5, source.sourceSchemaVersion());
                s.setString(6, version);
                s.setString(7, source.hopTemplate());
                s.executeUpdate();
            }
            insertRows(c, source, pack.manifest().stateCode(), version, batch, header, data);
            try (var s = c.prepareStatement("update ingestion_batch set status='COMPLETED', completed_at=now(),"
                    + " record_count=? where batch_id=?")) {
                s.setInt(1, data.size());
                s.setObject(2, batch);
                s.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot land " + source.location(), e);
        }
        return batch;
    }

    /**
     * Lands the source's data lines into an existing {@code ingestion_batch}, as the generic Hop pipeline does when
     * the orchestrator has already created the batch; returns the number of raw records written.
     */
    public int landInto(SourceDefinition source, String stateCode, String version, UUID batch) {
        var lines = readLines(baseDirectory.resolve(source.location()));
        var data = lines.subList(1, lines.size()).stream().filter(line -> !line.isBlank()).toList();
        try (var c = connections.open()) {
            insertRows(c, source, stateCode, version, batch, lines.getFirst(), data);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot land " + source.location(), e);
        }
        return data.size();
    }

    private static void insertRows(java.sql.Connection c, SourceDefinition source, String stateCode, String version,
                                   UUID batch, String header, List<String> data) throws SQLException {
        try (var s = c.prepareStatement("insert into raw_record(batch_id,state_code,source_system,raw_payload,"
                + "source_metadata,state_pack_version,source_schema_version,pipeline_template)"
                + " values(?,?,?,?,?::jsonb,?,?,?)")) {
            for (String line : data) {
                s.setObject(1, batch);
                s.setString(2, stateCode);
                s.setString(3, source.sourceId());
                s.setString(4, "{\"csvHeader\":\"" + header + "\",\"rawLine\":\"" + line + "\"}");
                s.setString(5, "{\"sourceEntity\":\"" + source.sourceId() + "\"}");
                s.setString(6, version);
                s.setString(7, source.sourceSchemaVersion());
                s.setString(8, source.hopTemplate());
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    private static List<String> readLines(Path file) {
        try {
            var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.contains("\"") || line.contains("\\")) {
                    throw new IllegalArgumentException(file + ": Hop lands lines unescaped, so fixtures must not contain quotes or backslashes");
                }
            }
            return lines;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }
}
