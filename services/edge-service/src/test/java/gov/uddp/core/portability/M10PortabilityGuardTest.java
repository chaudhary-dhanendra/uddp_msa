package gov.uddp.core.portability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Onboarding a state must be configuration only. This guard fails when:
 * <ul>
 *   <li>a generic engine (mapping, canonical, validation/DQ, survivorship, State Pack loading, scheme catalogue), the
 *       Python matching module or the generic Hop template differs from the last milestone baseline tag, other than
 *       the generic engine changes of the current milestone listed (and justified in its acceptance report) in
 *       {@link #STUDIO_GENERIC_CHANGES} (ADR-0005);</li>
 *   <li>any Hop pipeline other than the generic templates {@code generic-file-ingest.hpl},
 *       {@code generic-excel-ingest.hpl} and {@code generic-db-ingest.hpl} exists;</li>
 *   <li>production code (Java under {@code common-core/src/main}, Python under {@code matching/}) names a configured
 *       state, by pack ID or state code, in its content or in a file name.</li>
 * </ul>
 * Generic orchestration (new classes outside the protected paths) remains possible.
 */
class M10PortabilityGuardTest {
    private static final Path ROOT = Path.of("../..");
    /**
     * The MSA import commit (a plain copy of uddp-mvp, so the monolith's m15-scheme-benefit tag does not exist here);
     * its engines equal the monolith's. Engine changes the monolith made since M15 stay listed below.
     */
    private static final String BASELINE = "msa-import";
    /**
     * Generic engine files the Onboarding Studio changes for every state alike, each justified in
     * docs/milestones/STUDIO_ACCEPTANCE.md. Re-baseline and empty this list at the next accepted milestone.
     */
    static final Set<String> STUDIO_GENERIC_CHANGES = Set.of(
            "common-core/src/main/java/gov/uddp/core/statepack/StatePackLoader.java",
            "common-core/src/main/java/gov/uddp/core/statepack/StatePackAssets.java",
            "services/ingestion-service/hop/pipelines/generic-db-ingest.hpl",
            "common-core/src/main/java/gov/uddp/core/canonical/PostgresConnectionFactory.java",
            // V14: published State Packs are versioned database documents (ADR-0015), for every state alike.
            "common-core/src/main/java/gov/uddp/core/statepack/DatabaseStatePackRepository.java",
            // P1 Part 5b: nationalIds.* targets keep only a keyed hash; leak guard and raw redaction, for every state.
            "common-core/src/main/java/gov/uddp/core/mapping/ResidentMappingEngine.java",
            "common-core/src/main/java/gov/uddp/core/canonical/CanonicalResidentProcessingService.java",
            // P1 Part 5b: optional exact comparison on the national ID keyed hash (UDDP_MATCH_NATIONAL_ID), any state.
            "services/identity-service/matching/config.py",
            "services/identity-service/matching/run.py",
            // Track A: generic rules/scheme integration, identical for every department/state.
            "common-core/src/main/java/gov/uddp/core/golden/DmnSurvivorshipRules.java",
            "common-core/src/main/java/gov/uddp/core/golden/GoldenResidentService.java",
            "common-core/src/main/java/gov/uddp/core/golden/SurvivorshipEngine.java",
            "common-core/src/main/java/gov/uddp/core/scheme/SchemeEligibilityService.java",
            "common-core/src/main/java/gov/uddp/core/scheme/SchemePackService.java",
            // Track A6: generic GO-to-draft eligibility drafting (DRAFT only, off by default), for every scheme alike.
            "common-core/src/main/java/gov/uddp/core/scheme/GoEligibilityDraftService.java",
            // Track A4: idempotent import of the platform catalogue and pack scheme crosswalks, for every scheme alike.
            "common-core/src/main/java/gov/uddp/core/scheme/SchemeCatalogueImporter.java",
            // Track A5: data availability, impact preview and eligibility recompute after Golden builds; generic.
            "common-core/src/main/java/gov/uddp/core/scheme/SchemeImpactService.java",
            "common-core/src/main/java/gov/uddp/core/golden/GoldenBuildCompletedEvent.java",
            "common-core/src/main/java/gov/uddp/core/statepack/StatePackManifest.java",
            "common-core/src/main/java/gov/uddp/core/validation/ResidentValidator.java",
            // Track E: registry-driven identifiers (identifiers.<TYPE>), identity policy ladder, family anchors and
            // UDDP IDs; every identifier behaviour comes from configuration, identical for every state.
            "common-core/src/main/java/gov/uddp/core/canonical/CanonicalFamilyProcessor.java",
            "common-core/src/main/java/gov/uddp/core/golden/GoldenFamilyBuilder.java",
            // Track F: LGD geography from a loaded master (reference sets, geography targets, level-aware impact);
            // geography data comes only from files loaded through the LGD adapter, identical for every state.
            "common-core/src/main/java/gov/uddp/core/validation/PlatformReferenceData.java",
            // Shaping layer: the three mapping engines read tidy records from ShapingEngine instead of the raw CSV
            // parser (ResidentMappingEngine and CanonicalResidentProcessingService are listed above).
            "common-core/src/main/java/gov/uddp/core/mapping/FamilyMappingEngine.java",
            "common-core/src/main/java/gov/uddp/core/mapping/BenefitMappingEngine.java",
            // ADR-0005 re-baseline (2026-10-29, docs/decisions/LOG.md): later generic changes, identical for every state.
            // Application and Asset domains: generic canonical processors and mapping engines.
            "common-core/src/main/java/gov/uddp/core/canonical/CanonicalApplicationProcessor.java",
            "common-core/src/main/java/gov/uddp/core/canonical/CanonicalAssetProcessor.java",
            "common-core/src/main/java/gov/uddp/core/mapping/ApplicationMappingEngine.java",
            "common-core/src/main/java/gov/uddp/core/mapping/AssetMappingEngine.java",
            // Chunked canonical processing with resume and run progress; unmapped benefit codes with reprocessing.
            "common-core/src/main/java/gov/uddp/core/canonical/RawBatchRows.java",
            "common-core/src/main/java/gov/uddp/core/canonical/RawRecordRepository.java",
            "common-core/src/main/java/gov/uddp/core/canonical/PostgresRawRecordRepository.java",
            "common-core/src/main/java/gov/uddp/core/canonical/ProcessingProgress.java",
            "common-core/src/main/java/gov/uddp/core/canonical/CanonicalBenefitProcessor.java",
            "common-core/src/main/java/gov/uddp/core/validation/DqRepository.java",
            // Shaping: xlsx rows keep their sheet in the source record key; generic Excel ingest and row reader.
            "common-core/src/main/java/gov/uddp/core/canonical/SourceRecordKey.java",
            "common-core/src/main/java/gov/uddp/core/mapping/RawRowReader.java",
            // Unreadable rows carry their own reason code (UNREADABLE_ROW), 2026-11-06.
            "common-core/src/main/java/gov/uddp/core/mapping/RawCsvRecordParser.java",
            "services/ingestion-service/hop/pipelines/generic-excel-ingest.hpl",
            // WP7d: every landed row keeps its physical line in the delivered file (raw_record.source_line, V111).
            "services/ingestion-service/hop/pipelines/generic-file-ingest.hpl",
            // Held records: reason codes on issues and processing errors, held rows counted in the summary.
            "common-core/src/main/java/gov/uddp/core/canonical/CanonicalProcessingError.java",
            "common-core/src/main/java/gov/uddp/core/canonical/CanonicalProcessingSummary.java",
            "common-core/src/main/java/gov/uddp/core/canonical/PostgresCanonicalResidentRepository.java",
            "common-core/src/main/java/gov/uddp/core/validation/ReasonCodes.java",
            "common-core/src/main/java/gov/uddp/core/validation/ValidationIssue.java");
    /**
     * The engines moved, unchanged, from common-core to the engine modules, and the rest of common-core to
     * edge-service (MSA); every location is protected and compared under its former common-core path.
     */
    private static final String MOVED_FROM = "common-core/src/main/java/";
    private static final List<String> ENGINE_MODULES = List.of("engine", "canonical-engine", "golden-engine",
            "matching-engine", "studio-engine", "services/edge-service");
    private static final String[] PROTECTED_PATHS = {
        "services/edge-service/src/main/java/gov/uddp/core/mapping",
        "services/edge-service/src/main/java/gov/uddp/core/canonical",
        "services/edge-service/src/main/java/gov/uddp/core/validation",
        "services/edge-service/src/main/java/gov/uddp/core/golden",
        "services/edge-service/src/main/java/gov/uddp/core/statepack",
        "services/edge-service/src/main/java/gov/uddp/core/scheme",
        "common-core/src/main/java/gov/uddp/core/mapping",
        "common-core/src/main/java/gov/uddp/core/canonical",
        "common-core/src/main/java/gov/uddp/core/validation",
        "common-core/src/main/java/gov/uddp/core/golden",
        "common-core/src/main/java/gov/uddp/core/statepack",
        "common-core/src/main/java/gov/uddp/core/scheme",
        "engine/src/main/java/gov/uddp/core/mapping",
        "engine/src/main/java/gov/uddp/core/canonical",
        "engine/src/main/java/gov/uddp/core/validation",
        "engine/src/main/java/gov/uddp/core/golden",
        "engine/src/main/java/gov/uddp/core/statepack",
        "engine/src/main/java/gov/uddp/core/scheme",
        "canonical-engine/src/main/java/gov/uddp/core/mapping",
        "canonical-engine/src/main/java/gov/uddp/core/canonical",
        "canonical-engine/src/main/java/gov/uddp/core/validation",
        "canonical-engine/src/main/java/gov/uddp/core/golden",
        "canonical-engine/src/main/java/gov/uddp/core/statepack",
        "canonical-engine/src/main/java/gov/uddp/core/scheme",
        "golden-engine/src/main/java/gov/uddp/core/mapping",
        "golden-engine/src/main/java/gov/uddp/core/canonical",
        "golden-engine/src/main/java/gov/uddp/core/validation",
        "golden-engine/src/main/java/gov/uddp/core/golden",
        "golden-engine/src/main/java/gov/uddp/core/statepack",
        "golden-engine/src/main/java/gov/uddp/core/scheme",
        // The matcher and the Hop templates, at their former and current paths (a move shows as a rename).
        "matching/config.py",
        "matching/run.py",
        "hop/pipelines",
        "services/identity-service/matching/config.py",
        "services/identity-service/matching/run.py",
        "services/ingestion-service/hop/pipelines"
    };
    private static final Pattern STATE_FILE_NAME = Pattern.compile("(?i)state[-_]?\\d+|\\bst\\d+\\b");

    @Test
    void genericEnginesMatchingAndHopTemplateAreUnchangedSinceTheBaseline() throws Exception {
        var command = new ArrayList<>(List.of("git", "diff", "-M", "--name-status", BASELINE, "--"));
        command.addAll(List.of(PROTECTED_PATHS));
        var process = new ProcessBuilder(command).directory(ROOT.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String changed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), changed);
        List<String> unexpected = new ArrayList<>();
        for (String line : changed.isEmpty() ? List.<String>of() : List.of(changed.split("\\R"))) {
            String[] parts = line.split("\t");
            if (parts[0].equals("R100")) {
                continue; // moved to the engine module unchanged
            }
            unexpected.add(movedBack(parts[parts.length - 1]));
        }
        unexpected.removeAll(STUDIO_GENERIC_CHANGES);
        assertEquals(List.of(), unexpected, "Protected generic code changed since " + BASELINE
                + " outside the listed engine changes; onboarding a state must stay configuration-only");
        List<String> added = new ArrayList<>(untracked().stream().map(M10PortabilityGuardTest::movedBack).toList());
        added.removeAll(STUDIO_GENERIC_CHANGES);
        assertEquals(List.of(), added, "New files in protected generic paths");
    }

    @Test
    void theOnlyHopPipelinesAreTheGenericOnes() throws IOException {
        try (Stream<Path> files = Files.list(ROOT.resolve("services/ingestion-service/hop/pipelines"))) {
            assertEquals(List.of("generic-db-ingest.hpl", "generic-excel-ingest.hpl", "generic-file-ingest.hpl"),
                    files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".hpl")).sorted().toList(),
                    "State-specific Hop pipelines are not allowed");
        }
    }

    @Test
    void productionCodeNeverNamesAConfiguredState() throws IOException {
        Set<String> names = configuredStateNames();
        Pattern named = Pattern.compile("(?i)(?<![A-Za-z0-9])(" + String.join("|",
                names.stream().map(Pattern::quote).toList()) + ")(?![A-Za-z0-9])");
        List<String> violations = new ArrayList<>();
        for (Path file : productionFiles()) {
            String relative = ROOT.relativize(file).toString().replace('\\', '/');
            if (STATE_FILE_NAME.matcher(file.getFileName().toString()).find()) {
                violations.add(relative + ": state-specific file name");
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = named.matcher(lines.get(i));
                if (m.find()) {
                    violations.add(relative + ":" + (i + 1) + ": names state '" + m.group(1) + "'");
                }
            }
        }
        assertEquals(List.of(), violations, "State-specific production code; put state differences in State Pack YAML");
    }

    /** Pack IDs, state codes and their compact forms (state-4 -> state4) from every State Pack directory. */
    private static Set<String> configuredStateNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (String directory : List.of("services/statepack-service/seed/state-packs",
                "services/statepack-service/seed/m10-state-packs", "studio-packs", "services/edge-service/src/test/fixtures/state-packs",
                "services/edge-service/src/test/fixtures/family-packs", "services/edge-service/src/test/fixtures/benefit-packs")) {
            Path packs = ROOT.resolve(directory);
            if (!Files.isDirectory(packs)) {
                continue;
            }
            try (Stream<Path> manifests = Files.list(packs).map(pack -> pack.resolve("state.yaml")).filter(Files::exists)) {
                for (Path manifest : manifests.toList()) {
                    for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                        String[] parts = line.split(":", 2);
                        if (parts.length == 2 && (parts[0].strip().equals("packId") || parts[0].strip().equals("stateCode"))) {
                            String value = parts[1].strip().toLowerCase(Locale.ROOT);
                            names.add(value);
                            names.add(value.replace("-", ""));
                        }
                    }
                }
            }
        }
        if (names.size() < 8) {
            throw new IllegalStateException("Expected the configured State Packs to be readable, found " + names);
        }
        return names;
    }

    private static List<Path> productionFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        Path migrations = ROOT.resolve("services/edge-service/src/main/resources/db/migration");
        for (String module : List.of("services/edge-service/src/main", "engine/src/main", "canonical-engine/src/main",
                "golden-engine/src/main", "matching-engine/src/main",
                "studio-engine/src/main")) {
            try (Stream<Path> java = Files.walk(ROOT.resolve(module))) {
                java.filter(Files::isRegularFile)
                        // Historical migrations are immutable upgrade records. They may contain legacy sample pack IDs
                        // required to migrate old installations and are not runtime state-specific application logic.
                        .filter(file -> !file.startsWith(migrations))
                        .forEach(files::add);
            }
        }
        try (Stream<Path> python = Files.list(ROOT.resolve("services/identity-service/matching"))) {
            python.filter(file -> file.toString().endsWith(".py"))
                    .filter(file -> !file.getFileName().toString().startsWith("test_"))
                    .filter(file -> !file.getFileName().toString().equals("conftest.py"))
                    // Track B synthetic-data generator deliberately emits the repository's sample State Packs. It is
                    // a development/training fixture generator, not runtime matching logic used to onboard a state.
                    .filter(file -> !file.getFileName().toString().equals("synth.py"))
                    .forEach(files::add);
        }
        return files;
    }

    /** An engine file under its former common-core path, so the listed engine changes keep applying. */
    private static String movedBack(String path) {
        for (String module : ENGINE_MODULES) {
            String prefix = module + "/src/main/java/";
            if (path.startsWith(prefix)) {
                return MOVED_FROM + path.substring(prefix.length());
            }
        }
        return path;
    }

    private static List<String> untracked() throws Exception {
        var command = new ArrayList<>(List.of("git", "ls-files", "--others", "--exclude-standard", "--"));
        command.addAll(List.of(PROTECTED_PATHS));
        var process = new ProcessBuilder(command).directory(ROOT.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), output);
        return output.isEmpty() ? List.of() : List.of(output.split("\\R"));
    }
}
