package gov.uddp.core.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.security.AuditService;
import gov.uddp.core.studio.StudioWorkspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Data retention (P1 Part 5b): per data category a number of days to keep ({@code uddp.retention.*-days}; blank
 * keeps forever). The purge drops expired payloads but keeps every ID and its metadata, so lineage still resolves:
 * raw payloads and superseded canonical versions are replaced by a purge marker, append-only audit events get a
 * tombstone that hides them, and expired uploads of published drafts are deleted (the raw records hold the data).
 * A dry run counts without changing anything. Every run is recorded and audited with its counts. Runs manually
 * (platform administrators) and daily ({@code uddp.retention.cron}).
 */
@Service
public class RetentionService {
    public static final String RAW_PAYLOADS = "RAW_PAYLOADS";
    public static final String CANONICAL_VERSIONS = "CANONICAL_VERSIONS";
    public static final String AUDIT_EVENTS = "AUDIT_EVENTS";
    public static final String UPLOADS = "UPLOADS";
    /** Payload copies of final-rejected held records; their audit row (reference, reason, actor, time) stays. */
    public static final String REJECTED_PAYLOADS = "REJECTED_PAYLOADS";
    static final String MARKER = "{\"uddp.purged\": \"retention\"}";
    private static final Logger LOG = LoggerFactory.getLogger(RetentionService.class);

    /** The configured days per category (null keeps forever). */
    public record Policy(Integer rawPayloadDays, Integer canonicalVersionDays, Integer auditEventDays,
                         Integer uploadDays) {
    }

    /** What a run purged (or would purge, for a dry run), per category. */
    public record RunResult(UUID runId, boolean dryRun, OffsetDateTime ranAt, Map<String, Long> counts,
                            Policy policy) {
    }

    private final PostgresConnectionFactory connections;
    private final AuditService audit;
    private final StudioWorkspace workspace;
    private final ObjectMapper json;
    private final Policy policy;

    public RetentionService(PostgresConnectionFactory connections, AuditService audit, StudioWorkspace workspace,
                            ObjectMapper json,
                            @Value("${uddp.retention.raw-payload-days:#{null}}") Integer rawPayloadDays,
                            @Value("${uddp.retention.canonical-version-days:#{null}}") Integer canonicalVersionDays,
                            @Value("${uddp.retention.audit-event-days:#{null}}") Integer auditEventDays,
                            @Value("${uddp.retention.upload-days:#{null}}") Integer uploadDays) {
        this.connections = connections;
        this.audit = audit;
        this.workspace = workspace;
        this.json = json;
        this.policy = new Policy(rawPayloadDays, canonicalVersionDays, auditEventDays, uploadDays);
    }

    public Policy policy() {
        return policy;
    }

    /** The daily run; does nothing while no category has a retention period or no database is configured. */
    @Scheduled(cron = "${uddp.retention.cron:0 30 2 * * *}")
    public void daily() {
        if (!connections.isConfigured() || (policy.rawPayloadDays() == null && policy.canonicalVersionDays() == null
                && policy.auditEventDays() == null && policy.uploadDays() == null)) {
            return;
        }
        purge(false, "platform");
    }

