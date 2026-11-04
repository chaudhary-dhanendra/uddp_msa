package gov.uddp.core.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.canonical.RawRecordRepository;
import gov.uddp.core.canonical.SourceRecordKey;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.statepack.StatePackLoader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Compares a delivery with the previous state of the same source, per source-record key and content hash (Track C1).
 * A record is NEW (never seen), CHANGED (different content, or delivered again after being removed), UNCHANGED, or
 * REMOVED (delivered before, absent now). A delivery is treated as the complete current extract of its source.
 */
@Service
public class DeliveryChangeService {
    public enum RemovalHandling { MARK_ONLY, END_BENEFITS }

    /** The four counts of one delivery. */
    public record Counts(int newCount, int changedCount, int unchangedCount, int removedCount) {
        public Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("new", newCount);
            out.put("changed", changedCount);
            out.put("unchanged", unchangedCount);
            out.put("removed", removedCount);
            return out;
        }
    }

    private final PostgresConnectionFactory connections;
    private final RawRecordRepository raws;
    private final StatePackLoader packs;
    private final ObjectMapper json;
    private final RawCsvRecordParser parser;
    private final SourceRecordKey keys;

    public DeliveryChangeService(PostgresConnectionFactory connections, RawRecordRepository raws,
                                 StatePackLoader packs, ObjectMapper json) {
        this.connections = connections;
        this.raws = raws;
        this.packs = packs;
        this.json = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.parser = new RawCsvRecordParser(json);
        this.keys = new SourceRecordKey(parser);
    }

    public RemovalHandling removalHandling(String stateCode, String sourceSystem) {
        try (var c = connections.open();
                var s = c.prepareStatement("select removal_handling from source_delivery_policy"
                        + " where state_code=? and source_system=?")) {
            s.setString(1, stateCode);
            s.setString(2, sourceSystem);
            var r = s.executeQuery();
            return r.next() ? RemovalHandling.valueOf(r.getString(1)) : RemovalHandling.MARK_ONLY;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the delivery policy", e);
        }
    }

    public void setRemovalHandling(String stateCode, String sourceSystem, RemovalHandling handling) {
        try (var c = connections.open();
                var s = c.prepareStatement("insert into source_delivery_policy(state_code,source_system,removal_handling)"
                        + " values(?,?,?) on conflict (state_code,source_system) do update set"
                        + " removal_handling=excluded.removal_handling,updated_at=now()")) {
            s.setString(1, stateCode);
            s.setString(2, sourceSystem);
            s.setString(3, handling.name());
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot store the delivery policy", e);
        }
    }

    /** Compares the batch with the source's earlier deliveries; a batch that was already compared returns its counts. */
    public Counts evaluate(UUID batchId, UUID runId) {
        var existing = counts(batchId);
        if (existing.isPresent()) {
            return existing.get();
        }
        var records = raws.findByBatchId(batchId);
        if (records.isEmpty()) {
            return new Counts(0, 0, 0, 0);
        }
        String state = records.getFirst().stateCode();
        String source = records.getFirst().sourceSystem();
        var pack = packs.find(state).orElseThrow(() -> new IllegalStateException("State Pack no longer available"));
        RemovalHandling handling = removalHandling(state, source);
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            Map<String, String[]> known = new HashMap<>();
            try (var s = c.prepareStatement("select source_record_key,content_hash,status from source_record_state"
                    + " where state_code=? and source_system=?")) {
                s.setString(1, state);
                s.setString(2, source);
                var r = s.executeQuery();
                while (r.next()) {
                    known.put(r.getString(1), new String[] {r.getString(2), r.getString(3)});
                }
            }
            Set<String> delivered = new HashSet<>();
            int fresh = 0;
            int changed = 0;
            int unchanged = 0;
            for (var raw : records) {
                String key;
                String hash;
                try {
                    key = keys.of(raw, pack);
                    hash = hash(parser.parse(raw.rawPayload()));
                } catch (Exception unreadable) {
                    continue; // The mapping stage reports rows without a usable key.
                }
                if (!delivered.add(key)) {
                    continue; // Ambiguous duplicate key in one delivery; the mapping stage rejects it.
                }
                String[] before = known.get(key);
                if (before == null) {
                    fresh++;
                } else if (before[0].equals(hash) && "DELIVERED".equals(before[1])) {
                    unchanged++;
                } else {
                    changed++;
                }
                try (var s = c.prepareStatement("insert into source_record_state(source_record_key,state_code,"
                        + "source_system,content_hash,status,first_batch_id,last_batch_id) values(?,?,?,?,'DELIVERED',?,?)"
                        + " on conflict (source_record_key) do update set content_hash=excluded.content_hash,"
                        + "status='DELIVERED',last_batch_id=excluded.last_batch_id,removed_at=null,updated_at=now()")) {
                    s.setString(1, key);
                    s.setString(2, state);
                    s.setString(3, source);
                    s.setString(4, hash);
                    s.setObject(5, batchId);
                    s.setObject(6, batchId);
                    s.executeUpdate();
                }
            }
            int removed = 0;
            for (var entry : known.entrySet()) {
                if (!delivered.contains(entry.getKey()) && "DELIVERED".equals(entry.getValue()[1])) {
                    removed++;
                    try (var s = c.prepareStatement("update source_record_state set status='REMOVED',removed_at=now(),"
                            + "updated_at=now() where source_record_key=?")) {
                        s.setString(1, entry.getKey());
                        s.executeUpdate();
                    }
                    if (handling == RemovalHandling.END_BENEFITS) {
                        try (var s = c.prepareStatement("insert into benefit_end_marker(resident_record_key,batch_id)"
                                + " values(?,?)")) {
                            s.setString(1, entry.getKey());
                            s.setObject(2, batchId);
                            s.executeUpdate();
                        }
                    }
                }
            }
            try (var s = c.prepareStatement("insert into delivery_change_set(batch_id,onboarding_run_id,state_code,"
                    + "source_system,new_count,changed_count,unchanged_count,removed_count,removal_handling)"
                    + " values(?,?,?,?,?,?,?,?,?)")) {
                s.setObject(1, batchId);
                s.setObject(2, runId);
                s.setString(3, state);
                s.setString(4, source);
                s.setInt(5, fresh);
                s.setInt(6, changed);
                s.setInt(7, unchanged);
                s.setInt(8, removed);
                s.setString(9, handling.name());
                s.executeUpdate();
            }
            c.commit();
            return new Counts(fresh, changed, unchanged, removed);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compare the delivery with earlier deliveries", e);
        }
    }

    public Optional<Counts> counts(UUID batchId) {
        return query("select new_count,changed_count,unchanged_count,removed_count from delivery_change_set"
                + " where batch_id=?", batchId);
    }

    public Optional<Counts> countsForRun(UUID runId) {
        return query("select new_count,changed_count,unchanged_count,removed_count from delivery_change_set"
                + " where onboarding_run_id=? order by evaluated_at desc limit 1", runId);
    }

    private Optional<Counts> query(String sql, UUID id) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            var r = s.executeQuery();
            return r.next() ? Optional.of(new Counts(r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4)))
                    : Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the delivery change counts", e);
        }
    }

    private String hash(Map<String, Object> values) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(values).getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }
}
