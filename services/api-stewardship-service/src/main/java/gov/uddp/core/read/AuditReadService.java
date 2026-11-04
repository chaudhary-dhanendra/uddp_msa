package gov.uddp.core.read;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.security.CurrentUser;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * The audit trail for the audit log screen (ADR-0020): filter by user, action, result, state and date range. A user
 * who does not see every state sees the events of states in scope and the events that concern no state (sign-ins,
 * user administration). Read-only; the table itself rejects updates and deletes.
 */
@Service
public class AuditReadService {
    public record AuditEventView(UUID eventId, long sequence, OffsetDateTime occurredAt, String actor, String action,
                                 String resourceType, String resourceId, String stateCode, String result,
                                 JsonNode metadata) {
    }

    private static final Map<String, String> SORT = Map.of("occurredAt", "e.occurred_at", "action", "e.action",
            "actor", "e.actor_username", "stateCode", "e.state_code");
    private static final String COLUMNS = "select e.event_id, e.sequence, e.occurred_at, e.actor_username, e.action,"
            + " e.resource_type, e.resource_id, e.state_code, e.result, e.metadata::text as metadata_text"
            + " from app_audit_event e";
    /** Most events an export may contain. */
    public static final int EXPORT_LIMIT = 10_000;

    private final Db db;

    public AuditReadService(Db db) {
        this.db = db;
    }

    /**
     * @param actor  username contains (case-insensitive)
     * @param action exact action, for example {@code LOGIN}
     * @param result SUCCESS, DENIED or FAILURE
     * @param state  exact state code
     * @param from   first day (ISO date, inclusive, UTC)
     * @param to     last day (ISO date, inclusive, UTC)
     */
    public Page<AuditEventView> events(String actor, String action, String result, String state, String from,
                                       String to, Integer page, Integer size, String sort) {
        var query = PageQuery.of(page, size, sort, SORT, "occurredAt,desc", "e.sequence desc");
        Filter filter = filter(actor, action, result, state, from, to);
        long total = db.count("select count(*) from app_audit_event e" + filter.where(), filter.parameters());
        var items = db.list(COLUMNS + filter.where() + query.sql(), this::event, filter.parameters());
        return Page.of(items, query, total);
    }

    /** The filtered events, newest first, at most {@link #EXPORT_LIMIT}, for the CSV export. */
    public void export(String actor, String action, String result, String state, String from, String to,
                       Consumer<AuditEventView> sink) {
        Filter filter = filter(actor, action, result, state, from, to);
        db.list(COLUMNS + filter.where() + " order by e.occurred_at desc, e.sequence desc limit " + EXPORT_LIMIT,
                r -> {
                    sink.accept(event(r));
                    return null;
                }, filter.parameters());
    }

    /** The distinct actions recorded so far, for the filter. */
    public List<String> actions() {
        String[] scope = CurrentUser.scope().sqlCodes();
        return db.list("select distinct action from app_audit_event e where (cast(? as text[]) is null"
                + " or e.state_code = any(cast(? as text[]))) order by action",
                r -> r.getString(1), scope, scope);
    }

    private record Filter(String where, Object[] parameters) {
    }

    private static Filter filter(String actor, String action, String result, String state, String from, String to) {
        String who = blank(actor) ? null : "%" + CanonicalReadService.escapeLike(actor.strip()) + "%";
        String what = blank(action) ? null : action.strip().toUpperCase(Locale.ROOT);
        String outcome = blank(result) ? null : result.strip().toUpperCase(Locale.ROOT);
        if (outcome != null && !List.of("SUCCESS", "DENIED", "FAILURE").contains(outcome)) {
            throw new IllegalArgumentException("result must be SUCCESS, DENIED or FAILURE");
        }
        String code = blank(state) ? null : state.strip().toUpperCase(Locale.ROOT);
        OffsetDateTime start = day(from, "from");
        OffsetDateTime end = day(to, "to");
        String[] scope = CurrentUser.scope().sqlCodes();
        String where = " where (cast(? as text) is null or e.actor_username ilike cast(? as text))"
                + " and (cast(? as text) is null or e.action = cast(? as text))"
                + " and (cast(? as text) is null or e.result = cast(? as text))"
                + " and (cast(? as text) is null or e.state_code = cast(? as text))"
                + " and (cast(? as timestamptz) is null or e.occurred_at >= cast(? as timestamptz))"
                + " and (cast(? as timestamptz) is null or e.occurred_at < cast(? as timestamptz) + interval '1 day')"
                + " and (cast(? as text[]) is null or e.state_code = any(cast(? as text[])))"
                + " and not exists (select 1 from retention_tombstone t where t.category = 'AUDIT_EVENTS'"
                + " and t.record_id = e.event_id::text)";
        return new Filter(where, new Object[] {who, who, what, what, outcome, outcome, code, code, start, start, end, end,
            scope, scope});
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

    private AuditEventView event(java.sql.ResultSet r) throws java.sql.SQLException {
        return new AuditEventView(Db.uuid(r, "event_id"), r.getLong("sequence"), Db.time(r, "occurred_at"),
                r.getString("actor_username"), r.getString("action"), r.getString("resource_type"),
                r.getString("resource_id"), r.getString("state_code"), r.getString("result"),
                db.json(r.getString("metadata_text")));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
