package gov.uddp.core.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.connector.ConnectorSecrets;
import gov.uddp.core.monitoring.AlertService.Rule;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Track C6: a breach raises one alert, the signed webhook is retried, and no secret is ever in a payload. */
class AlertServiceTest {
    private static final String SIGNING_KEY = "signing-key-0123456789";
    private static final String WEBHOOK_URL = "https://hooks.example.test/token-abc-123";

    private static final class MovingClock extends Clock {
        private Instant now = Instant.now();

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class MemorySecrets implements ConnectorSecrets {
        final Map<String, String> values = new HashMap<>();

        @Override
        public Optional<String> get(String name) {
            return Optional.ofNullable(values.get(name));
        }

        @Override
        public void put(String name, String value) {
            values.put(name, value);
        }
    }

    private record Sent(String url, String body, Map<String, String> headers) {
    }

    @Test
    void breachRaisesOneAlertAndTheSignedWebhookIsRetriedWithoutLeakingSecrets() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var clock = new MovingClock();
        var secrets = new MemorySecrets();
        List<Sent> sent = new ArrayList<>();
        int[] failuresLeft = {2};
        AlertService.WebhookSender sender = (url, body, headers) -> {
            sent.add(new Sent(url, body, headers));
            if (failuresLeft[0]-- > 0) {
                throw new IOException("connection refused to " + url + " using " + SIGNING_KEY);
            }
        };
        var monitoring = new MonitoringService(connections, SampleData.loader(SampleData.STATE_PACKS), List.of(), clock);
        var alerts = new AlertService(connections, monitoring, secrets, sender, new ObjectMapper(), clock,
                Duration.ofMinutes(1));
        alerts.saveRule(new Rule(null, "Load failures", MonitoringService.Metric.LOAD_FAILURES, "GT", 1, "CRITICAL",
                null, null, true));

        failedBatch(connections);
        assertEquals(0, alerts.evaluate(), "one failure is within the threshold");
        failedBatch(connections);
        assertEquals(1, alerts.evaluate(), "the second failure breaches it");
        assertEquals(0, alerts.evaluate(), "an unread alert is not raised again");
        var alert = alerts.notifications(true, 10, state -> true).getFirst();
        assertEquals("CRITICAL", alert.get("severity"));
        assertEquals(2.0, (double) alert.get("observed"));
        assertEquals(0, sql.count("select count(*) from webhook_delivery"), "no webhook is configured yet");

        alerts.markRead((UUID) alert.get("notificationId"));
        alerts.saveWebhook(WEBHOOK_URL, SIGNING_KEY);
        assertEquals(1, alerts.evaluate(), "the breach persists, so a new alert follows the read one");
        assertEquals(1, sql.count("select count(*) from webhook_delivery where status='PENDING'"));

        assertEquals(0, alerts.deliverDue());
        assertEquals(1, sent.size(), "first attempt fails");
        clock.advance(Duration.ofSeconds(30));
        alerts.deliverDue();
        assertEquals(1, sent.size(), "not due before the backoff has passed");
        clock.advance(Duration.ofSeconds(31));
        alerts.deliverDue();
        assertEquals(2, sent.size(), "second attempt after one minute fails");
        clock.advance(Duration.ofMinutes(2));
        assertEquals(1, alerts.deliverDue(), "third attempt succeeds");
        assertEquals(3, sent.size());
        assertEquals(1, sql.count("select count(*) from webhook_delivery where status='DELIVERED' and attempts=3"));

        // The payload is signed and carries no URL, key or credential.
        Sent last = sent.getLast();
        assertEquals(WEBHOOK_URL, last.url());
        assertFalse(last.body().contains(SIGNING_KEY));
        assertFalse(last.body().contains("token-abc-123"));
        assertFalse(last.body().toLowerCase().contains("password"));
        assertTrue(last.body().contains("\"metric\":\"LOAD_FAILURES\""));
        String timestamp = last.headers().get("X-UDDP-Timestamp");
        assertEquals("sha256=" + AlertService.sign(SIGNING_KEY, timestamp + "." + last.body()),
                last.headers().get("X-UDDP-Signature"));
        assertFalse(last.headers().values().stream().anyMatch(v -> v.contains(SIGNING_KEY)));
        // A failed attempt stores its error without the URL or the key.
        assertEquals(0, sql.count("select count(*) from webhook_delivery where last_error like '%token-abc%'"
                + " or last_error like '%" + SIGNING_KEY + "%'"));
    }

    private static void failedBatch(gov.uddp.core.canonical.PostgresConnectionFactory connections) throws Exception {
        try (var c = connections.open();
                var s = c.prepareStatement("insert into ingestion_batch(batch_id,state_code,source_system,source_type,"
                        + "state_pack_version,pipeline_template,status) values(?,'ST1','pds','file','1.0.0','t','FAILED')")) {
            s.setObject(1, UUID.randomUUID());
            s.executeUpdate();
        }
    }
}
