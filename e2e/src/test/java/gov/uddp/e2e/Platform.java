package gov.uddp.e2e;

import gov.uddp.ingestion.HopExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Starts the five pipeline services in this JVM, each with its own database and schema (as in docs/msa-design.md,
 * section 6) on one Postgres container, sharing one Kafka. Every setting is passed explicitly (no application.yml,
 * because the services' files would collide on one classpath). Hop is replaced by a fixture that lands each CSV data
 * line exactly as generic-file-ingest.hpl does; everything else is the services' production code.
 */
final class Platform implements AutoCloseable {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    final PostgreSQLContainer<?> postgres;
    final KafkaContainer kafka;
    final List<ConfigurableApplicationContext> services = new ArrayList<>();
    ConfigurableApplicationContext statepack;
    ConfigurableApplicationContext ingestion;
    ConfigurableApplicationContext canonical;
    ConfigurableApplicationContext identity;
    ConfigurableApplicationContext registry;

    Platform(PostgreSQLContainer<?> postgres, KafkaContainer kafka) {
        this.postgres = postgres;
        this.kafka = kafka;
    }

    void start(Path work) throws Exception {
        try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var s = c.createStatement()) {
            for (String db : List.of("uddp_raw", "uddp_identity", "uddp_registry", "uddp_platform")) {
                s.execute("create database " + db);
            }
        }
        statepack = start(gov.uddp.statepack.StatepackServiceApplication.class, "statepack-service", "uddp_platform",
                "statepack", "classpath:db/statepack", Map.of(
                        "uddp.studio.repository-root", ROOT.toString(),
                        "uddp.studio.workspace-directory", work.resolve("statepack-studio").toString(),
                        "uddp.studio.settings-directory", work.resolve("statepack-settings").toString(),
                        "uddp.shaping.plugins-dir", work.resolve("plugins").toString(),
                        "uddp.shaping.approved-plugins-file",
                        ROOT.resolve("platform/shaping/approved-plugins.yaml").toString()));
        String statepackUrl = "http://127.0.0.1:" + port(statepack);
        ingestion = start(gov.uddp.ingestion.IngestionServiceApplication.class, "ingestion-service", "uddp_raw",
                "ingestion", "classpath:db/ingestion", Map.of("uddp.ingestion.repository-root", ROOT.toString()),
                ctx -> ctx.registerBean("fixtureHop", HopExecutor.class, this::fixtureHop,
                        bd -> bd.setPrimary(true)));
        canonical = start(gov.uddp.canonical.CanonicalServiceApplication.class, "canonical-service", "uddp_platform",
                "canonical", "classpath:db/canonical", Map.of(
                        "uddp.canonical.statepack-url", statepackUrl,
                        "uddp.canonical.pack-cache", work.resolve("canonical-packs").toString(),
                        "uddp.canonical.platform-directory", ROOT.resolve("platform").toString(),
                        "uddp.platform-directory", ROOT.resolve("platform").toString(),
                        "uddp.canonical.shaping-plugins-dir", work.resolve("plugins").toString(),
                        "uddp.canonical.shaping-approved-plugins",
                        ROOT.resolve("platform/shaping/approved-plugins.yaml").toString(),
                        "uddp.canonical.batch-wait", "PT5M"));
        identity = start(gov.uddp.identity.IdentityServiceApplication.class, "identity-service", "uddp_identity",
                "identity", "classpath:db/identity", Map.of(
                        "uddp.identity.repository-root", ROOT.toString(),
                        "uddp.identity.statepack-url", statepackUrl,
                        "uddp.identity.python", "python"));
        registry = start(gov.uddp.registry.RegistryServiceApplication.class, "registry-service", "uddp_registry",
                "registry,lineage", "classpath:db/registry", Map.of(
                        "uddp.registry.platform-directory", ROOT.resolve("platform").toString(),
                        "uddp.registry.state-pack-directory", ROOT.resolve("services/statepack-service/seed/state-packs").toString(),
                        "uddp.registry.build-wait", "PT5M"));
    }

    /** Set once ingestion-service has started; the fixture lands rows through it. */
    private volatile JdbcTemplate ingestionJdbc;

    @SafeVarargs
    private ConfigurableApplicationContext start(Class<?> app, String name, String database, String schemas,
                                                 String locations, Map<String, String> extra,
                                                 java.util.function.Consumer<GenericApplicationContext>... beans) {
        String first = schemas.split(",")[0];
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        Map<String, Object> p = new HashMap<>();
        p.put("spring.config.name", "e2e-none");
        p.put("spring.application.name", name);
        p.put("spring.datasource.url", url);
        p.put("spring.datasource.username", postgres.getUsername());
        p.put("spring.datasource.password", postgres.getPassword());
        p.put("spring.datasource.hikari.schema", first);
        p.put("spring.flyway.locations", locations);
        p.put("spring.flyway.schemas", schemas);
        p.put("spring.flyway.default-schema", first);
        p.put("spring.flyway.create-schemas", "true");
        p.put("server.address", "127.0.0.1");
        p.put("server.port", "0");
        p.put("spring.jmx.enabled", "false");
        // Internal APIs require the service token, as in a deployment (the services send it to each other).
        p.put("uddp.internal.token", "e2e-service-token");
        // The gateway's jars are on this JVM's classpath (DiscoveryAndGatewayTest); these services are not gateways.
        p.put("spring.cloud.gateway.server.webflux.enabled", "false");
        p.put("uddp.kafka.bootstrap-servers", kafka.getBootstrapServers());
        p.putAll(extra);
        var builder = new SpringApplicationBuilder(app).properties(p);
        for (var bean : beans) {
            builder.initializers(ctx -> bean.accept((GenericApplicationContext) ctx));
        }
        ConfigurableApplicationContext context = builder.run();
        if (name.equals("ingestion-service")) {
            ingestionJdbc = new JdbcTemplate(context.getBean(DataSource.class));
        }
        services.add(context);
        return context;
    }

    static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    static JdbcTemplate jdbc(ConfigurableApplicationContext context) {
        return new JdbcTemplate(context.getBean(DataSource.class));
    }

    /** Lands each data line of SOURCE_FILE as generic-file-ingest.hpl does (Hop runs only outside unit tests). */
    private HopExecutor fixtureHop() {
        return (template, p) -> {
            try {
                JdbcTemplate jdbc = ingestionJdbc;
                List<String> lines = Files.readAllLines(Path.of(p.get("SOURCE_FILE")), StandardCharsets.UTF_8);
                for (int i = 1; i < lines.size(); i++) {
                    if (lines.get(i).isBlank()) {
                        continue;
                    }
                    jdbc.update("insert into raw_record (batch_id, state_code, source_system, raw_payload,"
                            + " source_metadata, state_pack_version, source_schema_version, pipeline_template,"
                            + " source_line) values (?::uuid, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                            p.get("BATCH_ID"), p.get("STATE_CODE"), p.get("SOURCE_SYSTEM"),
                            "{\"csvHeader\":\"" + p.get("CSV_HEADER") + "\",\"rawLine\":\"" + lines.get(i) + "\"}",
                            "{\"sourceEntity\":\"" + p.get("SOURCE_ENTITY") + "\"}", p.get("STATE_PACK_VERSION"),
                            p.get("SOURCE_SCHEMA_VERSION"), template, i + 1);
                }
                return new HopExecutor.Result(0, "landed");
            } catch (Exception e) {
                return new HopExecutor.Result(1, String.valueOf(e.getMessage()));
            }
        };
    }

    @Override
    public void close() {
        for (int i = services.size() - 1; i >= 0; i--) {
            services.get(i).close();
        }
    }
}
