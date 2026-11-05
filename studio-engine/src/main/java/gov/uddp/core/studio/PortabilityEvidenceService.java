package gov.uddp.core.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.config.StudioProperties;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.studio.StudioModels.Draft;
import gov.uddp.core.studio.StudioModels.GuardResult;
import gov.uddp.core.studio.StudioModels.PortabilityEvidence;
import gov.uddp.core.studio.StudioModels.StateEvidence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Portability evidence computed from git, never asserted (ADR-0015). A fingerprint of the platform code (the commit
 * plus a content hash of every uncommitted file under the platform paths) is recorded when the backend starts and
 * whenever a state is published; a state's platform code changes are the files that differ between the fingerprint
 * before its publication and the one at it. The evidence also lists what differs from the baseline ref (default
 * {@code master}) and the latest portability guard result from the Maven surefire report.
 */
@Service
public class PortabilityEvidenceService {
    private static final Logger log = LoggerFactory.getLogger(PortabilityEvidenceService.class);
    /** The generic platform code: every service and engine module, the matcher, the Hop templates, platform data. */
    static final List<String> PLATFORM_PATHS = List.of("services", "engine", "canonical-engine", "golden-engine",
            "matching-engine", "studio-engine", "contracts", "messaging", "platform");
    private static final List<String> EXCLUDED = List.of(":(exclude)services/*/seed/**", ":(exclude)**/src/test/**",
            ":(exclude)services/identity-service/matching/test_*",
            ":(exclude)services/identity-service/matching/conftest.py",
            ":(exclude)**/__pycache__/**");
    private static final String GUARD = "gov.uddp.core.portability.M10PortabilityGuardTest";

    public record Fingerprint(String label, OffsetDateTime at, String commit, Map<String, String> uncommitted) {
    }

    record Fingerprints(List<Fingerprint> fingerprints) {
    }

    private final Path repository;
    private final StudioProperties studio;
    private final StudioWorkspace workspace;
    private final StatePackLoader packs;
    private final ObjectMapper json;

