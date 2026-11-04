package gov.uddp.core.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.rules.MakerChecker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The identifier type registry (Track E1): the types live in {@code identifier_type} (V80), seeded from
 * {@code <platform>/identity/identifier-types.yaml} the first time the registry is read. A type is added or changed
 * only through a proposed change that someone other than its author approves ({@link MakerChecker}). Validation,
 * normalisation and the scope key are worked out here from the type's configuration; check-digit algorithms are
 * pluggable {@link ChecksumValidator} beans. Without a database (unit tests, static callers) the seed file alone is
 * the registry.
 */
@Component
public class IdentifierTypeRegistry {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The outcome of checking one value; {@code reason} never contains the value. */
    public record Check(String normalised, boolean valid, String reason) {
    }

    /** A proposed change to one type, with its maker-checker state. */
    public record IdentifierTypeChange(UUID changeId, String code, IdentifierType definition, String status, String createdBy,
                         String submittedBy, String decidedBy, String decisionReason, OffsetDateTime createdAt) {
    }

    private final PostgresConnectionFactory connections;
    private final Path seedFile;
    private final Map<String, ChecksumValidator> checksums = new LinkedHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final MakerChecker approvals;
    private volatile Map<String, IdentifierType> cache;

    @Autowired
    public IdentifierTypeRegistry(PostgresConnectionFactory connections, StatePackProperties properties,
                                  List<ChecksumValidator> validators) {
        this(connections, Path.of(properties.getPlatformDirectory(), "identity", "identifier-types.yaml"), validators);
    }

    public IdentifierTypeRegistry(PostgresConnectionFactory connections, Path seedFile, List<ChecksumValidator> validators) {
        this.connections = connections;
        this.seedFile = seedFile;
        validators.forEach(v -> checksums.put(v.name().toUpperCase(Locale.ROOT), v));
        this.approvals = connections == null ? null
                : new MakerChecker(connections, "identifier_type_change", "change_id", "identifier type change");
    }

    /** A registry of exactly these types, with no database (tests and tools). */
    public static IdentifierTypeRegistry of(List<IdentifierType> types, List<ChecksumValidator> validators) {
        var registry = new IdentifierTypeRegistry(null, (Path) null, validators);
        var map = new LinkedHashMap<String, IdentifierType>();
        types.forEach(type -> map.put(type.code(), registry.validated(type)));
        registry.cache = Map.copyOf(map);
        return registry;
    }

    /** The seed file's types with the built-in validators, with no database. */
    public static IdentifierTypeRegistry fromSeed(Path seedFile) {
        return of(readSeed(seedFile), defaultValidators());
    }

    public static List<ChecksumValidator> defaultValidators() {
        return List.of(new VerhoeffChecksum(), new LuhnChecksum());
    }

    /** The types of a seed file ({@code identifierTypes:} list). */
    public static List<IdentifierType> readSeed(Path file) {
        try {
            var root = YAML.readTree(Files.readString(file.toAbsolutePath().normalize()));
            return YAML.convertValue(root.path("identifierTypes"), new TypeReference<List<IdentifierType>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read identifier types from " + file, e);
        }
    }

    public List<IdentifierType> types() {
        return List.copyOf(load().values());
    }

    public Optional<IdentifierType> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(load().get(code.strip().toUpperCase(Locale.ROOT)));
    }

