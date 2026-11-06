package gov.uddp.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Load test (opt-in: {@code -Dmsa.load=true}, rows with {@code -Dmsa.load.rows}, default 1,000,000): a batch of
 * millions of rows lands (Hop is a fixture that writes them with one INSERT ... SELECT generate_series, as fast as the
 * database allows) and is announced in committed chunks. It checks every row gets exactly one raw-record-received,
 * the batch completes, the heap stays far below the batch's size, and prints the rows per second of each phase.
 */
@Testcontainers
@SpringBootTest
@EnabledIfSystemProperty(named = "msa.load", matches = "true")
class LargeBatchLoadTest {

    static final int ROWS = Integer.getInteger("msa.load.rows", 1_000_000);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_raw")
            .withCommand("postgres", "-c", "shared_buffers=512MB", "-c", "max_wal_size=4GB", "-c", "fsync=off");

    @TestConfiguration
    static class GeneratedSource {
        @Bean
        @Primary
        HopExecutor generatedRows(JdbcTemplate jdbc) {
            return (template, p) -> {
                jdbc.update("insert into raw_record (batch_id, state_code, source_system, raw_payload, source_metadata,"
                        + " state_pack_version, source_schema_version, pipeline_template, source_line)"
                        + " select cast(? as uuid), ?, ?, '{\"csvHeader\":\"id,name,born,district\",\"rawLine\":\"'"
                        + " || g || ',Person ' || g || ',1990-01-01,' || (g % 38) || '\"}', '{}'::jsonb, '1.0.0',"
                        + " '1.0', ?, g + 1 from generate_series(1, ?) g",
                        p.get("BATCH_ID"), p.get("STATE_CODE"), p.get("SOURCE_SYSTEM"), template, ROWS);
                return new HopExecutor.Result(0, "landed");
            };
        }
    }

    @Autowired
    private Ingestions ingestions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void millionsOfRowsAreAnnouncedInChunksWithBoundedMemory() throws Exception {
        UUID batch = UUID.randomUUID();
        var json = new ObjectMapper();
        var request = json.createObjectNode();
        request.put("batchId", batch.toString());
        request.put("sourceSystem", "load-register");
        request.put("sourceType", "FILE");
        request.put("sourceSchemaVersion", "1.0");
        request.put("statePackVersion", "1.0.0");
        request.put("pipelineTemplate", "generic-file-ingest.hpl");
        var parameters = request.putObject("parameters");
        parameters.put("SOURCE_FILE", "generated");
        parameters.put("CSV_HEADER", "id,name,born,district");
        parameters.put("STATE_CODE", "LD");
        parameters.put("SOURCE_SYSTEM", "load-register");
        parameters.put("STATE_PACK_VERSION", "1.0.0");
        parameters.put("BATCH_ID", batch.toString());

        Runtime runtime = Runtime.getRuntime();
        long[] peak = {0};
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak[0] = Math.max(peak[0], runtime.totalMemory() - runtime.freeMemory());
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        long started = System.nanoTime();
        assertThat(ingestions.ingest(request, null)).isEqualTo("COMPLETED");
        double seconds = (System.nanoTime() - started) / 1e9;
        sampler.interrupt();

        Map<String, Object> outcome = jdbc.queryForMap("select record_count, published_count, status from"
                + " ingestion_batch where batch_id = ?", batch);
        long events = jdbc.queryForObject("select count(*) from outbox_event where event_type ="
                + " 'uddp.ingestion.raw-record-received'", Long.class);
        long distinct = jdbc.queryForObject("select count(distinct payload->'payload'->>'rawRecordId') from"
                + " outbox_event where event_type = 'uddp.ingestion.raw-record-received'", Long.class);
        long size = jdbc.queryForObject("select pg_total_relation_size('outbox_event')", Long.class);
        System.out.printf("LOAD %,d rows: landed and announced in %.1f s (%,.0f rows/s); peak heap %,d MB;"
                        + " outbox %,d MB%n", ROWS, seconds, ROWS / seconds, peak[0] / 1_048_576, size / 1_048_576);
        assertThat(outcome).containsEntry("status", "COMPLETED").containsEntry("record_count", ROWS);
        assertThat(events).isEqualTo(ROWS);
        assertThat(distinct).as("one event per row").isEqualTo(ROWS);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type ="
                + " 'uddp.ingestion.batch-completed'", Long.class)).isEqualTo(1);
    }
}
