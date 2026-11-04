package gov.uddp.core.held;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Persistence of held records and their append-only event log (V108). */
@Repository
public class HeldRecordRepository {
    private static final String COLUMNS = "h.held_id,h.batch_id,h.raw_record_id,h.source_system,h.state_code,h.department,"
            + "h.state_pack_version,h.reason_codes,h.status,h.created_at,h.assigned_to,h.decided_by,h.decided_at,"
            + "h.decision_reason_code,h.decision_note,"
            + "case when r.payload_purged_at is null then h.original_payload end,"
            + "h.current_payload,h.correction_version,h.pack_id,h.draft_id";
    private static final String FROM = " from held_record h join raw_record r on r.raw_record_id = h.raw_record_id";

    /** Statuses in which a held record is still waiting for a decision. */
    public static final List<String> WAITING = List.of("OPEN", "IN_REVIEW");
    /** The status filter that means "OPEN or IN_REVIEW". */
    public static final String WAITING_FILTER = "WAITING";

    private final PostgresConnectionFactory connections;
    private final ObjectMapper json;

    public HeldRecordRepository(PostgresConnectionFactory connections, ObjectMapper json) {
        this.connections = connections;
        this.json = json;
    }

    /** Filters of a list; null fields do not filter. */
    public record Filter(UUID batchId, String sourceSystem, String department, String reason, String status,
                         Integer minAgeHours, List<String> stateCodes) {
        public static Filter all() {
            return new Filter(null, null, null, null, null, null, null);
        }

        public Filter withStatesAndBatch(UUID batch, List<String> states) {
            return new Filter(batch, sourceSystem, department, reason, status, minAgeHours, states);
        }
    }

    // ---- writes ----------------------------------------------------------------------------------------------------

    /** Holds the row; false when the raw record is already held (a resumed or repeated chunk). */
    public boolean insert(HeldRecord held, String actor) {
        try (var c = connections.open()) {
            UUID id;
            // The pack's Studio draft, if it was made in the Studio, so "Fix in pack" opens that draft.
            try (var s = c.prepareStatement("insert into held_record(batch_id,raw_record_id,source_system,state_code,"
                    + "department,state_pack_version,reason_codes,original_payload,current_payload,pack_id,draft_id)"
                    + " values(?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),cast(? as jsonb),?,"
                    + "(select d.draft_id from studio_draft d where d.pack_id = ? order by d.created_at desc limit 1))"
                    + " on conflict (raw_record_id) do nothing returning held_id")) {
                s.setObject(1, held.batchId());
                s.setObject(2, held.rawRecordId());
                s.setString(3, held.sourceSystem());
                s.setString(4, held.stateCode());
                s.setString(5, held.department());
                s.setString(6, held.statePackVersion());
                s.setString(7, write(held.reasons()));
                s.setString(8, write(held.currentPayload()));
                s.setString(9, write(held.currentPayload()));
                s.setString(10, held.packId());
                s.setString(11, held.packId());
                var r = s.executeQuery();
                if (!r.next()) {
                    return false;
                }
                id = r.getObject(1, UUID.class);
            }
            event(c, id, actor, "HELD", null, held.reasons(), null);
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot hold the record", e);
        }
    }

    /** True when the raw record has a held record in any status. */
    public boolean exists(UUID rawRecordId) {
        try (var c = connections.open();
                var s = c.prepareStatement("select 1 from held_record where raw_record_id = ?")) {
            s.setObject(1, rawRecordId);
            return s.executeQuery().next();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot look for a held record", e);
        }
    }

