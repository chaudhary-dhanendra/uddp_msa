package gov.uddp.core.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import gov.uddp.core.privacy.PiiMasker;
import gov.uddp.core.security.CurrentUser;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * Final-rejected held records for audit reporting and the feedback file for the source department. The report lists
 * every held record a steward rejected for good, including those reopened afterwards (flagged), from the append-only
 * event log, so the reason, actor and time survive a reopening and a payload purge. Identifiers and values are masked
 * unless the caller asks for them and may view personal data in full. A user sees the states in their scope.
 */
@Service
public class RejectedRecordReportService {
    /** Most rows an export may contain. */
    public static final int EXPORT_LIMIT = 10_000;
    private static final String HIDDEN = "•••";
    private static final int TREND_WEEKS = 12;

    public record OriginalReason(String code, String field, String message) {
    }

    public record RejectedRow(UUID heldId, UUID batchId, String reference, String sourceSystem, String stateCode,
                              String department, String reasonCode, String note, List<OriginalReason> originalReasons,
                              String decidedBy, OffsetDateTime decidedAt, boolean reopened, String currentStatus,
                              boolean payloadPurged) {
    }

    public record WeekCount(String sourceSystem, String weekStart, long count) {
    }

    public record Summary(long total, long reopened, List<WeekCount> perSourcePerWeek) {
    }

    /** One line of the department file. */
    public record FeedbackRow(int rowNumber, String reasonCode, String reasonText, String field, String maskedValue,
                              String status) {
    }

    private record Filter(String where, Object[] parameters) {
    }

    private static final String FROM = " from held_record h join raw_record r on r.raw_record_id = h.raw_record_id"
            + " join lateral (select e.actor, e.at, e.note, e.after->>'reasonCode' code from held_record_event e"
            + " where e.held_id = h.held_id and e.action in ('REJECT','BULK_REJECT')"
            + " order by e.at desc, e.event_id desc limit 1) rej on true";
    private static final String REOPENED = "exists (select 1 from held_record_event x where x.held_id = h.held_id"
            + " and x.action = 'REOPEN')";
    private static final String COLUMNS = "select h.held_id, h.batch_id, coalesce(r.source_record_id,"
            + " h.raw_record_id::text) as reference, h.source_system, h.state_code, h.department, rej.code, rej.note,"
            + " (select o.after::text from held_record_event o where o.held_id = h.held_id and o.action = 'HELD'"
            + " order by o.at, o.event_id limit 1) as original_reasons, rej.actor, rej.at, " + REOPENED
            + " as reopened, h.status, h.payload_purged_at is not null as purged";

    private final Db db;
    private final ObjectMapper json;