    public PortabilityEvidenceService(StatePackProperties properties, StudioProperties studio, StudioWorkspace workspace,
                                      StatePackLoader packs, ObjectMapper json) {
        this.repository = properties.repositoryRoot();
        this.studio = studio;
        this.workspace = workspace;
        this.packs = packs;
        this.json = json.copy().enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recordStartup() {
        try {
            record("platform started");
        } catch (RuntimeException e) {
            log.warn("Cannot record the platform fingerprint at startup: {}", e.getMessage());
        }
    }

    /** Records the platform fingerprint now under {@code label} (a pack ID at publication). */
    public synchronized Fingerprint record(String label) {
        Fingerprint fingerprint = fingerprint(label);
        List<Fingerprint> all = new ArrayList<>(read());
        all.add(fingerprint);
        write(all);
        return fingerprint;
    }

    public PortabilityEvidence evidence(List<Draft> drafts) {
        String gitError = null;
        String baselineCommit = null;
        String current = null;
        List<String> changed = List.of();
        try {
            baselineCommit = git("rev-parse", studio.getBaselineRef()).strip();
            current = git("rev-parse", "HEAD").strip();
            changed = changedSince(studio.getBaselineRef());
        } catch (RuntimeException e) {
            gitError = e.getMessage();
        }
        List<Fingerprint> fingerprints = read();
        List<StateEvidence> states = new ArrayList<>();
        for (Draft draft : drafts) {
            if (draft.published() == null) {
                continue;
            }
            List<String> templates = packs.find(draft.packId())
                    .map(p -> p.sources().stream().map(s -> s.hopTemplate()).distinct().toList())
                    .orElse(List.of());
            Fingerprint at = null;
            Fingerprint before = null;
            for (Fingerprint f : fingerprints) {
                if (f.label().equals(draft.packId()) && !f.at().isAfter(draft.published().publishedAt().plusSeconds(5))) {
                    at = f;
                }
            }
            if (at != null) {
                for (Fingerprint f : fingerprints) {
                    if (f.at().isBefore(at.at())) {
                        before = f;
                    }
                }
            }
            List<String> codeChanges = at == null || before == null ? List.of() : difference(before, at);
            states.add(new StateEvidence(draft.packId(), draft.stateCode(), draft.displayName(),
                    draft.published().files(), templates,
                    at == null ? null : at.commit() + (at.uncommitted().isEmpty() ? "" : " + "
                            + at.uncommitted().size() + " uncommitted platform file(s)"),
                    codeChanges));
        }
        return new PortabilityEvidence(studio.getBaselineRef(), baselineCommit, current, PLATFORM_PATHS, changed,
                gitError, guard(), states);
    }

    /** Platform files that differ between two fingerprints: committed changes plus differing uncommitted files. */
    List<String> difference(Fingerprint before, Fingerprint after) {
        TreeSet<String> files = new TreeSet<>();
        if (!before.commit().equals(after.commit())) {
            files.addAll(lines(git(pathspec("diff", "--name-only", before.commit(), after.commit()))));
        }
        TreeSet<String> uncommitted = new TreeSet<>(before.uncommitted().keySet());
        uncommitted.addAll(after.uncommitted().keySet());
        for (String file : uncommitted) {
            if (!java.util.Objects.equals(before.uncommitted().get(file), after.uncommitted().get(file))) {
                files.add(file);
            }
        }
        return List.copyOf(files);
    }

    private List<String> changedSince(String ref) {
        TreeSet<String> files = new TreeSet<>(lines(git(pathspec("diff", "--name-only", ref))));
        files.addAll(lines(git(pathspec("ls-files", "--others", "--exclude-standard"))));
        return List.copyOf(files);
    }

    Fingerprint fingerprint(String label) {
        String commit = git("rev-parse", "HEAD").strip();
        Map<String, String> uncommitted = new TreeMap<>();
        TreeSet<String> files = new TreeSet<>(lines(git(pathspec("diff", "--name-only", "HEAD"))));
        files.addAll(lines(git(pathspec("ls-files", "--others", "--exclude-standard"))));
        for (String file : files) {
            uncommitted.put(file, hash(repository.resolve(file)));
        }
        return new Fingerprint(label, OffsetDateTime.now(ZoneOffset.UTC), commit, uncommitted);
    }

    GuardResult guard() {
        Path report = repository.resolve("services/edge-service/target/surefire-reports/TEST-" + GUARD + ".xml");
        if (!Files.isRegularFile(report)) {
            return null;
        }
        try {
            String xml = Files.readString(report, StandardCharsets.UTF_8);
            Matcher suite = Pattern.compile("<testsuite[^>]*>").matcher(xml);
            if (!suite.find()) {
                return null;
            }
            String tag = suite.group();
            return new GuardResult(GUARD, attribute(tag, "tests"), attribute(tag, "failures"), attribute(tag, "errors"),
                    Files.getLastModifiedTime(report).toInstant().atOffset(ZoneOffset.UTC));
        } catch (IOException e) {
            return null;
        }
    }

    private static int attribute(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"(\\d+)\"").matcher(tag);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String[] pathspec(String... command) {
        List<String> arguments = new ArrayList<>(List.of(command));
        arguments.add("--");
        arguments.addAll(PLATFORM_PATHS);
        arguments.addAll(EXCLUDED);
        return arguments.toArray(String[]::new);
    }

    private String git(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).directory(repository.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("git " + String.join(" ", arguments) + " failed");
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("git is not available: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git was interrupted", e);
        }
    }

    private static List<String> lines(String output) {
        return output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    private static String hash(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return "deleted";
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (Exception e) {
            return "unreadable";
        }
    }

    private List<Fingerprint> read() {
        Path file = workspace.evidenceFile();
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return json.readValue(file.toFile(), Fingerprints.class).fingerprints();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void write(List<Fingerprint> fingerprints) {
        Path file = workspace.evidenceFile();
        try {
            Files.createDirectories(file.getParent());
            json.writeValue(file.toFile(), new Fingerprints(fingerprints));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write portability evidence", e);
        }
    }
}
