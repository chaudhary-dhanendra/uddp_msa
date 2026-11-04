package gov.uddp.core.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.connector.ConnectorSecrets;
import gov.uddp.core.monitoring.MonitoringService.Metric;
import gov.uddp.core.security.SecretRedactor;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Alert rules, in-app notifications and the optional outgoing webhook (Track C6). A rule is a metric, a comparison, a
 * threshold and a severity, for one source or all. A breach creates one notification (not another until it is
 * read) and queues a webhook delivery when a URL is configured. Deliveries are signed with HMAC-SHA256 and retried
 * with exponential backoff. The URL and the signing secret live only in the encrypted settings store; a payload never
 * contains either.
 */
@Service
public class AlertService {
    static final String URL_SECRET = "alerts.webhook.url";
    static final String SIGNING_SECRET = "alerts.webhook.signingKey";
    static final int MAX_ATTEMPTS = 5;

    /** The HTTP boundary of the webhook; replaced by a fixture in tests. */
    public interface WebhookSender {
        void send(String url, String body, Map<String, String> headers) throws java.io.IOException;
    }

    public record Rule(UUID ruleId, String name, Metric metric, String comparison, double threshold, String severity,
                       String stateCode, String sourceSystem, boolean enabled) {
    }

    private final PostgresConnectionFactory connections;
    private final MonitoringService monitoring;
    private final ConnectorSecrets secrets;
    private final WebhookSender sender;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration backoff;

    @Autowired
    public AlertService(PostgresConnectionFactory connections, MonitoringService monitoring, ConnectorSecrets secrets,
                        WebhookSender sender, ObjectMapper json) {
        this(connections, monitoring, secrets, sender, json, Clock.systemUTC(), Duration.ofMinutes(1));
    }

    public AlertService(PostgresConnectionFactory connections, MonitoringService monitoring, ConnectorSecrets secrets,
                        WebhookSender sender, ObjectMapper json, Clock clock, Duration backoff) {
        this.connections = connections;
        this.monitoring = monitoring;
        this.secrets = secrets;
        this.sender = sender;
        this.json = json;
        this.clock = clock;
        this.backoff = backoff;
    }

    // ---- rules -----------------------------------------------------------------------------------------------------

    public Rule saveRule(Rule rule) {
        if (rule.name() == null || rule.name().isBlank()) {
            throw new IllegalArgumentException("A rule needs a name");
        }
        UUID id = rule.ruleId() == null ? UUID.randomUUID() : rule.ruleId();
        try (var c = connections.open();
                var s = c.prepareStatement("insert into alert_rule(rule_id,name,metric,comparison,threshold,severity,"
                        + "state_code,source_system,enabled) values(?,?,?,?,?,?,?,?,?) on conflict (rule_id) do update"
                        + " set name=excluded.name,metric=excluded.metric,comparison=excluded.comparison,"
                        + "threshold=excluded.threshold,severity=excluded.severity,state_code=excluded.state_code,"
                        + "source_system=excluded.source_system,enabled=excluded.enabled")) {
            s.setObject(1, id);
            s.setString(2, rule.name().strip());
            s.setString(3, rule.metric().name());
            s.setString(4, rule.comparison());
            s.setDouble(5, rule.threshold());
            s.setString(6, rule.severity());
            s.setString(7, blankToNull(rule.stateCode()));
            s.setString(8, blankToNull(rule.sourceSystem()));
            s.setBoolean(9, rule.enabled());
            s.executeUpdate();
            return new Rule(id, rule.name().strip(), rule.metric(), rule.comparison(), rule.threshold(),
                    rule.severity(), blankToNull(rule.stateCode()), blankToNull(rule.sourceSystem()), rule.enabled());
        } catch (java.sql.SQLException e) {
            throw new IllegalArgumentException("Invalid alert rule: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot store the alert rule", e);
        }
    }

    public void deleteRule(UUID ruleId) {
        update("delete from alert_rule where rule_id=?", ruleId);
    }

