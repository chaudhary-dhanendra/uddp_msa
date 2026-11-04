package gov.uddp.shaping.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import gov.uddp.core.rules.ApprovalConflictException;
import gov.uddp.core.security.AuditService;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.shaping.OperationRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Tier-3 shaping plug-ins as the registry found them at startup, and the approval list that decides which jars may
 * load. Approving or revoking a jar is a proposed change another user decides ({@link ShapingChanges}); the decision
 * edits {@code approved-plugins.yaml} and takes effect the next time the platform starts, because plug-ins are only
 * loaded at startup. Startup outcomes and decisions are audit events (PLUGIN_LOADED, PLUGIN_REJECTED,
 * PLUGIN_APPROVED, PLUGIN_REVOKED).
 */
@Service
public class PluginAdminService {
    private static final Logger log = LoggerFactory.getLogger(PluginAdminService.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper WRITER = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
    private static final String RESOURCE = "SHAPING_PLUGIN";

    /** A jar in the plugins directory (or only in the approval list: status NOT_FOUND). */
    public record PluginInfo(String fileName, String sha256, String name, String version, String apiVersion,
                             String status, String reason, boolean approved, List<String> provides, List<String> packs,
                             UUID pendingChangeId, String pendingAction) {
    }

    public record BuiltIn(String kind, String name, String version, String key) {
    }

    private final OperationRegistry registry;
    private final StatePackLoader packs;
    private final ShapingChanges changes;
    private final AuditService audit;
    private final Path approvedFile;

    public PluginAdminService(OperationRegistry registry, StatePackLoader packs, ShapingChanges changes,
                              AuditService audit,
                              @Value("${uddp.shaping.approved-plugins-file:platform/shaping/approved-plugins.yaml}")
                              String approvedFile) {
        this.registry = registry;
        this.packs = packs;
        this.changes = changes;
        this.audit = audit;
        this.approvedFile = Path.of(approvedFile);
    }

    public List<BuiltIn> builtIns() {
        List<BuiltIn> all = new ArrayList<>();
        registry.builtInOperations().forEach(key -> all.add(builtIn("operation", key)));
        registry.builtInParsers().forEach(key -> all.add(builtIn("parser", key)));
        return all;
    }

    private static BuiltIn builtIn(String kind, String key) {
        int at = key.indexOf('@');
        return new BuiltIn(kind, key.substring(0, at), key.substring(at + 1), key);
    }

    /** Parsers and operations registered by plug-ins (as opposed to built in). */
    public List<String> pluginKeys() {
        return registry.plugins().stream().flatMap(p -> p.provides().stream()).toList();
    }

    public List<PluginInfo> plugins() {
        Map<String, JsonNode> approved = approvedEntries();
        List<PluginInfo> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PluginStatus status : registry.plugins()) {
            seen.add(status.sha256());
            result.add(info(status, approved.containsKey(status.sha256())));
        }
        approved.forEach((sha, entry) -> {
            if (!seen.contains(sha)) {
                result.add(info(new PluginStatus(null, sha, entry.path("name").asText(null), null, null, "NOT_FOUND",
                        "Listed in approved-plugins.yaml but no jar with this hash is in the plugins directory",
                        List.of()), true));
            }
        });
        return result;
    }

    private PluginInfo info(PluginStatus status, boolean approved) {
        var pending = changes.pending(ShapingChanges.PLUGIN, status.sha256());
        return new PluginInfo(status.fileName(), status.sha256(), status.name(), status.version(), status.apiVersion(),
                status.status(), status.reason(), approved, status.provides(), packsUsing(status.provides()),
                pending.map(ShapingChanges.Change::changeId).orElse(null), pending.map(ShapingChanges.Change::action).orElse(null));
    }

    /** Ids of the State Packs whose layout steps or parsers name one of the plug-in's operations or parsers. */
    private List<String> packsUsing(List<String> provides) {
        if (provides.isEmpty()) {
            return List.of();
        }
        List<String> used = new ArrayList<>();
        for (LoadedStatePack pack : packs.all()) {
            boolean uses = pack.sources().stream().anyMatch(source ->
                    steps(source.layout(), "op").stream().anyMatch(provides::contains)
                            || steps(source.parse(), "parser").stream().anyMatch(provides::contains));
            if (uses) {
                used.add(pack.manifest().packId());
            }
        }
        return used;
    }

    private static List<String> steps(List<Map<String, Object>> steps, String field) {
        return steps == null ? List.of() : steps.stream().map(s -> String.valueOf(s.get(field))).toList();
    }

