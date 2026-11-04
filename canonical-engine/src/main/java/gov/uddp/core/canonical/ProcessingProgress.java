package gov.uddp.core.canonical;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Where chunked canonical processing of a batch has got to (V105): one row per committed chunk plus the batch's last
 * committed chunk, which is the resume point. A chunk's row is written in the same transaction as its work, so a chunk
 * is either fully there with its row or not there at all.
 */
@Component
public class ProcessingProgress {
    /** Rows read, mapped, skipped, failed (rejected) and held by one chunk, or the sum of several. */
    public record Counts(int read, int mapped, int skipped, int failed, int held) {
        public static final Counts NONE = new Counts(0, 0, 0, 0, 0);

        public Counts(int read, int mapped, int skipped, int failed) {
            this(read, mapped, skipped, failed, 0);
        }

        public Counts plus(Counts other) {
            return new Counts(read + other.read, mapped + other.mapped, skipped + other.skipped, failed + other.failed,
                    held + other.held);
        }
    }

    public record Chunk(int number, Counts counts, OffsetDateTime startedAt, OffsetDateTime completedAt) {
        public double seconds() {
            return Math.max(0.001, java.time.Duration.between(startedAt, completedAt).toMillis() / 1000.0);
        }
    }

    /** A row processing rejected, with its original payload unless that was purged. */
    /** {@code sourceLine} is the line of the delivered file (V111; null for older rows and database sources). */
    public record Rejected(UUID rawRecordId, String reason, OffsetDateTime occurredAt, String rawPayload,
                           Integer sourceLine) {
    }

    private final PostgresConnectionFactory connections;

    public ProcessingProgress(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    /** Runs {@code work} in one transaction. */
    public <T> T inTransaction(Supplier<T> work) {
        return connections.inTransaction(work);
    }

    /**
     * Starts or resumes processing of the batch and returns the number of chunks already committed. A batch whose
     * processing COMPLETED starts again from chunk 0 (it is being re-processed); one left RUNNING resumes after its
     * last committed chunk, but only when it was chunked the same size.
     */
    public int begin(UUID batch, int chunkSize) {
        try (var c = connections.open()) {
            try (var s = c.prepareStatement("select last_committed_chunk,status,chunk_size"
                    + " from canonical_processing_progress where batch_id=?")) {
                s.setObject(1, batch);
                var r = s.executeQuery();
                if (r.next() && "RUNNING".equals(r.getString(2)) && r.getInt(3) == chunkSize) {
                    return r.getInt(1);
                }
            }
            try (var s = c.prepareStatement("delete from canonical_processing_chunk where batch_id=?")) {
                s.setObject(1, batch);
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("insert into canonical_processing_progress(batch_id,chunk_size,status)"
                    + " values(?,?,'RUNNING') on conflict (batch_id) do update set chunk_size=excluded.chunk_size,"
                    + "last_committed_chunk=0,status='RUNNING',started_at=now(),updated_at=now()")) {
                s.setObject(1, batch);
                s.setInt(2, chunkSize);
                s.executeUpdate();
            }
            return 0;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start processing progress", e);
        }
    }

    /** Records a chunk as committed; call it inside the chunk's transaction. */
    public void commit(UUID batch, int number, Counts counts, OffsetDateTime startedAt) {
        try (var c = connections.open()) {
            try (var s = c.prepareStatement("insert into canonical_processing_chunk(batch_id,chunk_number,rows_read,"
                    + "rows_mapped,rows_skipped,rows_failed,rows_held,started_at,completed_at)"
                    + " values(?,?,?,?,?,?,?,?,now())")) {
                s.setObject(1, batch);
                s.setInt(2, number);
                s.setInt(3, counts.read());
                s.setInt(4, counts.mapped());
                s.setInt(5, counts.skipped());
                s.setInt(6, counts.failed());
                s.setInt(7, counts.held());
                s.setObject(8, startedAt);
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("update canonical_processing_progress set last_committed_chunk=?,"
                    + "updated_at=now() where batch_id=?")) {
                s.setInt(1, number);
                s.setObject(2, batch);
                s.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record committed chunk", e);
        }
    }

    public void complete(UUID batch) {
        try (var c = connections.open();
                var s = c.prepareStatement("update canonical_processing_progress set status='COMPLETED',updated_at=now()"
                        + " where batch_id=?")) {
            s.setObject(1, batch);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot complete processing progress", e);
        }
    }

    /** The sum of the batch's committed chunks. */
    public Counts totals(UUID batch) {
        return chunks(batch).stream().map(Chunk::counts).reduce(Counts.NONE, Counts::plus);
    }

    public List<Chunk> chunks(UUID batch) {
        List<Chunk> chunks = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select chunk_number,rows_read,rows_mapped,rows_skipped,rows_failed,rows_held,"
                        + "started_at,completed_at from canonical_processing_chunk where batch_id=? order by chunk_number")) {
            s.setObject(1, batch);
            var r = s.executeQuery();
            while (r.next()) {
                chunks.add(new Chunk(r.getInt(1),
                        new Counts(r.getInt(2), r.getInt(3), r.getInt(4), r.getInt(5), r.getInt(6)),
                        r.getObject(7, OffsetDateTime.class), r.getObject(8, OffsetDateTime.class)));
            }
            return chunks;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read processing progress", e);
        }
    }

    /** Empty when processing of the batch never started; otherwise whether it has finished. */
    public Optional<Boolean> completed(UUID batch) {
        try (var c = connections.open();
                var s = c.prepareStatement("select status from canonical_processing_progress where batch_id=?")) {
            s.setObject(1, batch);
            var r = s.executeQuery();
            return r.next() ? Optional.of("COMPLETED".equals(r.getString(1))) : Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read processing progress", e);
        }
    }

    /** Streams the rows of the batch that processing rejected, for the rejected-row download. */
    public void forEachRejected(UUID batch, Consumer<Rejected> consumer) {
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("select e.raw_record_id,e.error_message,e.occurred_at,"
                    + "case when r.payload_purged_at is null then r.raw_payload end, r.source_line"
                    + " from canonical_processing_error e join raw_record r on r.raw_record_id=e.raw_record_id"
                    + " where e.batch_id=? order by e.occurred_at,e.raw_record_id")) {
                s.setFetchSize(1000);
                s.setObject(1, batch);
                var r = s.executeQuery();
                while (r.next()) {
                    consumer.accept(new Rejected(r.getObject(1, UUID.class), r.getString(2),
                            r.getObject(3, OffsetDateTime.class), r.getString(4), (Integer) r.getObject(5)));
                }
            } finally {
                c.rollback();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read rejected rows", e);
        }
    }

    public int rejectedCount(UUID batch) {
        try (var c = connections.open();
                var s = c.prepareStatement("select count(*) from canonical_processing_error where batch_id=?")) {
            s.setObject(1, batch);
            var r = s.executeQuery();
            r.next();
            return r.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot count rejected rows", e);
        }
    }
}