    public IdentifierType require(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("Unknown identifier type: " + code));
    }

    /** Normalises and validates one value against its type; the reason names the rule, never the value. */
    public Check check(IdentifierType type, String value) {
        String normalised = type.normalise(value);
        var rules = type.validation();
        if (normalised.isEmpty()) {
            return new Check(normalised, false, type.code() + " value is blank");
        }
        if (rules.minLength() != null && normalised.length() < rules.minLength()
                || rules.maxLength() != null && normalised.length() > rules.maxLength()) {
            return new Check(normalised, false, type.code() + " value has the wrong length");
        }
        if (rules.pattern() != null && !Pattern.compile(rules.pattern()).matcher(normalised).matches()) {
            return new Check(normalised, false, type.code() + " value does not have the configured format");
        }
        if (rules.checksum() != null && !checksum(rules.checksum()).valid(normalised)) {
            return new Check(normalised, false, type.code() + " value fails its " + rules.checksum() + " check");
        }
        return new Check(normalised, true, null);
    }

    /**
     * The text a protected value is derived from: {@code TYPE:value} for a NATIONAL type and {@code TYPE:ISSUER:value}
     * for an ISSUER type, so the same number from two issuers never compares equal.
     */
    public String scopeKey(IdentifierType type, String issuer, String normalised) {
        if (type.scope() == IdentifierType.Scope.NATIONAL) {
            return type.code() + ":" + normalised;
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException(type.code() + " is issuer-scoped and needs an issuer");
        }
        return type.code() + ":" + issuer.strip().toUpperCase(Locale.ROOT) + ":" + normalised;
    }

    public ChecksumValidator checksum(String name) {
        var validator = checksums.get(name.toUpperCase(Locale.ROOT));
        if (validator == null) {
            throw new IllegalArgumentException("Unknown checksum algorithm: " + name);
        }
        return validator;
    }

    /** Types whose values the leak guard looks for in free text. */
    public List<IdentifierType> sensitiveTypes() {
        return types().stream().filter(IdentifierType::sensitivePattern).toList();
    }

    // ---- changes (maker-checker) ------------------------------------------------------------------------------

    /** Records a proposed new or changed type as a DRAFT; nothing changes until another user approves it. */
    public IdentifierTypeChange propose(IdentifierType definition, String actor) {
        requireDatabase();
        IdentifierType checked = validated(definition);
        UUID id = UUID.randomUUID();
        try (var c = connections.open(); var s = c.prepareStatement("insert into identifier_type_change(change_id, code,"
                + " definition, status, created_by) values (?, ?, cast(? as jsonb), 'DRAFT', ?)")) {
            s.setObject(1, id);
            s.setString(2, checked.code());
            s.setString(3, json.writeValueAsString(checked));
            s.setString(4, actor);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record the identifier type change", e);
        }
        return change(id);
    }

    public IdentifierTypeChange submit(UUID changeId, String actor) {
        requireDatabase();
        approvals.submit(changeId, actor);
        return change(changeId);
    }

    /** Approves a submitted change (never by its author or submitter) and makes it the type's current definition. */
    public IdentifierTypeChange approve(UUID changeId, String approver, String reason) {
        requireDatabase();
        approvals.approve(changeId, approver, reason);
        IdentifierTypeChange change = change(changeId);
        try (var c = connections.open(); var s = c.prepareStatement("insert into identifier_type(code, definition,"
                + " version, updated_by) values (?, cast(? as jsonb), 1, ?) on conflict (code) do update set"
                + " definition = excluded.definition, version = identifier_type.version + 1,"
                + " updated_by = excluded.updated_by, updated_at = now()")) {
            s.setString(1, change.code());
            s.setString(2, json.writeValueAsString(change.definition()));
            s.setString(3, approver);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot apply the identifier type change", e);
        }
        cache = null;
        return change;
    }

    public IdentifierTypeChange reject(UUID changeId, String approver, String reason) {
        requireDatabase();
        approvals.reject(changeId, approver, reason);
        return change(changeId);
    }

    public List<IdentifierTypeChange> changes() {
        requireDatabase();
        List<IdentifierTypeChange> out = new ArrayList<>();
        try (var c = connections.open(); var s = c.prepareStatement("select change_id from identifier_type_change"
                + " order by created_at desc, change_id"); var r = s.executeQuery()) {
            while (r.next()) {
                out.add(change(r.getObject(1, UUID.class)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot list identifier type changes", e);
        }
        return out;
    }

    public IdentifierTypeChange change(UUID id) {
        requireDatabase();
        try (var c = connections.open(); var s = c.prepareStatement("select code, definition::text, status, created_by,"
                + " submitted_by, decided_by, decision_reason, created_at from identifier_type_change where change_id = ?")) {
            s.setObject(1, id);
            var r = s.executeQuery();
            if (!r.next()) {
                throw new IllegalArgumentException("identifier type change does not exist: " + id);
            }
            return new IdentifierTypeChange(id, r.getString(1), json.readValue(r.getString(2), IdentifierType.class), r.getString(3),
                    r.getString(4), r.getString(5), r.getString(6), r.getString(7),
                    r.getObject(8, OffsetDateTime.class));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the identifier type change", e);
        }
    }

    /** Forgets the cached types (after an approval in another instance, for example). */
    public void refresh() {
        if (connections != null) {
            cache = null;
        }
    }

    // ---- internals -------------------------------------------------------------------------------------------

    /** Checks a definition's patterns and checksum name before it can be stored. */
    IdentifierType validated(IdentifierType type) {
        try {
            if (type.validation().pattern() != null) {
                Pattern.compile(type.validation().pattern());
            }
            if (type.detection() != null) {
                Pattern.compile(type.detection());
            }
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Identifier type " + type.code() + " has an invalid pattern");
        }
        if (type.validation().checksum() != null) {
            checksum(type.validation().checksum());
        }
        return type;
    }

    private Map<String, IdentifierType> load() {
        var current = cache;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cache == null) {
                seed();
                var map = new LinkedHashMap<String, IdentifierType>();
                try (var c = connections.open(); var s = c.prepareStatement("select definition::text from identifier_type"
                        + " order by code"); var r = s.executeQuery()) {
                    while (r.next()) {
                        var type = json.readValue(r.getString(1), IdentifierType.class);
                        map.put(type.code(), type);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot read the identifier type registry", e);
                }
                cache = Map.copyOf(map);
            }
            return cache;
        }
    }

    /** Inserts the seed file's types that the registry does not hold yet; existing rows are never overwritten. */
    private void seed() {
        if (seedFile == null || !Files.exists(seedFile)) {
            return;
        }
        try (var c = connections.open(); var s = c.prepareStatement("insert into identifier_type(code, definition,"
                + " version, updated_by) values (?, cast(? as jsonb), 1, 'seed') on conflict (code) do nothing")) {
            for (IdentifierType type : readSeed(seedFile)) {
                s.setString(1, validated(type).code());
                s.setString(2, json.writeValueAsString(type));
                s.addBatch();
            }
            s.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot seed the identifier type registry", e);
        }
    }

    private void requireDatabase() {
        if (connections == null) {
            throw new IllegalStateException("This identifier type registry has no database");
        }
    }
}
