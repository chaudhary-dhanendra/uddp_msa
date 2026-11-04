package gov.uddp.core.matching;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Matching profiles as versioned configuration (Matching Studio). A profile is DRAFT until it has been trained and
 * submitted; a different user then approves it (maker-checker). At most one profile is APPROVED; matching runs use
 * it, and until one is approved the built-in {@value #DEFAULT_MODEL} stays in force.
 */
@Service
public class MatchingProfileService {
    public static final String DEFAULT_MODEL = "resident-match-v1";
    static final String COLUMNS = "profile_id, name, version, status, config::text, parameters::text, weights::text,"
            + " training_records, created_by, submitted_by, approved_by, created_at, trained_at, approved_at";

    public record MatchingProfile(UUID profileId, String name, int version, String status, Map<String, Object> config,
                          Map<String, Object> parameters, List<Map<String, Object>> weights, Integer trainingRecords,
                          String createdBy, String submittedBy, String approvedBy, OffsetDateTime createdAt,
                          OffsetDateTime trainedAt, OffsetDateTime approvedAt) {
        public String modelVersion() {
            return name + "-v" + version;
        }
    }

    public record MatchingTrainingJob(UUID jobId, UUID profileId, String status, String message, String requestedBy,
                              OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    public static class ProfileConflictException extends gov.uddp.core.stewardship.StewardshipConflictException {
        public ProfileConflictException(String message) {
            super(message);
        }
    }

    private final PostgresConnectionFactory connections;
    private final ObjectMapper json;
    private final PythonModules.Runner python;
    private final Executor background;

    @Autowired
    public MatchingProfileService(PostgresConnectionFactory connections, ObjectMapper json, PythonModules modules) {
        this(connections, json, modules::run, task -> new Thread(task, "matching-training").start());
    }

    /** For tests: a replaceable Python boundary and a synchronous executor. */
    public MatchingProfileService(PostgresConnectionFactory connections, ObjectMapper json, PythonModules.Runner python,
                                  Executor background) {
        this.connections = connections;
        this.json = json;
        this.python = python;
        this.background = background;
    }

    // ---- reads

    public List<MatchingProfile> list() {
        return query("select " + COLUMNS + " from matching_profile order by name, version desc");
    }

    public MatchingProfile get(UUID id) {
        return query("select " + COLUMNS + " from matching_profile where profile_id = ?", id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Matching profile does not exist: " + id));
    }

    /** The APPROVED profile, or empty while the built-in model is still the default. */
    public Optional<MatchingProfile> approved() {
        return query("select " + COLUMNS + " from matching_profile where status = 'APPROVED'").stream().findFirst();
    }

    /** The model version the next matching run will record: the approved profile, else the built-in default. */
    public String effectiveModelVersion() {
        return approved().map(MatchingProfile::modelVersion).orElse(DEFAULT_MODEL);
    }

    public List<MatchingTrainingJob> jobs(UUID profileId) {
        List<MatchingTrainingJob> out = new ArrayList<>();
        try (var c = connections.open(); var s = c.prepareStatement("select job_id, profile_id, status, message,"
                + " requested_by, started_at, finished_at from matching_training_job where profile_id = ?"
                + " order by started_at desc")) {
            s.setObject(1, profileId);
            var r = s.executeQuery();
            while (r.next()) {
                out.add(new MatchingTrainingJob(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3),
                        r.getString(4), r.getString(5), r.getObject(6, OffsetDateTime.class),
                        r.getObject(7, OffsetDateTime.class)));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read training jobs", e);
        }
    }

    // ---- authoring

    /** A new DRAFT version of the named profile. */
    public MatchingProfile create(String name, Map<String, Object> config, String user) {
        requireValid(config);
        String profileName = name == null || name.isBlank() ? "resident" : name.trim();
        UUID id = UUID.randomUUID();
        execute("insert into matching_profile(profile_id, name, version, status, config, created_by) values (?, ?,"
                + " (select coalesce(max(version), 0) + 1 from matching_profile where name = ?), 'DRAFT',"
                + " cast(? as jsonb), ?)", id, profileName, profileName, write(config), user);
        return get(id);
    }

    /** Replaces a DRAFT configuration; earlier training no longer applies, so it is cleared. */
    public MatchingProfile update(UUID id, Map<String, Object> config) {
        requireValid(config);
        requireStatus(get(id), "DRAFT");
        execute("update matching_profile set config = cast(? as jsonb), parameters = null, weights = null,"
                + " training_records = null, trained_at = null where profile_id = ?", write(config), id);
        return get(id);
    }

    /** Queues training and runs it in the background; progress is on the returned job. */
    public MatchingTrainingJob startTraining(UUID id, String user) {
        requireStatus(get(id), "DRAFT");
        UUID jobId = UUID.randomUUID();
        execute("insert into matching_training_job(job_id, profile_id, status, message, requested_by)"
                + " values (?, ?, 'QUEUED', 'Waiting to start', ?)", jobId, id, user);
        background.execute(() -> {
            try {
                python.run("train", List.of("--profile-id", id.toString(), "--job-id", jobId.toString()));
            } catch (RuntimeException e) {
                String message = e.getMessage() == null ? "Training failed" : e.getMessage();
                execute("update matching_training_job set status = 'FAILED', message = ?, finished_at = now()"
                        + " where job_id = ? and status <> 'COMPLETED'",
                        message.substring(0, Math.min(500, message.length())), jobId);
            }
        });
        return jobs(id).stream().filter(j -> j.jobId().equals(jobId)).findFirst().orElseThrow();
    }

    public JsonNode estimateBlocking(UUID id) {
        return python.run("estimate", List.of("--profile-id", id.toString()));
    }

    // ---- maker-checker

    public MatchingProfile submit(UUID id, String user) {
        MatchingProfile profile = get(id);
        requireStatus(profile, "DRAFT");
        if (profile.trainedAt() == null) {
            throw new ProfileConflictException("Train the profile before submitting it for approval");
        }
        execute("update matching_profile set status = 'PENDING_APPROVAL', submitted_by = ? where profile_id = ?", user, id);
        return get(id);
    }

    /** A different user approves: the previously approved profile is retired and this one becomes the model in use. */
    public MatchingProfile approve(UUID id, String approver) {
        MatchingProfile profile = get(id);
        requireStatus(profile, "PENDING_APPROVAL");
        if (approver == null || approver.isBlank() || approver.equals(profile.submittedBy())
                || approver.equals(profile.createdBy())) {
            throw new ProfileConflictException("A matching profile must be approved by a signed-in user other than its"
                    + " author and submitter");
        }
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try (var retire = c.prepareStatement("update matching_profile set status = 'RETIRED' where status = 'APPROVED'");
                 var approve = c.prepareStatement("update matching_profile set status = 'APPROVED', approved_by = ?,"
                         + " approved_at = now() where profile_id = ?")) {
                retire.executeUpdate();
                approve.setString(1, approver);
                approve.setObject(2, id);
                approve.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot approve the matching profile", e);
        }
        return get(id);
    }

    /**
     * A threshold change approved through a monitoring proposal: the trained model is unchanged, so the approved profile
     * is copied as a new, already approved version with the new thresholds, and the old version is retired.
     */
    public MatchingProfile approveWithThresholds(UUID approvedId, double autoMatch, double possibleMatch, String approver) {
        MatchingProfile base = get(approvedId);
        requireStatus(base, "APPROVED");
        if (!(0 <= possibleMatch && possibleMatch <= autoMatch && autoMatch <= 1)) {
            throw new IllegalArgumentException("Thresholds must satisfy 0 <= possibleMatch <= autoMatch <= 1");
        }
        Map<String, Object> config = new java.util.LinkedHashMap<>(base.config());
        config.put("thresholds", Map.of("autoMatch", autoMatch, "possibleMatch", possibleMatch));
        UUID id = UUID.randomUUID();
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try (var retire = c.prepareStatement("update matching_profile set status = 'RETIRED' where profile_id = ?");
                 var copy = c.prepareStatement("insert into matching_profile(profile_id, name, version, status, config,"
                         + " parameters, weights, training_records, created_by, submitted_by, approved_by, trained_at,"
                         + " approved_at) select ?, name, (select max(version) + 1 from matching_profile where name = p.name),"
                         + " 'APPROVED', cast(? as jsonb), parameters, weights, training_records, ?, ?, ?, trained_at, now()"
                         + " from matching_profile p where profile_id = ?")) {
                retire.setObject(1, approvedId);
                retire.executeUpdate();
                copy.setObject(1, id);
                copy.setString(2, write(config));
                copy.setString(3, approver);
                copy.setString(4, approver);
                copy.setString(5, approver);
                copy.setObject(6, approvedId);
                copy.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot apply the threshold change", e);
        }
        return get(id);
    }

    /** Back to DRAFT for changes. */
    public MatchingProfile reject(UUID id) {
        requireStatus(get(id), "PENDING_APPROVAL");
        execute("update matching_profile set status = 'DRAFT', submitted_by = null where profile_id = ?", id);
        return get(id);
    }

    public MatchingProfile retire(UUID id) {
        requireStatus(get(id), "APPROVED");
        execute("update matching_profile set status = 'RETIRED' where profile_id = ?", id);
        return get(id);
    }

    // ---- plumbing

    private static void requireStatus(MatchingProfile profile, String expected) {
        if (!profile.status().equals(expected)) {
            throw new ProfileConflictException("The profile is " + profile.status() + "; this needs " + expected);
        }
    }

    private void requireValid(Map<String, Object> config) {
        if (config == null || !(config.get("blocking") instanceof List<?> blocking) || blocking.isEmpty()) {
            throw new IllegalArgumentException("A matching profile needs at least one blocking rule");
        }
        if (config.get("fields") instanceof List<?> fields && fields.isEmpty()) {
            throw new IllegalArgumentException("A matching profile needs at least one compared field");
        }
        if (config.get("thresholds") instanceof Map<?, ?> t) {
            double auto = ((Number) t.get("autoMatch")).doubleValue();
            double possible = ((Number) t.get("possibleMatch")).doubleValue();
            if (!(0 <= possible && possible <= auto && auto <= 1)) {
                throw new IllegalArgumentException("Thresholds must satisfy 0 <= possibleMatch <= autoMatch <= 1");
            }
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not valid JSON", e);
        }
    }

    private void execute(String sql, Object... arguments) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                s.setObject(i + 1, arguments[i]);
            }
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Matching profile update failed", e);
        }
    }

    private List<MatchingProfile> query(String sql, Object... arguments) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                s.setObject(i + 1, arguments[i]);
            }
            var r = s.executeQuery();
            List<MatchingProfile> out = new ArrayList<>();
            while (r.next()) {
                out.add(profile(r));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read matching profiles", e);
        }
    }

    private MatchingProfile profile(ResultSet r) throws SQLException {
        try {
            var map = new TypeReference<Map<String, Object>>() { };
            var list = new TypeReference<List<Map<String, Object>>>() { };
            String parameters = r.getString(6);
            String weights = r.getString(7);
            return new MatchingProfile(r.getObject(1, UUID.class), r.getString(2), r.getInt(3), r.getString(4),
                    json.readValue(r.getString(5), map), parameters == null ? null : json.readValue(parameters, map),
                    weights == null ? null : json.readValue(weights, list), (Integer) r.getObject(8), r.getString(9),
                    r.getString(10), r.getString(11), r.getObject(12, OffsetDateTime.class),
                    r.getObject(13, OffsetDateTime.class), r.getObject(14, OffsetDateTime.class));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Stored profile JSON is unreadable", e);
        }
    }
}