    public List<Rule> rules() {
        List<Rule> rows = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select rule_id,name,metric,comparison,threshold,severity,state_code,"
                        + "source_system,enabled from alert_rule order by name")) {
            var r = s.executeQuery();
            while (r.next()) {
                rows.add(new Rule(r.getObject(1, UUID.class), r.getString(2), Metric.valueOf(r.getString(3)),
                        r.getString(4), r.getDouble(5), r.getString(6), r.getString(7), r.getString(8), r.getBoolean(9)));
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the alert rules", e);
        }
    }

    // ---- evaluation ------------------------------------------------------------------------------------------------

    /** Checks every enabled rule against every source; returns the notifications created. */
    public int evaluate() {
        int created = 0;
        boolean webhook = secrets.get(URL_SECRET).isPresent();
        Map<Metric, Double> platform = monitoring.platformMetrics();
        for (Rule rule : rules()) {
            if (!rule.enabled()) {
                continue;
            }
            if (rule.metric().platformWide()) {
                Double observed = platform.get(rule.metric());
                if (observed != null && breached(rule, observed) && !hasUnread(rule, MonitoringService.PLATFORM)) {
                    UUID notification = notify(rule, MonitoringService.PLATFORM, observed);
                    if (webhook) {
                        queueWebhook(notification);
                    }
                    created++;
                }
                continue;
            }
            for (var source : monitoring.sources(null)) {
                if ((rule.stateCode() != null && !rule.stateCode().equals(source.stateCode()))
                        || (rule.sourceSystem() != null && !rule.sourceSystem().equals(source.sourceSystem()))) {
                    continue;
                }
                Double observed = monitoring.metrics(source.stateCode(), source.sourceSystem()).get(rule.metric());
                if (observed == null || !breached(rule, observed) || hasUnread(rule, source)) {
                    continue;
                }
                UUID notification = notify(rule, source, observed);
                if (webhook) {
                    queueWebhook(notification);
                }
                created++;
            }
        }
        return created;
    }

    private static boolean breached(Rule rule, double observed) {
        return "GT".equals(rule.comparison()) ? observed > rule.threshold() : observed < rule.threshold();
    }

    private boolean hasUnread(Rule rule, MonitoringService.SourceKey source) {
        try (var c = connections.open();
                var s = c.prepareStatement("select 1 from alert_notification where rule_id=? and state_code=? and"
                        + " source_system=? and read_at is null limit 1")) {
            s.setObject(1, rule.ruleId());
            s.setString(2, source.stateCode());
            s.setString(3, source.sourceSystem());
            return s.executeQuery().next();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot look for an open alert", e);
        }
    }

    private UUID notify(Rule rule, MonitoringService.SourceKey source, double observed) {
        UUID id = UUID.randomUUID();
        String message = rule.name() + ": " + rule.metric() + " is " + round(observed) + " ("
                + ("GT".equals(rule.comparison()) ? "above " : "below ") + round(rule.threshold()) + ") for "
                + source.stateCode() + "/" + source.sourceSystem();
        try (var c = connections.open();
                var s = c.prepareStatement("insert into alert_notification(notification_id,rule_id,state_code,"
                        + "source_system,metric,observed,threshold,severity,message,created_at)"
                        + " values(?,?,?,?,?,?,?,?,?,?)")) {
            s.setObject(1, id);
            s.setObject(2, rule.ruleId());
            s.setString(3, source.stateCode());
            s.setString(4, source.sourceSystem());
            s.setString(5, rule.metric().name());
            s.setDouble(6, observed);
            s.setDouble(7, rule.threshold());
            s.setString(8, rule.severity());
            s.setString(9, message);
            s.setTimestamp(10, Timestamp.from(clock.instant()));
            s.executeUpdate();
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot store the notification", e);
        }
    }

    private void queueWebhook(UUID notification) {
        try (var c = connections.open();
                var s = c.prepareStatement("insert into webhook_delivery(notification_id,status,next_attempt_at)"
                        + " values(?,'PENDING',?)")) {
            s.setObject(1, notification);
            s.setTimestamp(2, Timestamp.from(clock.instant()));
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot queue the webhook", e);
        }
    }

    // ---- webhook ---------------------------------------------------------------------------------------------------

    public void saveWebhook(String url, String signingKey) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            throw new IllegalArgumentException("The webhook URL must be http or https");
        }
        if (signingKey == null || signingKey.length() < 16) {
            throw new IllegalArgumentException("The signing key must be at least 16 characters");
        }
        secrets.put(URL_SECRET, url.strip());
        secrets.put(SIGNING_SECRET, signingKey);
    }

    public boolean webhookConfigured() {
        return secrets.get(URL_SECRET).isPresent() && secrets.get(SIGNING_SECRET).isPresent();
    }

    /** Sends the deliveries that are due; a failure schedules a retry, and the fifth failure ends the delivery. */
    public int deliverDue() {
        var url = secrets.get(URL_SECRET);
        var key = secrets.get(SIGNING_SECRET);
        if (url.isEmpty() || key.isEmpty()) {
            return 0;
        }
        record Due(UUID delivery, int attempts, Map<String, Object> payload) { }
        List<Due> due = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select d.delivery_id,d.attempts,n.notification_id,r.name,n.metric,n.observed,"
                        + "n.threshold,n.severity,n.state_code,n.source_system,n.message,n.created_at from webhook_delivery d"
                        + " join alert_notification n on n.notification_id=d.notification_id"
                        + " join alert_rule r on r.rule_id=n.rule_id where d.status='PENDING' and d.next_attempt_at <= ?"
                        + " order by d.next_attempt_at")) {
            s.setTimestamp(1, Timestamp.from(clock.instant()));
            var r = s.executeQuery();
            while (r.next()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("notificationId", r.getObject(3).toString());
                payload.put("rule", r.getString(4));
                payload.put("metric", r.getString(5));
                payload.put("observed", r.getDouble(6));
                payload.put("threshold", r.getDouble(7));
                payload.put("severity", r.getString(8));
                payload.put("stateCode", r.getString(9));
                payload.put("sourceSystem", r.getString(10));
                payload.put("message", r.getString(11));
                payload.put("createdAt", r.getTimestamp(12).toInstant().toString());
                due.add(new Due(r.getObject(1, UUID.class), r.getInt(2), payload));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the webhook queue", e);
        }
        int delivered = 0;
        for (Due item : due) {
            try {
                String body = json.writeValueAsString(item.payload());
                String timestamp = Long.toString(clock.instant().getEpochSecond());
                sender.send(url.get(), body, Map.of("Content-Type", "application/json", "X-UDDP-Timestamp", timestamp,
                        "X-UDDP-Signature", "sha256=" + sign(key.get(), timestamp + "." + body)));
                finish(item.delivery(), "DELIVERED", item.attempts() + 1, null, clock.instant());
                delivered++;
            } catch (Exception failure) {
                int attempts = item.attempts() + 1;
                String error = SecretRedactor.redact(String.valueOf(failure.getMessage()),
                        List.of(url.get(), key.get()));
                if (attempts >= MAX_ATTEMPTS) {
                    finish(item.delivery(), "FAILED", attempts, error, clock.instant());
                } else {
                    finish(item.delivery(), "PENDING", attempts, error,
                            clock.instant().plus(backoff.multipliedBy(1L << (attempts - 1))));
                }
            }
        }
        return delivered;
    }

    private void finish(UUID delivery, String status, int attempts, String error, Instant next) {
        try (var c = connections.open();
                var s = c.prepareStatement("update webhook_delivery set status=?,attempts=?,last_error=?,"
                        + "next_attempt_at=?,delivered_at=case when cast(? as text)='DELIVERED' then cast(? as timestamptz) else null end where delivery_id=?")) {
            s.setString(1, status);
            s.setInt(2, attempts);
            s.setString(3, error);
            s.setTimestamp(4, Timestamp.from(next));
            s.setString(5, status);
            s.setTimestamp(6, Timestamp.from(clock.instant()));
            s.setObject(7, delivery);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record the webhook attempt", e);
        }
    }

    static String sign(String key, String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- notifications ---------------------------------------------------------------------------------------------

    public List<Map<String, Object>> notifications(boolean unreadOnly, int limit, Predicate<String> stateAllowed) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select notification_id,state_code,source_system,metric,observed,threshold,"
                        + "severity,message,created_at,read_at from alert_notification"
                        + (unreadOnly ? " where read_at is null" : "") + " order by created_at desc limit ?")) {
            s.setInt(1, Math.max(1, Math.min(limit, 200)));
            var r = s.executeQuery();
            while (r.next()) {
                if (!stateAllowed.test(r.getString(2))) {
                    continue;
                }
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("notificationId", r.getObject(1));
                x.put("stateCode", r.getString(2));
                x.put("sourceSystem", r.getString(3));
                x.put("metric", r.getString(4));
                x.put("observed", r.getDouble(5));
                x.put("threshold", r.getDouble(6));
                x.put("severity", r.getString(7));
                x.put("message", r.getString(8));
                x.put("createdAt", r.getObject(9));
                x.put("readAt", r.getObject(10));
                rows.add(x);
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the notifications", e);
        }
    }

    public void markRead(UUID notificationId) {
        update("update alert_notification set read_at=now() where notification_id=? and read_at is null", notificationId);
    }

    private void update(String sql, UUID id) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot update the alert data", e);
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
