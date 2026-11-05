package gov.uddp.core.events;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.contracts.StatePackClient;
import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.onboarding.StatePackLifecycleService;
import gov.uddp.core.statepack.DatabaseStatePackRepository;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.messaging.KafkaEventLoop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Installs the State Pack versions statepack-service activates (MSA): each {@code statepack.pack-activated} is fetched
 * by its digest, verified, loaded by the existing {@link StatePackLoader}, stored as the edge's pack document and
 * registered ACTIVE (the previously ACTIVE version of the pack retired), so onboarding runs use exactly the approved
 * version. Installing a version that is already there changes nothing.
 */
@Component
@ConditionalOnExpression("'${uddp.statepack.url:}' != '' and '${uddp.events.kafka.bootstrap-servers:}' != ''")
public class RemoteStatePackMessaging implements DisposableBean {

    static final String CONSUMER = "edge-statepack-installer";

    private final KafkaProducer<String, String> deadLetters;
    private final KafkaEventLoop loop;
    private final Thread thread;

    public RemoteStatePackMessaging(PostgresConnectionFactory connections, StatePackLoader packs,
                                    DatabaseStatePackRepository documents, StatePackLifecycleService lifecycle,
                                    @Value("${uddp.statepack.url}") String url,
                                    @Value("${uddp.events.kafka.bootstrap-servers}") String bootstrapServers) {
        var installer = new Installer(new StatePackClient(url), packs, documents, lifecycle, connections);
        this.deadLetters = new KafkaProducer<>(Map.of("bootstrap.servers", bootstrapServers, "acks", "all"),
                new StringSerializer(), new StringSerializer());
        this.loop = new KafkaEventLoop(bootstrapServers, CONSUMER, connections::open,
                Map.of(Topics.PACK_ACTIVATED, installer::activated), deadLetters);
        this.thread = new Thread(loop, "uddp-statepack-installer");
        this.thread.setDaemon(true);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        thread.start();
    }

    @Override
    public void destroy() {
        loop.close();
        deadLetters.close();
    }

    /** The installation itself, separate from Kafka so it can be proven on its own. */
    public record Installer(StatePackClient client, StatePackLoader packs, DatabaseStatePackRepository documents,
                            StatePackLifecycleService lifecycle, PostgresConnectionFactory connections) {

        public void activated(Connection ignored, JsonNode envelope) throws Exception {
            JsonNode payload = envelope.path("payload");
            try {
                install(payload.path("packId").asText(), payload.path("version").asText(),
                        payload.path("digest").asText(null));
            } catch (IllegalStateException e) {
                // statepack-service is not reachable (yet): wait for it instead of parking the event.
                if (unreachable(e)) {
                    throw new gov.uddp.messaging.Inbox.NotYetApplicable("statepack-service is not reachable: "
                            + e.getMessage());
                }
                throw e;
            }
        }

        private static boolean unreachable(Throwable e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                String message = String.valueOf(t.getMessage());
                if (t instanceof java.net.ConnectException || message.startsWith("Cannot reach statepack-service")
                        || message.startsWith("No instance of") || message.startsWith("No service registry")) {
                    return true;
                }
            }
            return false;
        }

        public LoadedStatePack install(String packId, String version, String digest) throws Exception {
            var pack = client.fetch(packId, version, digest);
            Path cache = Files.createTempDirectory("uddp-statepack-");
            LoadedStatePack loaded;
            try {
                loaded = packs.load(StatePackClient.materialize(pack, cache));
            } finally {
                try (var files = Files.walk(cache)) {
                    files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                }
            }
            documents.save(loaded.manifest().packId(), loaded);
            String previous = activeVersion(packId);
            if (previous != null && !previous.equals(version)) {
                lifecycle.transition(packId, previous, "RETIRED");
            }
            lifecycle.register(packId);
            String status = status(packId, version);
            if ("DRAFT".equals(status)) {
                lifecycle.transition(packId, version, "VALIDATED");
                status = "VALIDATED";
            }
            if ("VALIDATED".equals(status)) {
                lifecycle.transition(packId, version, "ACTIVE");
            }
            return loaded;
        }

        private String activeVersion(String packId) throws Exception {
            try (var c = connections.open(); var s = c.prepareStatement(
                    "select version from state_pack_lifecycle where pack_id = ? and status = 'ACTIVE'")) {
                s.setString(1, packId);
                try (var r = s.executeQuery()) {
                    return r.next() ? r.getString(1) : null;
                }
            }
        }

        private String status(String packId, String version) throws Exception {
            try (var c = connections.open(); var s = c.prepareStatement(
                    "select status from state_pack_lifecycle where pack_id = ? and version = ?")) {
                s.setString(1, packId);
                s.setString(2, version);
                try (var r = s.executeQuery()) {
                    return r.next() ? r.getString(1) : null;
                }
            }
        }
    }
}