    /** Proposes approving or revoking one jar (identified by its SHA-256); another user decides. */
    public ShapingChanges.Change propose(String sha256, String action, String actor) {
        if (!"APPROVE".equals(action) && !"REVOKE".equals(action)) {
            throw new IllegalArgumentException("Action must be APPROVE or REVOKE");
        }
        String sha = sha256 == null ? "" : sha256.toLowerCase();
        boolean approved = approvedEntries().containsKey(sha);
        PluginStatus found = registry.plugins().stream().filter(p -> sha.equals(p.sha256())).findFirst().orElse(null);
        if ("APPROVE".equals(action)) {
            if (found == null) {
                throw new IllegalArgumentException("No jar with this SHA-256 is in the plugins directory");
            }
            if (approved) {
                throw new ApprovalConflictException("This jar is already approved");
            }
        } else if (!approved) {
            throw new ApprovalConflictException("This jar is not in the approved list");
        }
        if (changes.pending(ShapingChanges.PLUGIN, sha).isPresent()) {
            throw new ApprovalConflictException("This jar already has a change waiting for approval");
        }
        ObjectNode payload = YAML.createObjectNode();
        payload.put("sha256", sha);
        if (found != null) {
            payload.put("fileName", found.fileName());
            if (found.name() != null) {
                payload.put("name", found.name());
            }
        }
        return changes.propose(ShapingChanges.PLUGIN, action, sha, payload, actor);
    }

    /** Edits the approval list for a decided change. */
    void apply(ShapingChanges.Change change, String approver) {
        String sha = change.target();
        Map<String, JsonNode> entries = new LinkedHashMap<>(approvedEntries());
        String event;
        if ("APPROVE".equals(change.action())) {
            ObjectNode entry = YAML.createObjectNode();
            entry.put("sha256", sha);
            if (change.payload().hasNonNull("name")) {
                entry.put("name", change.payload().get("name").asText());
            }
            entry.put("approvedBy", approver);
            entry.put("approvedAt", OffsetDateTime.now().toString());
            entries.put(sha, entry);
            event = "PLUGIN_APPROVED";
        } else {
            entries.remove(sha);
            event = "PLUGIN_REVOKED";
        }
        writeApproved(entries.values());
        audit.record(AuditService.AuditEvent.of(event, RESOURCE, sha, null, AuditService.SUCCESS)
                .by(approver).with(Map.of("proposedBy", change.createdBy(), "effect", "next startup")));
    }

    /** Records what each jar did at startup (loaded or why it was not). */
    @EventListener(ApplicationReadyEvent.class)
    public void recordStartup() {
        for (PluginStatus status : registry.plugins()) {
            boolean loaded = PluginStatus.LOADED.equals(status.status());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("file", status.fileName());
            details.put("status", status.status());
            if (loaded) {
                details.put("plugin", status.name() + "@" + status.version());
            } else {
                details.put("reason", status.reason());
            }
            try {
                audit.record(AuditService.AuditEvent.of(loaded ? "PLUGIN_LOADED" : "PLUGIN_REJECTED", RESOURCE,
                        status.sha256(), null, loaded ? AuditService.SUCCESS : AuditService.DENIED).with(details));
            } catch (RuntimeException e) {
                log.warn("Plugin startup event for {} not stored: {}", status.fileName(), e.getMessage());
            }
        }
    }

    // ---- approved-plugins.yaml ----------------------------------------------------------------------------------

    private Map<String, JsonNode> approvedEntries() {
        Map<String, JsonNode> entries = new LinkedHashMap<>();
        if (!Files.isRegularFile(approvedFile)) {
            return entries;
        }
        try {
            YAML.readTree(approvedFile.toFile()).path("approved").forEach(entry -> {
                String sha = entry.path("sha256").asText("").toLowerCase();
                if (!sha.isEmpty()) {
                    entries.put(sha, entry);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }

    private void writeApproved(Iterable<JsonNode> entries) {
        ObjectNode root = YAML.createObjectNode();
        ArrayNode approved = root.putArray("approved");
        entries.forEach(approved::add);
        try {
            StringBuilder header = new StringBuilder();
            if (Files.isRegularFile(approvedFile)) {
                for (String line : Files.readAllLines(approvedFile)) {
                    if (!line.startsWith("#") || line.startsWith("# approved:") || line.startsWith("#   ")) {
                        break;
                    }
                    header.append(line).append('\n');
                }
            }
            Path parent = approvedFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(approvedFile, header + WRITER.writeValueAsString(root));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