    /**
     * Moves the record from one of {@code from} to {@code to}; false when it was not in one of them (a concurrent
     * decision). Decision fields are set only for a decided status.
     */
    public boolean transition(UUID heldId, List<String> from, String to, String actor, String reasonCode, String note,
                              String action, Object before, Object after) {
        boolean decided = !WAITING.contains(to);
        try (var c = connections.open()) {
            try (var s = c.prepareStatement("update held_record set status = ?,"
                    + " decided_by = case when ? then ? else null end,"
                    + " decided_at = case when ? then now() else null end,"
                    + " decision_reason_code = case when ? then ? else null end,"
                    + " decision_note = case when ? then ? else null end"
                    + " where held_id = ? and status = any (?)")) {
                s.setString(1, to);
                s.setBoolean(2, decided);
                s.setString(3, actor);
                s.setBoolean(4, decided);
                s.setBoolean(5, decided);
                s.setString(6, reasonCode);
                s.setBoolean(7, decided);
                s.setString(8, note);
                s.setObject(9, heldId);
                s.setArray(10, c.createArrayOf("text", from.toArray()));
                if (s.executeUpdate() != 1) {
                    return false;
                }
            }
            event(c, heldId, actor, action, before, after, note);
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update the held record", e);
        }
    }

    /** Stores a correction as the next version and the (possibly unchanged) reasons it still has. */
    public void saveCorrection(UUID heldId, JsonNode payload, List<HeldReason> reasons, int version, String actor,
                               JsonNode before, String note) {
        try (var c = connections.open()) {
            try (var s = c.prepareStatement("update held_record set current_payload = cast(? as jsonb),"
                    + " reason_codes = cast(? as jsonb), correction_version = ?, assigned_to = ? where held_id = ?")) {
                s.setString(1, write(payload));
                s.setString(2, write(reasons));
                s.setInt(3, version);
                s.setString(4, actor);
                s.setObject(5, heldId);
                s.executeUpdate();
            }
            event(c, heldId, actor, "RECTIFY_ATTEMPT", before, payload, note);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot store the correction", e);
        }
    }

