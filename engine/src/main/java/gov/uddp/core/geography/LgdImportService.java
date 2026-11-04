package gov.uddp.core.geography;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Imports one LGD snapshot into the geography master (Track F1): the rows are staged, the whole snapshot is validated,
 * diffed against the active master and, in one transaction, persisted as new entity versions, relationships and
 * change-log rows and activated. A snapshot that fails validation or persistence leaves the active master untouched.
 * A row missing from a snapshot is not a deletion (entities are only ever inactivated by an explicit status), and
 * re-importing an identical snapshot changes nothing.
 */
@Service
public class LgdImportService {
    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final int MAX_ERRORS = 50;
    private static final int SAMPLES = 20;

    /** The outcome of one import: ACTIVE (master changed), UNCHANGED or FAILED, with the changes by type. */
    public record ImportResult(UUID snapshotId, String status, int rows, Map<String, Integer> changes,
                               List<String> errors) {
        public int changeCount() {
            return changes.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** One validation error; the line is the row's line in the uploaded file. */
    public record SnapshotIssue(int line, String message) {
        String text() {
            return "line " + line + ": " + message;
        }
    }

    /** One entity change a snapshot would make (or made): a change type as in the change log. */
    public record SnapshotChange(String changeType, String entityType, String code, String oldValue, String newValue) {
    }

    /**
     * The dry run of a snapshot: rows by type and, when the snapshot is valid, its changes against the active master
     * (counts by type and change type, plus samples of each change type). Nothing is stored.
     */
    public record SnapshotPreview(String sourceLabel, LocalDate asOf, int rows, Map<String, Integer> rowsByType,
                          List<SnapshotIssue> errors, boolean errorsTruncated,
                          Map<String, Map<String, Integer>> changesByType, Map<String, Integer> changeTotals,
                          Map<String, List<SnapshotChange>> samples) {
    }

    private record Current(String name, String status, LocalDate effectiveFrom) {
    }

    private final PostgresConnectionFactory connections;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public LgdImportService(PostgresConnectionFactory connections, ObjectMapper json) {
        this(connections, json, Clock.systemUTC());
    }

    public LgdImportService(PostgresConnectionFactory connections, ObjectMapper json, Clock clock) {
        this.connections = connections;
        this.json = json;
        this.clock = clock;
    }

    public ImportResult importSnapshot(LgdSnapshotData data, String actor) {
        UUID snapshotId = UUID.randomUUID();
        List<LgdRow> rows = data.rows().stream().map(LgdImportService::normalise).toList();
        stage(snapshotId, data, rows, actor);
        List<String> errors = validate(rows).stream().map(SnapshotIssue::text).toList();
        if (!errors.isEmpty()) {
            fail(snapshotId, errors);
            return new ImportResult(snapshotId, "FAILED", rows.size(), Map.of(), errors);
        }
        try {
            Map<String, Integer> changes = persistAndActivate(snapshotId, data.asOf(), rows);
            int total = changes.values().stream().mapToInt(Integer::intValue).sum();
            return new ImportResult(snapshotId, total == 0 ? "UNCHANGED" : ACTIVE, rows.size(), changes, List.of());
        } catch (RuntimeException e) {
            List<String> failure = List.of("Persisting the snapshot failed: " + e.getMessage());
            fail(snapshotId, failure);
            return new ImportResult(snapshotId, "FAILED", rows.size(), Map.of(), failure);
        }
    }

    /** Validates and diffs a snapshot against the active master without staging or changing anything. */
    public SnapshotPreview preview(LgdSnapshotData data) {
        List<LgdRow> rows = data.rows().stream().map(LgdImportService::normalise).toList();
        Map<String, Integer> byType = new LinkedHashMap<>();
        rows.forEach(row -> byType.merge(String.valueOf(row.entityType()), 1, Integer::sum));
        List<SnapshotIssue> errors = validate(rows);
        Map<String, Map<String, Integer>> changesByType = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        Map<String, List<SnapshotChange>> samples = new LinkedHashMap<>();
        if (errors.isEmpty()) {
            for (SnapshotChange change : diff(rows)) {
                changesByType.computeIfAbsent(change.entityType(), k -> new LinkedHashMap<>())
                        .merge(change.changeType(), 1, Integer::sum);
                totals.merge(change.changeType(), 1, Integer::sum);
                List<SnapshotChange> sample = samples.computeIfAbsent(change.changeType(), k -> new ArrayList<>());
                if (sample.size() < SAMPLES) {
                    sample.add(change);
                }
            }
        }
        return new SnapshotPreview(data.sourceLabel(), data.asOf(), rows.size(), byType, errors, errors.size() >= MAX_ERRORS,
                changesByType, totals, samples);
    }

    /** The changes {@link #persistAndActivate} would log for these rows; keep the two in step. */
    private List<SnapshotChange> diff(List<LgdRow> rows) {
        List<SnapshotChange> changes = new ArrayList<>();
        try (Connection c = connections.open()) {
            Map<String, Current> current = currentVersions(c);
            Map<String, String> parents = currentParents(c);
            for (LgdRow row : rows) {
                String key = key(row.entityType(), row.code());
                Current was = current.get(key);
                if (was == null) {
                    changes.add(new SnapshotChange("ADDED", row.entityType(), row.code(), null, row.name()));
                } else {
                    if (!was.name().equals(row.name())) {
                        changes.add(new SnapshotChange("RENAMED", row.entityType(), row.code(), was.name(), row.name()));
                    }
                    if (!was.status().equals(row.status())) {
                        changes.add(new SnapshotChange(INACTIVE.equals(row.status()) ? "INACTIVATED" : "REACTIVATED",
                                row.entityType(), row.code(), was.status(), row.status()));
                    }
                }
                String previous = parents.get(key);
                if (row.parentType() != null && previous != null
                        && !key(row.parentType(), row.parentCode()).equals(previous)) {
                    changes.add(new SnapshotChange("REPARENTED", row.entityType(), row.code(), previous,
                            key(row.parentType(), row.parentCode())));
                }
            }
            return changes;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the active LGD master", e);
        }
    }

    private static LgdRow normalise(LgdRow row) {
        return new LgdRow(row.lineNo(), upper(row.entityType()), strip(row.code()), strip(row.name()),
                row.status() == null ? ACTIVE : upper(row.status()), upper(row.parentType()), strip(row.parentCode()),
                row.effectiveFrom());
    }

    // ---- staging ---------------------------------------------------------------------------------------------------

    private void stage(UUID snapshotId, LgdSnapshotData data, List<LgdRow> rows, String actor) {
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("insert into lgd_snapshot(snapshot_id,source_label,as_of,checksum,status,"
                    + "row_count,created_by,created_at) values(?,?,?,?,'STAGED',?,?,?)")) {
                s.setObject(1, snapshotId);
                s.setString(2, data.sourceLabel() == null ? "unnamed" : data.sourceLabel());
                s.setDate(3, Date.valueOf(data.asOf()));
                s.setString(4, checksum(rows));
                s.setInt(5, rows.size());
                s.setString(6, actor);
                s.setTimestamp(7, Timestamp.from(clock.instant()));
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("insert into lgd_staging_row(snapshot_id,position,line_no,entity_type,code,"
                    + "name,status,parent_type,parent_code,effective_from) values(?,?,?,?,?,?,?,?,?,?)")) {
                int position = 0;
                for (LgdRow row : rows) {
                    s.setObject(1, snapshotId);
                    s.setInt(2, ++position);
                    s.setInt(3, row.lineNo());
                    s.setString(4, row.entityType());
                    s.setString(5, row.code());
                    s.setString(6, row.name());
                    s.setString(7, row.status());
                    s.setString(8, row.parentType());
                    s.setString(9, row.parentCode());
                    s.setDate(10, row.effectiveFrom() == null ? null : Date.valueOf(row.effectiveFrom()));
                    s.addBatch();
                }
                s.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot stage the LGD snapshot", e);
        }
    }

    private static String checksum(List<LgdRow> rows) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            rows.stream().map(r -> String.join("|", Objects.toString(r.entityType()), Objects.toString(r.code()),
                    Objects.toString(r.name()), Objects.toString(r.status()), Objects.toString(r.parentType()),
                    Objects.toString(r.parentCode()), Objects.toString(r.effectiveFrom()))).sorted()
                    .forEach(line -> digest.update((line + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- validation of the whole snapshot --------------------------------------------------------------------------

    private List<SnapshotIssue> validate(List<LgdRow> rows) {
        Map<String, Integer> levels = entityTypes();
        Map<String, LgdRow> byKey = new HashMap<>();
        List<SnapshotIssue> errors = new ArrayList<>();
        for (LgdRow row : rows) {
            if (row.entityType() == null || !levels.containsKey(row.entityType())) {
                errors.add(new SnapshotIssue(row.lineNo(), "unknown entity type " + row.entityType()));
            }
            if (row.code() == null || !CODE.matcher(row.code()).matches()) {
                errors.add(new SnapshotIssue(row.lineNo(), "invalid code"));
            }
            if (row.name() == null) {
                errors.add(new SnapshotIssue(row.lineNo(), "name is required"));
            }
            if (!ACTIVE.equals(row.status()) && !INACTIVE.equals(row.status())) {
                errors.add(new SnapshotIssue(row.lineNo(), "status must be ACTIVE or INACTIVE"));
            }
            if (byKey.putIfAbsent(row.entityType() + ":" + row.code(), row) != null) {
                errors.add(new SnapshotIssue(row.lineNo(), "duplicate " + row.entityType() + " " + row.code()));
            }
        }
        for (LgdRow row : rows) {
            if ((row.parentType() == null) != (row.parentCode() == null)) {
                errors.add(new SnapshotIssue(row.lineNo(), "parentType and parentCode go together"));
                continue;
            }
            if (row.parentType() == null) {
                continue;
            }
            Integer parentLevel = levels.get(row.parentType());
            Integer childLevel = levels.get(row.entityType());
            if (parentLevel == null) {
                errors.add(new SnapshotIssue(row.lineNo(), "unknown parent type " + row.parentType()));
            } else if (childLevel != null && parentLevel >= childLevel) {
                errors.add(new SnapshotIssue(row.lineNo(), row.parentType() + " cannot be the parent of " + row.entityType()));
            } else if (!byKey.containsKey(row.parentType() + ":" + row.parentCode())
                    && !entityExists(row.parentType(), row.parentCode())) {
                errors.add(new SnapshotIssue(row.lineNo(), "parent " + row.parentType() + " " + row.parentCode() + " is unknown"));
            }
        }
        return errors.size() > MAX_ERRORS ? List.copyOf(errors.subList(0, MAX_ERRORS)) : errors;
    }

    Map<String, Integer> entityTypes() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select entity_type,level_order from lgd_entity_type order by level_order")) {
            var r = s.executeQuery();
            while (r.next()) {
                levels.put(r.getString(1), r.getInt(2));
            }
            return levels;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the LGD entity types", e);
        }
    }

    private boolean entityExists(String type, String code) {
        try (var c = connections.open();
                var s = c.prepareStatement("select 1 from lgd_entity where entity_type=? and code=?")) {
            s.setString(1, type);
            s.setString(2, code);
            return s.executeQuery().next();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot look up an LGD entity", e);
        }
    }

    private void fail(UUID snapshotId, List<String> errors) {
        try (var c = connections.open();
                var s = c.prepareStatement("update lgd_snapshot set status='FAILED',errors=?::jsonb where snapshot_id=?");
                var d = c.prepareStatement("delete from lgd_staging_row where snapshot_id=?")) {
            s.setString(1, json.writeValueAsString(errors));
            s.setObject(2, snapshotId);
            s.executeUpdate();
            d.setObject(1, snapshotId);
            d.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record the failed LGD snapshot", e);
        }
    }

    // ---- diff, persist and activate (one transaction) --------------------------------------------------------------

    private Map<String, Integer> persistAndActivate(UUID snapshotId, LocalDate asOf, List<LgdRow> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            try {
                try (var lock = c.prepareStatement("select pg_advisory_xact_lock(hashtext('lgd_master'))")) {
                    lock.execute();
                }
                Map<String, Current> current = currentVersions(c);
                Map<String, String> parents = currentParents(c);
                List<LgdRow> ordered = new ArrayList<>(rows);
                ordered.sort(Comparator.comparingInt(LgdRow::lineNo));
                for (LgdRow row : ordered) {
                    if (!current.containsKey(key(row.entityType(), row.code()))) {
                        exec(c, "insert into lgd_entity(entity_type,code,first_snapshot_id) values(?,?,?)",
                                row.entityType(), row.code(), snapshotId);
                    }
                }
                for (LgdRow row : ordered) {
                    LocalDate effective = row.effectiveFrom() == null ? asOf : row.effectiveFrom();
                    String key = key(row.entityType(), row.code());
                    Current was = current.get(key);
                    if (was == null) {
                        insertVersion(c, row, effective, snapshotId);
                        change(c, snapshotId, row, "ADDED", null, row.name(), effective, counts);
                    } else {
                        boolean renamed = !was.name().equals(row.name());
                        boolean statusChanged = !was.status().equals(row.status());
                        if (renamed || statusChanged) {
                            LocalDate closeAt = effective.isBefore(was.effectiveFrom()) ? was.effectiveFrom() : effective;
                            exec(c, "update lgd_entity_version set effective_to=? where entity_type=? and code=?"
                                    + " and effective_to is null", Date.valueOf(closeAt), row.entityType(), row.code());
                            insertVersion(c, row, closeAt, snapshotId);
                        }
                        if (renamed) {
                            change(c, snapshotId, row, "RENAMED", was.name(), row.name(), effective, counts);
                        }
                        if (statusChanged) {
                            change(c, snapshotId, row, INACTIVE.equals(row.status()) ? "INACTIVATED" : "REACTIVATED",
                                    was.status(), row.status(), effective, counts);
                        }
                    }
                    if (row.parentType() != null) {
                        String parent = key(row.parentType(), row.parentCode());
                        String previous = parents.get(key);
                        if (!parent.equals(previous)) {
                            if (previous != null) {
                                exec(c, "update lgd_relationship set valid_to=? where child_type=? and child_code=?"
                                        + " and valid_to is null", Date.valueOf(effective), row.entityType(), row.code());
                                change(c, snapshotId, row, "REPARENTED", previous, parent, effective, counts);
                            }
                            exec(c, "insert into lgd_relationship(parent_type,parent_code,child_type,child_code,"
                                    + "valid_from,snapshot_id) values(?,?,?,?,?,?)", row.parentType(), row.parentCode(),
                                    row.entityType(), row.code(), Date.valueOf(effective), snapshotId);
                        }
                    }
                }
                int total = counts.values().stream().mapToInt(Integer::intValue).sum();
                Timestamp now = Timestamp.from(clock.instant());
                exec(c, "update lgd_snapshot set status=?,change_count=?,activated_at=? where snapshot_id=?",
                        total == 0 ? "UNCHANGED" : ACTIVE, total, total == 0 ? null : now, snapshotId);
                if (total > 0) {
                    exec(c, "insert into lgd_master_state(singleton,active_snapshot_id,activated_at) values(true,?,?)"
                            + " on conflict (singleton) do update set active_snapshot_id=excluded.active_snapshot_id,"
                            + "activated_at=excluded.activated_at", snapshotId, now);
                }
                exec(c, "delete from lgd_staging_row where snapshot_id=?", snapshotId);
                c.commit();
                return counts;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getMessage(), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static Map<String, Current> currentVersions(Connection c) throws SQLException {
        Map<String, Current> current = new HashMap<>();
        try (var s = c.prepareStatement("select entity_type,code,name,status,effective_from from lgd_entity_version"
                + " where effective_to is null")) {
            var r = s.executeQuery();
            while (r.next()) {
                current.put(key(r.getString(1), r.getString(2)),
                        new Current(r.getString(3), r.getString(4), r.getDate(5).toLocalDate()));
            }
        }
        return current;
    }

    private static Map<String, String> currentParents(Connection c) throws SQLException {
        Map<String, String> parents = new HashMap<>();
        try (var s = c.prepareStatement("select child_type,child_code,parent_type,parent_code from lgd_relationship"
                + " where valid_to is null")) {
            var r = s.executeQuery();
            while (r.next()) {
                parents.put(key(r.getString(1), r.getString(2)), key(r.getString(3), r.getString(4)));
            }
        }
        return parents;
    }

    private static void insertVersion(Connection c, LgdRow row, LocalDate effective, UUID snapshotId)
            throws SQLException {
        exec(c, "insert into lgd_entity_version(entity_type,code,name,status,effective_from,snapshot_id)"
                + " values(?,?,?,?,?,?)", row.entityType(), row.code(), row.name(), row.status(),
                Date.valueOf(effective), snapshotId);
    }

    private static void change(Connection c, UUID snapshotId, LgdRow row, String type, String oldValue,
                               String newValue, LocalDate effective, Map<String, Integer> counts) throws SQLException {
        exec(c, "insert into lgd_change(snapshot_id,entity_type,code,change_type,old_value,new_value,effective_from)"
                + " values(?,?,?,?,?,?,?)", snapshotId, row.entityType(), row.code(), type, oldValue, newValue,
                Date.valueOf(effective));
        counts.merge(type, 1, Integer::sum);
    }

    private static void exec(Connection c, String sql, Object... parameters) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        }
    }

    static String key(String type, String code) {
        return type + ":" + code;
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
