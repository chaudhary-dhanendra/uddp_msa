package gov.uddp.statepack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import gov.uddp.contracts.EventEnvelope;
import gov.uddp.contracts.StatePackDigest;
import gov.uddp.contracts.StatePackRef;
import gov.uddp.contracts.Topics;
import gov.uddp.messaging.Outbox;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/**
 * Publish, maker-checker approval, activation and serving of State Pack versions. A published version is immutable and
 * identified by its content digest; the checker who approves or rejects must not be the maker who published; only an
 * APPROVED version can be activated, and activating it retires the pack's previously ACTIVE version. Every lifecycle
 * step writes its decision and its event (pack-published, -approved, -activated, -retired) in one transaction. The
 * service is generic: it reads only the manifest fields every pack has (packId, stateCode, version).
 */
@Service
public class StatePacks {

    /** Thrown for a request that breaks a lifecycle rule (HTTP 409). */
    public static class LifecycleException extends RuntimeException {
        public LifecycleException(String message) {
            super(message);
        }
    }

    /** A pack version as served. */
    public record PackVersion(String packId, String version, String stateCode, String digest, String status,
                              String maker, String checker, Map<String, String> files) {
    }

    private static final String PRODUCER = "statepack-service";

    private final DataSource dataSource;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public StatePacks(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Publishes a version (PENDING_APPROVAL); publishing the same content again returns the existing version. */
    public PackVersion publish(String maker, Map<String, String> uploaded) {
        if (maker == null || maker.isBlank()) {
            throw new LifecycleException("A maker is required");
        }
        Map<String, String> files = new LinkedHashMap<>();
        uploaded.forEach((path, content) -> files.put(path.replace('\\', '/'), content.replace("\r\n", "\n")));
        JsonNode manifest = manifest(files);
        String packId = required(manifest, "packId");
        String version = required(manifest, "version");
        String stateCode = required(manifest, "stateCode");
        String digest = StatePackDigest.of(files);
        return inTransaction(c -> {
            Optional<PackVersion> existing = find(c, packId, version);
            if (existing.isPresent()) {
                if (!existing.get().digest().equals(digest)) {
                    throw new LifecycleException(packId + " " + version + " is already published with other content;"
                            + " publish a new version");
                }
                return existing.get();
            }
            try (var s = c.prepareStatement("insert into state_pack_version (pack_id, version, state_code, digest,"
                    + " manifest, status, maker) values (?, ?, ?, ?, cast(? as jsonb), 'PENDING_APPROVAL', ?)")) {
                s.setString(1, packId);
                s.setString(2, version);
                s.setString(3, stateCode);
                s.setString(4, digest);
                s.setString(5, manifest.toString());
                s.setString(6, maker);
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("insert into state_pack_file (pack_id, version, path, content)"
                    + " values (?, ?, ?, ?)")) {
                for (var file : files.entrySet()) {
                    s.setString(1, packId);
                    s.setString(2, version);
                    s.setString(3, file.getKey());
                    s.setString(4, file.getValue());
                    s.addBatch();
                }
                s.executeBatch();
            }
            decide(c, packId, version, "PUBLISH", maker, null);
            event(c, Topics.PACK_PUBLISHED, "uddp.statepack.pack-published", packId, version, stateCode, digest, maker);
            return find(c, packId, version).orElseThrow();
        });
    }

    public PackVersion approve(String packId, String version, String checker) {
        return check(packId, version, checker, "APPROVED", "APPROVE", null);
    }

    public PackVersion reject(String packId, String version, String checker, String reason) {
        return check(packId, version, checker, "REJECTED", "REJECT", reason);
    }

    /** Activates an APPROVED version and retires the pack's previously ACTIVE one. */
    public PackVersion activate(String packId, String version, String actor) {
        return inTransaction(c -> {
            PackVersion pack = lock(c, packId, version);
            if (!"APPROVED".equals(pack.status())) {
                throw new LifecycleException(packId + " " + version + " is " + pack.status() + "; only an APPROVED"
                        + " version can be activated");
            }
            try (var s = c.prepareStatement("select version, digest from state_pack_version where pack_id = ?"
                    + " and status = 'ACTIVE' for update")) {
                s.setString(1, packId);
                try (var r = s.executeQuery()) {
                    if (r.next()) {
                        String previous = r.getString(1);
                        status(c, packId, previous, "RETIRED");
                        decide(c, packId, previous, "RETIRE", actor, "superseded by " + version);
                        event(c, Topics.PACK_RETIRED, "uddp.statepack.pack-retired", packId, previous,
                                pack.stateCode(), r.getString(2), actor);
                    }
                }
            }
            try (var s = c.prepareStatement("update state_pack_version set status = 'ACTIVE', activated_at = now()"
                    + " where pack_id = ? and version = ?")) {
                s.setString(1, packId);
                s.setString(2, version);
                s.executeUpdate();
            }
            decide(c, packId, version, "ACTIVATE", actor, null);
            event(c, Topics.PACK_ACTIVATED, "uddp.statepack.pack-activated", packId, version, pack.stateCode(),
                    pack.digest(), actor);
            return find(c, packId, version).orElseThrow();
        });
    }

    public Optional<PackVersion> get(String packId, String version) {
        return inTransaction(c -> find(c, packId, version));
    }

    /** The APPROVED or ACTIVE version of a state with this version number (what a raw record names). */
    public Optional<PackVersion> resolve(String stateCode, String version) {
        return inTransaction(c -> {
            try (var s = c.prepareStatement("select pack_id from state_pack_version where state_code = ?"
                    + " and version = ? and status in ('APPROVED', 'ACTIVE', 'RETIRED')"
                    + " order by (status = 'ACTIVE') desc limit 1")) {
                s.setString(1, stateCode);
                s.setString(2, version);
                try (var r = s.executeQuery()) {
                    return r.next() ? find(c, r.getString(1), version) : Optional.empty();
                }
            }
        });
    }

    public Optional<PackVersion> byDigest(String digest) {
        return inTransaction(c -> {
            try (var s = c.prepareStatement("select pack_id, version from state_pack_version where digest = ?")) {
                s.setString(1, digest);
                try (var r = s.executeQuery()) {
                    return r.next() ? find(c, r.getString(1), r.getString(2)) : Optional.empty();
                }
            }
        });
    }

    /** Pack versions without files, newest first; filters are optional. */
    public List<PackVersion> list(String stateCode, String status) {
        return inTransaction(c -> {
            var out = new java.util.ArrayList<PackVersion>();
            try (var s = c.prepareStatement("select pack_id, version, state_code, digest, status, maker, checker"
                    + " from state_pack_version where (cast(? as text) is null or state_code = ?) and (cast(? as text) is null or status = ?)"
                    + " order by pack_id, published_at desc")) {
                s.setString(1, stateCode);
                s.setString(2, stateCode);
                s.setString(3, status);
                s.setString(4, status);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        out.add(new PackVersion(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                                r.getString(5), r.getString(6), r.getString(7), Map.of()));
                    }
                }
            }
            return out;
        });
    }

    private PackVersion check(String packId, String version, String checker, String status, String action,
                              String reason) {
        if (checker == null || checker.isBlank()) {
            throw new LifecycleException("A checker is required");
        }
        return inTransaction(c -> {
            PackVersion pack = lock(c, packId, version);
            if (!"PENDING_APPROVAL".equals(pack.status())) {
                throw new LifecycleException(packId + " " + version + " is " + pack.status() + ", not PENDING_APPROVAL");
            }
            if (checker.equals(pack.maker())) {
                throw new LifecycleException("The checker must not be the maker (" + checker + ")");
            }
            try (var s = c.prepareStatement("update state_pack_version set status = ?, checker = ?, decided_at = now()"
                    + " where pack_id = ? and version = ?")) {
                s.setString(1, status);
                s.setString(2, checker);
                s.setString(3, packId);
                s.setString(4, version);
                s.executeUpdate();
            }
            decide(c, packId, version, action, checker, reason);
            if ("APPROVED".equals(status)) {
                event(c, Topics.PACK_APPROVED, "uddp.statepack.pack-approved", packId, version, pack.stateCode(),
                        pack.digest(), checker);
            }
            return find(c, packId, version).orElseThrow();
        });
    }

    private PackVersion lock(Connection c, String packId, String version) throws Exception {
        try (var s = c.prepareStatement("select 1 from state_pack_version where pack_id = ? and version = ?"
                + " for update")) {
            s.setString(1, packId);
            s.setString(2, version);
            s.executeQuery().close();
        }
        return find(c, packId, version).orElseThrow(() -> new LifecycleException("No State Pack " + packId + " "
                + version));
    }

    private Optional<PackVersion> find(Connection c, String packId, String version) throws Exception {
        String stateCode;
        String digest;
        String status;
        String maker;
        String checker;
        try (var s = c.prepareStatement("select state_code, digest, status, maker, checker from state_pack_version"
                + " where pack_id = ? and version = ?")) {
            s.setString(1, packId);
            s.setString(2, version);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                stateCode = r.getString(1);
                digest = r.getString(2);
                status = r.getString(3);
                maker = r.getString(4);
                checker = r.getString(5);
            }
        }
        Map<String, String> files = new LinkedHashMap<>();
        try (var s = c.prepareStatement("select path, content from state_pack_file where pack_id = ? and version = ?"
                + " order by path")) {
            s.setString(1, packId);
            s.setString(2, version);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    files.put(r.getString(1), r.getString(2));
                }
            }
        }
        return Optional.of(new PackVersion(packId, version, stateCode, digest, status, maker, checker, files));
    }

    private static void status(Connection c, String packId, String version, String status) throws Exception {
        try (var s = c.prepareStatement("update state_pack_version set status = ? where pack_id = ? and version = ?")) {
            s.setString(1, status);
            s.setString(2, packId);
            s.setString(3, version);
            s.executeUpdate();
        }
    }

    private static void decide(Connection c, String packId, String version, String action, String actor,
                               String reason) throws Exception {
        try (var s = c.prepareStatement("insert into state_pack_decision (pack_id, version, action, actor, reason)"
                + " values (?, ?, ?, ?, ?)")) {
            s.setString(1, packId);
            s.setString(2, version);
            s.setString(3, action);
            s.setString(4, actor);
            s.setString(5, reason);
            s.executeUpdate();
        }
    }

    private static void event(Connection c, String topic, String type, String packId, String version, String stateCode,
                              String digest, String actor) throws Exception {
        ObjectNode p = Outbox.json().createObjectNode();
        p.put("packId", packId);
        p.put("version", version);
        p.put("stateCode", stateCode);
        p.put("digest", digest);
        p.put("actor", actor);
        Outbox.append(c, topic, new EventEnvelope(Outbox.deterministicId(type, packId + "|" + version), type, 1,
                Instant.now(), PRODUCER, packId, null, null, stateCode, new StatePackRef(packId, version, digest), p));
    }

    private JsonNode manifest(Map<String, String> files) {
        String manifest = files.get("state.yaml");
        if (manifest == null) {
            throw new LifecycleException("A State Pack needs a state.yaml manifest");
        }
        try {
            return yaml.readTree(manifest);
        } catch (Exception e) {
            throw new LifecycleException("state.yaml is not valid YAML: " + e.getMessage());
        }
    }

    private static String required(JsonNode manifest, String field) {
        String value = manifest.path(field).asText("");
        if (value.isBlank()) {
            throw new LifecycleException("state.yaml has no " + field);
        }
        return value;
    }

    private interface Work<T> {
        T run(Connection c) throws Exception;
    }

    private <T> T inTransaction(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("State Pack database work failed", e);
        }
    }
}