    public void saveReasons(UUID heldId, List<HeldReason> reasons) {
        try (var c = connections.open();
                var s = c.prepareStatement("update held_record set reason_codes = cast(? as jsonb) where held_id = ?")) {
            s.setString(1, write(reasons));
            s.setObject(2, heldId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot store the reasons", e);
        }
    }

    public void note(UUID heldId, String actor, String action, Object before, Object after, String note) {
        try (var c = connections.open()) {
            event(c, heldId, actor, action, before, after, note);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot log the event", e);
        }
    }

    private void event(Connection c, UUID heldId, String actor, String action, Object before, Object after, String note)
            throws SQLException {
        try (var s = c.prepareStatement("insert into held_record_event(held_id,actor,action,before,after,note)"
                + " values(?,?,?,cast(? as jsonb),cast(? as jsonb),?)")) {
            s.setObject(1, heldId);
            s.setString(2, actor == null ? "SYSTEM" : actor);
            s.setString(3, action);
            s.setString(4, before == null ? null : write(before));
            s.setString(5, after == null ? null : write(after));
            s.setString(6, note);
            s.executeUpdate();
        }
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    public Optional<HeldRecord> find(UUID heldId) {
        try (var c = connections.open();
                var s = c.prepareStatement("select " + COLUMNS + FROM + " where h.held_id = ?")) {
            s.setObject(1, heldId);
            var r = s.executeQuery();
            return r.next() ? Optional.of(row(r)) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the held record", e);
        }
    }

    public List<HeldRecord> list(Filter filter, int limit, int offset) {
        var where = new Where(filter);
        try (var c = connections.open();
                var s = c.prepareStatement("select " + COLUMNS + FROM + where.sql()
                        + " order by h.created_at, h.held_id limit ? offset ?")) {
            int next = where.bind(c, s);
            s.setInt(next++, limit);
            s.setInt(next, offset);
            var r = s.executeQuery();
            var rows = new ArrayList<HeldRecord>();
            while (r.next()) {
                rows.add(row(r));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot list held records", e);
        }
    }

    public int count(Filter filter) {
        var where = new Where(filter);
        try (var c = connections.open();
                var s = c.prepareStatement("select count(*)" + FROM + where.sql())) {
            where.bind(c, s);
            var r = s.executeQuery();
            r.next();
            return r.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot count held records", e);
        }
    }

    public List<HeldEvent> events(UUID heldId) {
        try (var c = connections.open();
                var s = c.prepareStatement("select event_id,actor,action,before,after,note,at from held_record_event"
                        + " where held_id = ? order by at, event_id")) {
            s.setObject(1, heldId);
            var r = s.executeQuery();
            var events = new ArrayList<HeldEvent>();
            while (r.next()) {
                events.add(new HeldEvent(r.getObject(1, UUID.class), r.getString(2), r.getString(3), tree(r.getString(4)),
                        tree(r.getString(5)), r.getString(6), r.getObject(7, OffsetDateTime.class)));
            }
            return events;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the held record history", e);
        }
    }

    /** One group: the records of a batch that share a reason code. */
    /** One waiting group; {@code sourceSystem} is the batch's first source and {@code oldest} its oldest record. */
    public record GroupRow(UUID batchId, String reasonCode, String stateCode, int count, int batchRows,
                           String sourceSystem, java.time.Instant oldest) {
    }

    /** Waiting records grouped by batch and reason code; a record with several reasons is in several groups. */
    public List<GroupRow> groups(List<String> stateCodes) {
        String sql = "select h.batch_id, x.code, h.state_code, count(*),"
                + " (select count(*) from raw_record rr where rr.batch_id = h.batch_id),"
                + " min(h.source_system), min(h.created_at)"
                + " from held_record h cross join lateral (select distinct e->>'code' code"
                + " from jsonb_array_elements(h.reason_codes) e) x"
                + " where h.status = any (?) " + (stateCodes == null ? "" : "and h.state_code = any (?) ")
                + "group by h.batch_id, x.code, h.state_code order by h.batch_id, x.code";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setArray(1, c.createArrayOf("text", WAITING.toArray()));
            if (stateCodes != null) {
                s.setArray(2, c.createArrayOf("text", stateCodes.toArray()));
            }
            var r = s.executeQuery();
            var rows = new ArrayList<GroupRow>();
            while (r.next()) {
                rows.add(new GroupRow(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getInt(4),
                        r.getInt(5), r.getString(6), r.getTimestamp(7).toInstant()));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot group held records", e);
        }
    }

    /** The ids of the waiting records of a batch with the reason code. */
    public List<UUID> waitingIds(UUID batchId, String reasonCode) {
        try (var c = connections.open();
                var s = c.prepareStatement("select h.held_id from held_record h where h.batch_id = ?"
                        + " and h.status = any (?) and exists (select 1 from jsonb_array_elements(h.reason_codes) e"
                        + " where e->>'code' = ?) order by h.created_at, h.held_id")) {
            s.setObject(1, batchId);
            s.setArray(2, c.createArrayOf("text", WAITING.toArray()));
            s.setString(3, reasonCode);
            var r = s.executeQuery();
            var ids = new ArrayList<UUID>();
            while (r.next()) {
                ids.add(r.getObject(1, UUID.class));
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot list the group", e);
        }
    }

    /** Held and rejected rows of the batch, and the rows it delivered. */
    public record BatchShare(int held, int rejected, int rows) {
        public double percent() {
            return rows == 0 ? 0 : 100.0 * (held + rejected) / rows;
        }
    }

    public BatchShare share(UUID batchId) {
        try (var c = connections.open();
                var s = c.prepareStatement("select (select count(*) from held_record where batch_id = ?),"
                        + " (select count(distinct raw_record_id) from canonical_processing_error where batch_id = ?),"
                        + " (select count(*) from raw_record where batch_id = ?)")) {
            s.setObject(1, batchId);
            s.setObject(2, batchId);
            s.setObject(3, batchId);
            var r = s.executeQuery();
            r.next();
            return new BatchShare(r.getInt(1), r.getInt(2), r.getInt(3));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot measure the held share", e);
        }
    }

    /** Counts per status, for metrics. */
    public java.util.Map<String, Integer> countsByStatus() {
        try (var c = connections.open();
                var s = c.prepareStatement("select status, count(*) from held_record group by status")) {
            var r = s.executeQuery();
            var counts = new java.util.TreeMap<String, Integer>();
            while (r.next()) {
                counts.put(r.getString(1), r.getInt(2));
            }
            return counts;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot count held records", e);
        }
    }

    /** Age in seconds of the oldest waiting record, 0 with none. */
    public double oldestWaitingSeconds() {
        return scalar("select coalesce(extract(epoch from now() - min(created_at)), 0) from held_record"
                + " where status in ('OPEN','IN_REVIEW')");
    }

    /** Mean seconds from hold to decision over decided records, 0 with none. */
    public double meanSecondsToDecide() {
        return scalar("select coalesce(avg(extract(epoch from decided_at - created_at)), 0) from held_record"
                + " where decided_at is not null");
    }

    private double scalar(String sql) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            var r = s.executeQuery();
            r.next();
            return r.getDouble(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the held record metric", e);
        }
    }

    // ---- mapping ---------------------------------------------------------------------------------------------------

    private HeldRecord row(ResultSet r) throws SQLException {
        return new HeldRecord(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class),
                r.getString(4), r.getString(5), r.getString(6), r.getString(7), reasons(r.getString(8)), r.getString(9),
                r.getObject(10, OffsetDateTime.class), r.getString(11), r.getString(12),
                r.getObject(13, OffsetDateTime.class), r.getString(14), r.getString(15), tree(r.getString(16)),
                tree(r.getString(17)), r.getInt(18), r.getString(19), r.getObject(20, UUID.class));
    }

    private List<HeldReason> reasons(String text) {
        try {
            return json.readValue(text, new TypeReference<List<HeldReason>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("Stored reasons are not valid", e);
        }
    }

    private JsonNode tree(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Stored JSON is not valid", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise", e);
        }
    }

    /** The WHERE clause of a {@link Filter} with its parameters in order. */
    private static final class Where {
        private final Filter filter;
        private final StringBuilder sql = new StringBuilder(" where true");

        Where(Filter f) {
            this.filter = f;
            if (f.batchId() != null) {
                sql.append(" and h.batch_id = ?");
            }
            if (f.sourceSystem() != null) {
                sql.append(" and h.source_system = ?");
            }
            if (f.department() != null) {
                sql.append(" and h.department = ?");
            }
            if (f.reason() != null) {
                sql.append(" and exists (select 1 from jsonb_array_elements(h.reason_codes) e where e->>'code' = ?)");
            }
            if (WAITING_FILTER.equals(f.status())) {
                sql.append(" and h.status in ('OPEN','IN_REVIEW')");
            } else if (f.status() != null) {
                sql.append(" and h.status = ?");
            }
            if (f.minAgeHours() != null) {
                sql.append(" and h.created_at <= now() - make_interval(hours => ?)");
            }
            if (f.stateCodes() != null) {
                sql.append(" and h.state_code = any (?)");
            }
        }

        String sql() {
            return sql.toString();
        }

        int bind(Connection c, java.sql.PreparedStatement s) throws SQLException {
            int i = 1;
            if (filter.batchId() != null) {
                s.setObject(i++, filter.batchId());
            }
            if (filter.sourceSystem() != null) {
                s.setString(i++, filter.sourceSystem());
            }
            if (filter.department() != null) {
                s.setString(i++, filter.department());
            }
            if (filter.reason() != null) {
                s.setString(i++, filter.reason());
            }
            if (filter.status() != null && !WAITING_FILTER.equals(filter.status())) {
                s.setString(i++, filter.status());
            }
            if (filter.minAgeHours() != null) {
                s.setInt(i++, filter.minAgeHours());
            }
            if (filter.stateCodes() != null) {
                s.setArray(i++, c.createArrayOf("text", filter.stateCodes().toArray()));
            }
            return i;
        }
    }
}