    public RejectedRecordReportService(Db db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public Page<RejectedRow> rejected(UUID batch, String source, String department, String state, String reason,
                                      String decidedBy, String from, String to, boolean unmasked, Integer page,
                                      Integer size) {
        var query = PageQuery.of(page, size, null, java.util.Map.of("decidedAt", "rej.at"), "decidedAt,desc",
                "h.held_id");
        Filter filter = filter(batch, source, department, state, reason, decidedBy, from, to);
        long total = db.count("select count(*)" + FROM + filter.where(), filter.parameters());
        var items = db.list(COLUMNS + FROM + filter.where() + query.sql(), r -> row(r, unmasked), filter.parameters());
        return Page.of(items, query, total);
    }

    public void export(UUID batch, String source, String department, String state, String reason, String decidedBy,
                       String from, String to, boolean unmasked, Consumer<RejectedRow> sink) {
        Filter filter = filter(batch, source, department, state, reason, decidedBy, from, to);
        db.list(COLUMNS + FROM + filter.where() + " order by rej.at desc, h.held_id limit " + EXPORT_LIMIT, r -> {
            sink.accept(row(r, unmasked));
            return null;
        }, filter.parameters());
    }

    /** Counts for the landing page: totals and final rejections per source per week over the last twelve weeks. */
    public Summary summary() {
        Filter filter = filter(null, null, null, null, null, null, null, null);
        long total = db.count("select count(*)" + FROM + filter.where(), filter.parameters());
        long reopened = db.count("select count(*)" + FROM + filter.where() + " and " + REOPENED, filter.parameters());
        Object[] parameters = java.util.Arrays.copyOf(filter.parameters(), filter.parameters().length + 1);
        parameters[parameters.length - 1] = TREND_WEEKS;
        var weeks = db.list("select h.source_system, date_trunc('week', rej.at at time zone 'UTC')::date::text as week,"
                + " count(*) as n" + FROM + filter.where()
                + " and rej.at >= date_trunc('week', now()) - make_interval(weeks => ?)"
                + " group by h.source_system, 2 order by 2, 1",
                r -> new WeekCount(r.getString(1), r.getString(2), r.getLong(3)), parameters);
        return new Summary(total, reopened, weeks);
    }

    /**
     * The feedback file for a batch: one line per reason of every held record of the batch, in file order, with the
     * failing field's value masked. A batch outside the caller's scope is reported as unknown.
     */
    public List<FeedbackRow> batchRejectionReport(UUID batch) {
        String state = db.one("select state_code from ingestion_batch where batch_id = ?", r -> r.getString(1), batch)
                .filter(code -> CurrentUser.scope().allows(code))
                .orElseThrow(() -> new ReadNotFoundException("Batch not found: " + batch));
        record Held(int ordinal, String payload, String status, String original, String reasons, String fields,
                    Integer sourceLine) {
        }
        var held = db.list("select o.ordinal, o.raw_payload, h.status, (select e.after::text from held_record_event e"
                + " where e.held_id = h.held_id and e.action = 'HELD' order by e.at, e.event_id limit 1),"
                + " h.reason_codes::text, case when h.payload_purged_at is null and r.payload_purged_at is null"
                + " then h.original_payload::text end, r.source_line"
                + " from held_record h join raw_record r on r.raw_record_id = h.raw_record_id"
                + " join (select raw_record_id, raw_payload, row_number() over (order by ingested_at, ctid) as ordinal"
                + " from raw_record where batch_id = ?) o on o.raw_record_id = h.raw_record_id"
                + " where h.batch_id = ? order by o.ordinal, h.held_id",
                r -> new Held(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                        r.getString(6), (Integer) r.getObject(7)), batch, batch);
        var rows = new ArrayList<FeedbackRow>();
        for (Held h : held) {
            JsonNode original = db.json(h.fields());
            int number = h.sourceLine() != null && h.sourceLine() > 0 ? h.sourceLine() : rowNumber(h.payload(), h.ordinal());
            JsonNode reasons = db.json(h.original() != null ? h.original() : h.reasons());
            for (OriginalReason reason : reasons(reasons)) {
                rows.add(new FeedbackRow(number, reason.code(), reason.message(), reason.field(),
                        maskedValue(original, reason.field()), statusLabel(h.status())));
            }
        }
        return rows;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private RejectedRow row(java.sql.ResultSet r, boolean unmasked) throws java.sql.SQLException {
        String reference = r.getString("reference");
        return new RejectedRow(Db.uuid(r, "held_id"), Db.uuid(r, "batch_id"),
                unmasked ? reference : mask(reference), r.getString("source_system"), r.getString("state_code"),
                r.getString("department"), r.getString("code"), r.getString("note"),
                reasons(db.json(r.getString("original_reasons"))), r.getString("actor"), Db.time(r, "at"),
                r.getBoolean("reopened"), r.getString("status"), r.getBoolean("purged"));
    }

    private static List<OriginalReason> reasons(JsonNode array) {
        var reasons = new ArrayList<OriginalReason>();
        if (array != null && array.isArray()) {
            array.forEach(item -> reasons.add(new OriginalReason(text(item, "code"), text(item, "field"),
                    text(item, "message"))));
        }
        return reasons;
    }

    private static String text(JsonNode node, String name) {
        return node.path(name).isTextual() ? node.get(name).asText() : null;
    }

    /**
     * For rows landed before V111 (no stored source line): the sheet row of a spreadsheet, else the position among the
     * batch's landed rows.
     */
    private int rowNumber(String payload, int ordinal) {
        try {
            int row = json.readTree(payload).path("row").asInt(0);
            return row > 0 ? row : ordinal;
        } catch (Exception e) {
            return ordinal;
        }
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "RECTIFIED" -> "RECTIFIED";
            case "REJECTED_FINAL" -> "REJECTED_FINAL";
            case "RELEASED_OVERRIDE" -> "RELEASED";
            default -> "HELD";
        };
    }

    /** The value at a dotted canonical path of the held record, masked; blank when it is gone (purged) or unnamed. */
    private static String maskedValue(JsonNode payload, String path) {
        if (payload == null || path == null || path.isBlank()) {
            return null;
        }
        JsonNode value = payload;
        for (String part : path.split("\\.")) {
            value = value.path(part);
        }
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        var probe = JsonNodeFactory.instance.objectNode().put("field", path);
        probe.set("value", value.deepCopy());
        JsonNode masked = PiiMasker.mask(probe).path("value");
        // PiiMasker knows names, birth dates, mobile numbers and addresses; any other value may be an identifier.
        return masked.equals(value) ? mask(value.isValueNode() ? value.asText() : HIDDEN) : masked.asText(HIDDEN);
    }

    /** "R1001" -> "•••1001"; a value of four characters or fewer is hidden completely. */
    static String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String trimmed = text.strip();
        return trimmed.length() <= 4 ? HIDDEN : HIDDEN + trimmed.substring(trimmed.length() - 4);
    }

    private static Filter filter(UUID batch, String source, String department, String state, String reason,
                                 String decidedBy, String from, String to) {
        String who = blank(decidedBy) ? null : "%" + CanonicalReadService.escapeLike(decidedBy.strip()) + "%";
        String code = blank(state) ? null : state.strip().toUpperCase(Locale.ROOT);
        String src = blank(source) ? null : source.strip();
        String dept = blank(department) ? null : department.strip();
        String why = blank(reason) ? null : reason.strip();
        OffsetDateTime start = day(from, "from");
        OffsetDateTime end = day(to, "to");
        String[] scope = CurrentUser.scope().sqlCodes();
        String where = " where (cast(? as uuid) is null or h.batch_id = cast(? as uuid))"
                + " and (cast(? as text) is null or h.source_system = cast(? as text))"
                + " and (cast(? as text) is null or h.department = cast(? as text))"
                + " and (cast(? as text) is null or h.state_code = cast(? as text))"
                + " and (cast(? as text) is null or rej.code = cast(? as text))"
                + " and (cast(? as text) is null or rej.actor ilike cast(? as text))"
                + " and (cast(? as timestamptz) is null or rej.at >= cast(? as timestamptz))"
                + " and (cast(? as timestamptz) is null or rej.at < cast(? as timestamptz) + interval '1 day')"
                + " and (cast(? as text[]) is null or h.state_code = any(cast(? as text[])))";
        return new Filter(where, new Object[] {batch, batch, src, src, dept, dept, code, code, why, why, who, who, start,
            start, end, end, scope, scope});
    }

    private static OffsetDateTime day(String value, String name) {
        if (blank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip()).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be an ISO date (YYYY-MM-DD)");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