    /** Purges expired data, or with {@code dryRun} only counts it; records the run and audits it with its counts. */
    public synchronized RunResult purge(boolean dryRun, String triggeredBy) {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            try {
                insertRun(c, runId, triggeredBy, dryRun);
                counts.put(RAW_PAYLOADS, raw(c, policy.rawPayloadDays(), dryRun));
                counts.put(REJECTED_PAYLOADS, rejectedPayloads(c, policy.rawPayloadDays(), dryRun));
                counts.put(CANONICAL_VERSIONS, canonical(c, policy.canonicalVersionDays(), dryRun));
                counts.put(AUDIT_EVENTS, auditEvents(c, policy.auditEventDays(), runId, dryRun));
                counts.put(UPLOADS, uploads(c, cutoff(now, policy.uploadDays()), dryRun));
                try (var s = c.prepareStatement("update retention_run set counts = cast(? as jsonb) where run_id = ?")) {
                    s.setString(1, json.writeValueAsString(counts));
                    s.setObject(2, runId);
                    s.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("The retention purge failed", e);
        }
        audit.record(AuditService.AuditEvent.of("RETENTION_PURGE", "RETENTION", runId.toString(), null,
                AuditService.SUCCESS).by(triggeredBy).with(AuditService.details("dryRun", dryRun, "counts", counts)));
        LOG.info("Retention {} {}: {}", dryRun ? "dry run" : "purge", runId, counts);
        return new RunResult(runId, dryRun, now, counts, policy);
    }

    private static OffsetDateTime cutoff(OffsetDateTime now, Integer days) {
        return days == null || days < 0 ? null : now.minusDays(days);
    }

    /** Database rows are compared with the database clock: {@code now()} minus the configured days. */
    private static final String EXPIRED = " < now() - make_interval(days => ?)";

    private static boolean keepsForever(Integer days) {
        return days == null || days < 0;
    }

    private static void insertRun(Connection c, UUID runId, String triggeredBy, boolean dryRun) throws SQLException {
        try (var s = c.prepareStatement("insert into retention_run(run_id, triggered_by, dry_run, counts)"
                + " values (?, ?, ?, '{}'::jsonb)")) {
            s.setObject(1, runId);
            s.setString(2, triggeredBy);
            s.setBoolean(3, dryRun);
            s.executeUpdate();
        }
    }

    /** Raw payloads older than the cutoff: the payload is replaced by a marker; the row, IDs and metadata stay. */
    private static long raw(Connection c, Integer days, boolean dryRun) throws SQLException {
        if (keepsForever(days)) {
            return 0;
        }
        String where = " where ingested_at" + EXPIRED + " and payload_purged_at is null";
        if (dryRun) {
            return count(c, "select count(*) from raw_record" + where, days);
        }
        // A held record's copy of the mapped payload goes with the raw payload it came from.
        update(c, "update held_record h set original_payload = null from raw_record r"
                + " where r.raw_record_id = h.raw_record_id and h.original_payload is not null and r.ingested_at"
                + EXPIRED + " and r.payload_purged_at is null", days);
        return update(c, "update raw_record set raw_payload = '" + MARKER + "', payload_purged_at = now()" + where,
                days);
    }

    /**
     * Final-rejected held records whose raw record is past the raw-payload period lose both payload copies and the
     * payloads inside their correction attempts. The record, its reasons and its decision events are kept.
     */
    private static long rejectedPayloads(Connection c, Integer days, boolean dryRun) throws SQLException {
        if (keepsForever(days)) {
            return 0;
        }
        String where = " from held_record h join raw_record r on r.raw_record_id = h.raw_record_id"
                + " where h.status = 'REJECTED_FINAL' and h.payload_purged_at is null and r.ingested_at" + EXPIRED;
        if (dryRun) {
            return count(c, "select count(*)" + where, days);
        }
        update(c, "update held_record_event set before = null, after = null where action = 'RECTIFY_ATTEMPT'"
                + " and held_id in (select h.held_id" + where + ")", days);
        return update(c, "update held_record set current_payload = cast('" + MARKER + "' as jsonb),"
                + " original_payload = null, payload_purged_at = now() where held_id in (select h.held_id" + where + ")",
                days);
    }

    /** Superseded canonical versions older than the cutoff (current versions feed Golden data and are kept). */
    private static long canonical(Connection c, Integer days, boolean dryRun) throws SQLException {
        if (keepsForever(days)) {
            return 0;
        }
        String where = " where c.created_at" + EXPIRED + " and c.payload_purged_at is null and exists (select 1 from"
                + " canonical_resident_version s where s.supersedes_canonical_id = c.canonical_resident_id)";
        return dryRun ? count(c, "select count(*) from canonical_resident c" + where, days)
                : update(c, "update canonical_resident c set canonical_payload = cast('" + MARKER + "' as jsonb),"
                + " payload_purged_at = now()" + where, days);
    }

    /** Audit events are append-only: expired ones get a tombstone and disappear from reads. */
    private static long auditEvents(Connection c, Integer days, UUID runId, boolean dryRun)
            throws SQLException {
        if (keepsForever(days)) {
            return 0;
        }
        String expired = " from app_audit_event e where e.occurred_at" + EXPIRED + " and not exists (select 1 from"
                + " retention_tombstone t where t.category = '" + AUDIT_EVENTS + "' and t.record_id = e.event_id::text)";
        if (dryRun) {
            return count(c, "select count(*)" + expired, days);
        }
        try (var s = c.prepareStatement("insert into retention_tombstone(category, record_id, run_id)"
                + " select '" + AUDIT_EVENTS + "', e.event_id::text, ?" + expired)) {
            s.setObject(1, runId);
            s.setObject(2, days);
            return s.executeUpdate();
        }
    }

    /** Uploaded source files older than the cutoff whose draft is published: the landed raw records keep the data. */
    private long uploads(Connection c, OffsetDateTime cutoff, boolean dryRun) throws SQLException {
        Path uploads = workspace.root().resolve("uploads");
        if (cutoff == null || !Files.isDirectory(uploads)) {
            return 0;
        }
        List<String> published = new ArrayList<>();
        try (var s = c.prepareStatement("select draft_id::text from studio_draft where draft->'published' is not null"
                + " and jsonb_typeof(draft->'published') = 'object'");
                var r = s.executeQuery()) {
            while (r.next()) {
                published.add(r.getString(1));
            }
        }
        Instant limit = cutoff.toInstant();
        long count = 0;
        for (String draft : published) {
            Path directory = uploads.resolve(draft);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(limit)) {
                        count++;
                        if (!dryRun) {
                            Files.delete(file);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return count;
    }

    private static long count(Connection c, String sql, Object parameter) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            s.setObject(1, parameter);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    private static long update(Connection c, String sql, Object parameter) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            s.setObject(1, parameter);
            return s.executeUpdate();
        }
    }
}
