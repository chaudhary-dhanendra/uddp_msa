package gov.uddp.core.studio;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.config.StudioProperties;
import gov.uddp.core.onboarding.DatabaseSources;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.security.SecretRedactor;
import gov.uddp.core.setup.DatabaseConnectionSettings;
import gov.uddp.core.setup.LocalSettingsStore;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackLoadException;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.studio.StudioMetadata.DefaultValidation;
import gov.uddp.core.studio.StudioModels.Approval;
import gov.uddp.core.studio.StudioModels.Crosswalk;
import gov.uddp.core.studio.StudioModels.Draft;
import gov.uddp.core.studio.StudioModels.DraftConfiguration;
import gov.uddp.core.studio.StudioModels.DraftDetails;
import gov.uddp.core.studio.StudioModels.DraftStatus;
import gov.uddp.core.studio.StudioModels.GeneratedFile;
import gov.uddp.core.studio.StudioModels.GeneratedPack;
import gov.uddp.core.studio.StudioModels.Matching;
import gov.uddp.core.studio.StudioModels.MappingRule;
import gov.uddp.core.studio.StudioModels.OnboardStarted;
import gov.uddp.core.studio.StudioModels.Preview;
import gov.uddp.core.studio.StudioModels.Profile;
import gov.uddp.core.studio.StudioModels.Publication;
import gov.uddp.core.studio.StudioModels.Rules;
import gov.uddp.core.studio.StudioModels.Source;
import gov.uddp.core.studio.StudioModels.StateEntry;
import gov.uddp.core.studio.StudioModels.StudioStatus;
import gov.uddp.core.studio.StudioModels.Suggestions;
import gov.uddp.core.studio.StudioModels.ValidationRule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * The Onboarding Studio: drafts, upload and profiling, suggestions, dry-run preview, publication of a State Pack in
 * the existing spec format into a versioned database document (validated by the existing {@link StatePackLoader},
 * registered DRAFT -> VALIDATED -> ACTIVE and available without a restart), and onboarding runs. Nothing here is
 * state-specific; every state is a draft's configuration.
 */
@Service
public class StudioService {
    private static final Logger log = LoggerFactory.getLogger(StudioService.class);
    private static final Pattern PACK_ID = Pattern.compile("[a-z][a-z0-9-]{1,40}");
    private static final Pattern STATE_CODE = Pattern.compile("[A-Z][A-Z0-9]{1,9}");
    private static final Pattern SOURCE_ID = Pattern.compile("[a-z][a-z0-9-]{0,40}");
    static final int PREVIEW_ROWS = 20;
    static final int PUBLISH_CHECK_ROWS = 10_000;
    /** Profiling reads the first rows and a random sample of the rest, however large the file is. */
    static final int PROFILE_HEAD_ROWS = 1_000;
    static final int PROFILE_RANDOM_ROWS = 1_000;

    private final StudioWorkspace workspace;
    private final StudioProperties studio;
    private final Path repositoryRoot;
    private final StatePackLoader packs;
    private final StudioPublications publications;
    private final StudioOnboarding onboarding;
    private final PostgresConnectionFactory connections;
    private final CsvProfiler profiler;
    private final SuggestionEngine suggestions;
    private final SuggestionReviewStore reviews;
    private final AiMappingAdvisor advisor;
    private final StudioMetadata metadata;
    private final PackGenerator generator;
    private final PreviewService previews;
    private final PortabilityEvidenceService evidence;
    private final SourceRows sourceRows;
    private final LocalSettingsStore store;
    private final StatePackApprovalStore approvals;

    public StudioService(StudioWorkspace workspace, StudioProperties studio, StatePackProperties properties,
                         StatePackLoader packs, StudioPublications publications, StudioOnboarding onboarding,
                         PostgresConnectionFactory connections, CsvProfiler profiler, SuggestionEngine suggestions,
                         StudioMetadata metadata, PackGenerator generator, PreviewService previews,
                         PortabilityEvidenceService evidence, SourceRows sourceRows,
                         LocalSettingsStore store, StatePackApprovalStore approvals,
                         SuggestionReviewStore reviews, AiMappingAdvisor advisor) {
        this.workspace = workspace;
        this.studio = studio;
        this.repositoryRoot = properties.repositoryRoot();
        this.packs = packs;
        this.publications = publications;
        this.onboarding = onboarding;
        this.connections = connections;
        this.profiler = profiler;
        this.suggestions = suggestions;
        this.reviews = reviews;
        this.advisor = advisor;
        this.metadata = metadata;
        this.generator = generator;
        this.previews = previews;
        this.evidence = evidence;
        this.sourceRows = sourceRows;
        this.store = store;
        this.approvals = approvals;
    }

    public StudioStatus status() {
        return new StudioStatus(true, "postgresql:state_pack_document", "postgresql:state_pack_document",
                studio.getUploadLimitBytes(), goldenDataExists());
    }

    // ---- drafts ---------------------------------------------------------------------------------------------

    public List<StateEntry> states() {
        List<StateEntry> states = new ArrayList<>();
        for (Draft draft : workspace.all()) {
            String runStatus = draft.lastRunId() == null ? null : onboarding.runStatus(draft.lastRunId());
            states.add(new StateEntry(draft.draftId(), draft.packId(), draft.stateCode(), draft.displayName(),
                    draft.status(), draft.published() == null ? null : draft.published().version(),
                    draft.source() == null ? null : draft.source().fileName(), draft.lastRunId(), runStatus,
                    draft.updatedAt()));
        }
        return states;
    }

    public Draft draft(String draftId) {
        Draft loaded = workspace.find(draftId).orElseThrow(() -> new StudioNotFoundException("Unknown draft: " + draftId));
        if (loaded.source() == null) return loaded;
        List<MappingRule> mappings = normaliseGeographyCrosswalks(loaded.mappings());
        if (mappings.equals(loaded.mappings())) return loaded;
        List<Crosswalk> crosswalks = new ArrayList<>(loaded.crosswalks());
        for (MappingRule mapping : mappings) {
            if (mapping.crosswalk() != null && crosswalks.stream().noneMatch(c -> mapping.crosswalk().equals(c.id()))) {
                String codeSet = metadata.target(mapping.entity(), mapping.target()).map(StudioMetadata.Target::codeSet)
                        .orElse(mapping.crosswalk());
                crosswalks.add(new Crosswalk(mapping.crosswalk(), codeSet, List.of()));
            }
        }
        Draft normalized = loaded.withConfiguration(mappings,
                suggestBlankCrosswalks(loaded, mappings, crosswalks), loaded.rules(), now());
        workspace.save(normalized);
        return normalized;
    }

    public synchronized Draft create(DraftDetails details) {
        validate(details, null);
        OffsetDateTime now = now();
        Draft draft = new Draft(UUID.randomUUID().toString(), details.packId().trim(), details.stateCode().trim(),
                details.displayName().trim(), "1.0.0", DraftStatus.DRAFT, now, now, null, List.of(), List.of(),
                null, null, null, null);
        workspace.save(draft);
        return draft;
    }

    public synchronized Draft updateDetails(String draftId, DraftDetails details) {
        Draft draft = draft(draftId);
        requireNotPending(draft);
        if (draft.published() != null && !draft.packId().equals(details.packId().trim())) {
            throw new StudioConflictException("A published pack keeps its pack ID");
        }
        validate(details, draftId);
        Draft updated = draft.withDetails(details.packId().trim(), details.stateCode().trim(),
                details.displayName().trim(), now());
        workspace.save(updated);
        return updated;
    }

    /**
     * Lands the uploaded CSV or .xlsx workbook as the draft's source; rules start from the platform defaults. A
     * workbook loads all its sheets until the user ticks fewer (stored as the source's {@code sheets}).
     */
    public synchronized Draft upload(String draftId, String fileName, InputStream content) {
        Draft draft = draft(draftId);
        requireNotPending(draft);
        Path file = workspace.storeUpload(draftId, fileName, content, studio.getUploadLimitBytes());
        List<String> sheets = null;
        if (RawSheets.isWorkbook(file)) {
            sheets = RawSheets.summary(file, null).stream().map(StudioModels.SheetSummary::name).toList();
            if (sheets.isEmpty()) {
                throw new IllegalArgumentException("The workbook has no rows on any sheet");
            }
        } else {
            profiler.read(file);
        }
        String sourceId = sourceId(file.getFileName().toString());
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Source source = new Source(sourceId, "file", file.getFileName().toString(), file.toString(), size, null,
                List.of(), null, null, null);
        if (sheets != null) {
            source = source.withShaping(null, null, sheets);
        }
        Draft updated = draft.withSource(source, now());
        if (updated.rules() == null) {
            updated = updated.withConfiguration(updated.mappings(), updated.crosswalks(), defaultRules(sourceId), now());
        }
        workspace.save(updated);
        return updated;
    }

    /**
     * Track C1: a new delivery of an already published file source. The file replaces the landing file the published
     * pack points at (same name and location), so the next onboarding run ingests it and change detection reports
     * new / changed / unchanged / removed rows. The pack, its mappings and record key stay as published; a delivery
     * that lacks a mapped column is refused and the landing file is left as it was.
     */
    public synchronized Draft uploadDelivery(String draftId, String fileName, InputStream content) {
        Draft draft = draft(draftId);
        requireNotPending(draft);
        Source source = requireSource(draft);
        if (draft.published() == null || !"file".equals(source.sourceType()) || source.location() == null) {
            throw new StudioConflictException("A new delivery needs a published file source; upload the first file instead");
        }
        Path staged = workspace.storeUpload(draftId + "-delivery", source.fileName(), content, studio.getUploadLimitBytes());
        try {
            List<String> header = RawSheets.isWorkbook(staged)
                    ? sourceRows.read(new Source(source.sourceId(), source.sourceType(), source.fileName(),
                            staged.toString(), null, null, null, null, null, null, source.layout(), source.parse(),
                            source.sheets()), 1).header()
                    : profiler.read(staged).header();
            List<String> missing = new java.util.ArrayList<>();
            for (MappingRule rule : draft.mappings()) {
                if (!header.contains(rule.sourceField()) && !missing.contains(rule.sourceField())) {
                    missing.add(rule.sourceField());
                }
            }
            if (source.recordIdField() != null && !header.contains(source.recordIdField())
                    && !missing.contains(source.recordIdField())) {
                missing.add(source.recordIdField());
            }
            if (!missing.isEmpty()) {
                throw new StudioConflictException("The new delivery lacks mapped column(s): " + String.join(", ", missing)
                        + (fileName == null ? "" : " (" + fileName + ")"));
            }
            Path target = Path.of(source.location());
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.move(staged, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Source delivered = new Source(source.sourceId(), source.sourceType(), source.fileName(), source.location(),
                    Files.size(target), source.recordIdField(), source.recordKeyFields(), source.connectionName(),
                    source.table(), source.query(), source.layout(), source.parse(), source.sheets());
            Draft updated = draft.withSource(delivered, now());
            workspace.save(updated);
            return updated;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException ignored) {
                // the staging folder is cleared by the next delivery
            }
        }
    }

    /** Uses a database table or query as the draft's source (credentials stay in the local settings store). */
    public synchronized Draft useDatabaseSource(String draftId, String connectionName, String table, String query) {
        Draft draft = draft(draftId);
        requireNotPending(draft);
        sourceRows.read(new Source("preview", "database", null, null, null, null, List.of(), connectionName, table,
                query), 1);
        String sourceId = sourceId(table != null && !table.isBlank() ? table.replaceAll(".*\\.", "") : "query");
        Source source = new Source(sourceId, "database", null, null, null, null, List.of(), connectionName,
                blankToNull(table), blankToNull(query));
        Draft updated = draft.withSource(source, now());
        if (updated.rules() == null) {
            updated = updated.withConfiguration(updated.mappings(), updated.crosswalks(), defaultRules(sourceId), now());
        }
        workspace.save(updated);
        return updated;
    }

    // ---- database-table sources (ADR-0016) -----------------------------------------------------------------

    public List<StudioModels.SourceConnection> connections() {
        return store.connections().entrySet().stream()
                .map(e -> new StudioModels.SourceConnection(e.getKey(), e.getValue().host(), e.getValue().port(),
                        e.getValue().database(), e.getValue().user(), true))
                .toList();
    }

    /** Tests and saves a named connection; the password is stored encrypted and never returned. */
    public synchronized StudioModels.SourceConnection saveConnection(String name,
                                                                   StudioModels.SourceConnectionRequest request) {
        DatabaseSources.requireName(name);
        if (request.port() == null) {
            throw new IllegalArgumentException("Port is required");
        }
        String password = request.password();
        if (password == null || password.isEmpty()) {
            var saved = store.connections().get(name);
            password = saved == null ? null : saved.password();
        }
        DatabaseConnectionSettings settings = new DatabaseConnectionSettings(request.host(), request.port(),
                request.database(), request.user(), password).validated();
        try (var c = java.sql.DriverManager.getConnection(settings.jdbcUrl(), settings.user(), settings.password())) {
            c.isValid(5);
        } catch (java.sql.SQLException e) {
            throw new IllegalArgumentException("Cannot connect: "
                    + SecretRedactor.redact(e.getMessage(), List.of(settings.password())));
        }
        store.saveConnection(name, settings);
        return new StudioModels.SourceConnection(name, settings.host(), settings.port(), settings.database(),
                settings.user(), true);
    }

    public Profile profile(String draftId) {
        Draft draft = draft(draftId);
        Source source = requireSource(draft);
        Profile profile = profiler.profile(sourceRows.sample(source, PROFILE_HEAD_ROWS, PROFILE_RANDOM_ROWS));
        if ("file".equals(source.sourceType()) && source.location() != null
                && RawSheets.isWorkbook(Path.of(source.location()))) {
            profile = profile.withSheets(RawSheets.summary(Path.of(source.location()), source.sheets()));
        }
        return profile;
    }

    public Suggestions suggestions(String draftId) {
        Draft draft = draft(draftId);
        Profile profile = profile(draftId);
        List<LoadedStatePack> published = publications.published().stream()
                .filter(p -> !p.manifest().packId().equals(draft.packId()))
                .toList();
        Suggestions suggested = advisor.combine(profile, suggestions.suggest(profile, published));
        Source source = requireSource(draft);
        if ("file".equals(source.sourceType()) && source.location() != null) {
            suggested = suggested.withLayoutProposals(suggestions.layoutProposals(RawSheets.read(Path.of(source.location()))));
        }
        reviews.record(draft.draftId(), draft.packId(), draft.stateCode(), suggested);
        return suggested;
    }

    public synchronized Draft saveConfiguration(String draftId, DraftConfiguration configuration) {
        Draft draft = draft(draftId);
        requireNotPending(draft);
        Source source = requireSource(draft);
        List<String> columns = sourceRows.read(source, 1).header();
        for (MappingRule rule : configuration.mappings()) {
            if (!columns.contains(rule.sourceField())) {
                throw new IllegalArgumentException("Unknown source column: " + rule.sourceField());
            }
            boolean extension = rule.target() != null && rule.target().startsWith("extensions.")
                    && rule.target().length() > "extensions.".length();
            if (!extension && metadata.target(rule.entity(), rule.target()).isEmpty()) {
                throw new IllegalArgumentException("Unsupported " + rule.entity() + " target: " + rule.target());
            }
        }
        String recordId = configuration.recordIdField() == null ? source.recordIdField() : configuration.recordIdField();
        if (recordId != null && !columns.contains(recordId)) {
            throw new IllegalArgumentException("Unknown record ID column: " + recordId);
        }
        List<String> keys = configuration.recordKeyFields() == null ? source.recordKeyFields() : configuration.recordKeyFields();
        Source updatedSource = source.withRecordKey(recordId, keys);
        List<MappingRule> mappings = normaliseGeographyCrosswalks(configuration.mappings());
        List<Crosswalk> crosswalks = new ArrayList<>(configuration.crosswalks());
        for (MappingRule mapping : mappings) {
            if (mapping.crosswalk() != null && crosswalks.stream().noneMatch(c -> mapping.crosswalk().equals(c.id()))) {
                String codeSet = metadata.target(mapping.entity(), mapping.target()).map(StudioMetadata.Target::codeSet)
                        .orElse(mapping.crosswalk());
                crosswalks.add(new Crosswalk(mapping.crosswalk(), codeSet, List.of()));
            }
        }
        Draft updated = draft.withSource(updatedSource, now())
                .withConfiguration(mappings,
                        suggestBlankCrosswalks(draft, mappings, crosswalks),
                        configuration.rules(), now());
        workspace.save(updated);
        reviews.clear(draftId);
        return updated;
    }

    /** Adds newly declared geography-name crosswalks to legacy mapping rules without replacing user choices. */
    private List<MappingRule> normaliseGeographyCrosswalks(List<MappingRule> mappings) {
        return mappings.stream().map(mapping -> {
            if (mapping.crosswalk() != null || mapping.target() == null || !mapping.target().startsWith("geography.")
                    || !mapping.target().endsWith(".name")) {
                return mapping;
            }
            return metadata.target(mapping.entity(), mapping.target()).filter(target -> target.codeSet() != null)
                    .map(target -> new MappingRule(mapping.sourceField(), mapping.entity(), mapping.target(),
                            mapping.conversion(), SuggestionEngine.crosswalkId(target.codeSet()), mapping.required()))
                    .orElse(mapping);
        }).toList();
    }

    /**
     * A crosswalk sent with no value chosen at all belongs to a target picked by hand on the Map step: it gets the
     * suggestions the Map step would have offered (values that are already codes map to themselves). A crosswalk with
     * any value chosen is the person's and is kept as sent.
     */
    private List<Crosswalk> suggestBlankCrosswalks(Draft draft, List<MappingRule> mappings, List<Crosswalk> crosswalks) {
        if (crosswalks.stream().noneMatch(StudioService::blank)) {
            return List.copyOf(crosswalks);
        }
        Profile profile = profiler.profile(sourceRows.sample(requireSource(draft), PROFILE_HEAD_ROWS, PROFILE_RANDOM_ROWS));
        List<LoadedStatePack> published = publications.published().stream()
                .filter(p -> !p.manifest().packId().equals(draft.packId()))
                .toList();
        List<Crosswalk> result = new ArrayList<>();
        for (Crosswalk crosswalk : crosswalks) {
            String column = mappings.stream()
                    .filter(m -> crosswalk.id().equals(m.crosswalk()))
                    .map(MappingRule::sourceField)
                    .findFirst()
                    .orElse(null);
            var columnProfile = profile.columns().stream().filter(c -> c.name().equals(column)).findFirst();
            result.add(blank(crosswalk) && columnProfile.isPresent()
                    ? suggestions.crosswalkFor(columnProfile.get(), crosswalk.codeSet(), crosswalk.id(), published)
                    : crosswalk);
        }
        return List.copyOf(result);
    }

    private static boolean blank(Crosswalk crosswalk) {
        return crosswalk.entries().isEmpty()
                || crosswalk.entries().stream().allMatch(e -> e.targetValue() == null && e.basis() == null);
    }

    // ---- shaping (the Shape step) -------------------------------------------------------------------------------

    private static final com.fasterxml.jackson.databind.ObjectMapper FLOW =
            new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());

    private ShapePreviewService shapePreviews;

    @org.springframework.beans.factory.annotation.Autowired
    public void setShapePreviews(ShapePreviewService shapePreviews) {
        this.shapePreviews = shapePreviews;
    }

    /** The layout and parse proposals for the draft's file, as steps ready to save, and the steps it keeps now. */
    public StudioModels.ShapeState shaping(String draftId) {
        Draft draft = draft(draftId);
        Source source = requireSource(draft);
        List<Map<String, Object>> layout = source.layout() == null ? List.of() : source.layout();
        List<Map<String, Object>> parse = source.parse() == null ? List.of() : source.parse();
        List<StudioModels.ShapeProposal> proposals = new java.util.ArrayList<>();
        List<String> sheets = new java.util.ArrayList<>();
        if ("file".equals(source.sourceType()) && source.location() != null) {
            var all = RawSheets.read(Path.of(source.location()));
            all.stream().map(r -> r.sheet()).filter(java.util.Objects::nonNull).distinct().forEach(sheets::add);
            // Proposals come from the sheets the user chose to load (all of them until they untick some).
            var rows = source.sheets() == null || source.sheets().isEmpty() ? all
                    : all.stream().filter(r -> r.sheet() == null || source.sheets().contains(r.sheet())).toList();
            for (StudioModels.LayoutProposal p : suggestions.layoutProposals(rows)) {
                if (!"layout".equals(p.section()) && !"parse".equals(p.section())) {
                    continue;
                }
                Map<String, Object> step = normalisedStep(p);
                boolean enabled = ("layout".equals(p.section()) ? layout : parse).contains(step);
                proposals.add(new StudioModels.ShapeProposal(p.section(), p.kind(), p.sheet(), p.description(),
                        p.evidenceCount(), p.evidenceTotal(), step, PackGenerator.flow(step), enabled));
            }
        }
        return new StudioModels.ShapeState(proposals, layout, parse, source.sheets() == null ? sheets : source.sheets());
    }

    /** The profiler writes {@code op: ditto}; a pack names versions, and aliases are logical name to variants. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalisedStep(StudioModels.LayoutProposal proposal) {
        try {
            Map<String, Object> step = new java.util.LinkedHashMap<>(FLOW.readValue(proposal.yaml(), Map.class));
            for (String key : List.of("op", "parser")) {
                if (step.get(key) instanceof String name && !name.contains("@")) {
                    step.put(key, name + "@1.0");
                }
            }
            if (step.get("aliases") instanceof Map<?, ?> aliases
                    && aliases.values().stream().allMatch(v -> v instanceof String)) {
                Map<String, Object> byLogical = new java.util.LinkedHashMap<>();
                aliases.forEach((variant, known) -> ((List<String>) byLogical
                        .computeIfAbsent(String.valueOf(known), k -> new java.util.ArrayList<String>()))
                        .add(String.valueOf(variant)));
                step.put("aliases", byLogical);
            }
            return step;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read the proposed step " + proposal.yaml(), e);
        }
    }

    /** Before/after of the first rows with the given steps; nothing is saved. */
    public StudioModels.ShapePreview shapePreview(String draftId, StudioModels.ShapingConfig config) {
        return shapePreviews.preview(requireSource(draft(draftId)), config);
    }

    /** Keeps the accepted layout steps and parsers in the draft's source; the pack's source YAML gets them on publish. */
    public synchronized Draft saveShaping(String draftId, StudioModels.ShapingConfig config) {
        Draft draft = draft(draftId);
        requireNotPending(draft);
        Source source = requireSource(draft);
        if (!"file".equals(source.sourceType())) {
            throw new StudioConflictException("Shaping applies to uploaded file sources");
        }
        shapePreviews.validate(config);
        List<Map<String, Object>> layout = source.location() != null && RawSheets.isWorkbook(Path.of(source.location()))
                ? ShapePreviewService.workbookLayout(config.layout()) : config.layout();
        Draft updated = draft.withSource(source.withShaping(List.copyOf(layout), List.copyOf(config.parse()),
                config.sheets() == null || config.sheets().isEmpty() ? null : List.copyOf(config.sheets())), now());
        workspace.save(updated);
        reviews.clear(draftId);
        return updated;
    }

    // ---- preview and publication ------------------------------------------------------------------------------

    public GeneratedPack generated(String draftId) {
        Draft draft = draft(draftId);
        String version = nextVersion(draft);
        return new GeneratedPack(draft.packId(), version, generate(draft, version));
    }

    private Map<String, String> fileMap(Draft draft, String version) {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        generate(draft, version).forEach(file -> files.put(file.path(), file.content()));
        return files;
    }

    public Preview preview(String draftId, Integer rows) {
        Draft draft = draft(draftId);
        return preview(draft, rows == null ? PREVIEW_ROWS : Math.max(1, Math.min(rows, 500)));
    }

    /**
     * Validates the draft and generates its next version. With maker-checker on (the default, ADR-0021) the version
     * is only submitted: it waits as PENDING_APPROVAL until a different user approves it; otherwise it is published
     * at once.
     */
    public synchronized Draft publish(String draftId) {
        Draft draft = draft(draftId);
        requireNotPending(draft);
        if (onboarding.inProgress()) {
            throw new StudioConflictException("An onboarding run is in progress; publish when it has finished");
        }
        Preview check = preview(draft, PUBLISH_CHECK_ROWS);
        if (check.packError() != null) {
            throw new StudioConflictException("The draft is not a valid State Pack: " + check.packError());
        }
        if (check.blockingErrors() > 0) {
            throw new StudioConflictException("Publishing is blocked by " + check.blockingErrors()
                    + " blocking error(s) in the preview; fix the mapping, crosswalks or rules first");
        }
        requireAgreeingThresholds(draft);
        String version = nextVersion(draft);
        List<GeneratedFile> files = generate(draft, version);
        if (draft.published() != null && draft.published().version().equals(version)) {
            return draft;
        }
        Path staging = writeTemporary(draft, files);
        LoadedStatePack persisted;
        try {
            persisted = packs.load(staging);
        } finally {
            deleteTree(staging.getParent());
        }
        List<String> paths = files.stream().map(GeneratedFile::path).toList();
        if (!publications.makerChecker() || publications.autoApproved(draft, persisted, actor())) {
            return applyPublication(draft, version, paths, persisted, null);
        }
        Approval approval = approvals.submit(draft.draftId(), draft.packId(), draft.stateCode(), version, persisted,
                paths, actor());
        publications.submitted(approval, fileMap(draft, version));
        Draft submitted = draft.withApproval(DraftStatus.PENDING_APPROVAL, approval, now());
        workspace.save(submitted);
        log.info("Studio submitted {} {} for approval", draft.packId(), version);
        return submitted;
    }

    /** Submissions, newest first; {@code status} null lists all. */
    public List<Approval> approvals(String status) {
        return approvals.list(status);
    }

    public Approval approval(String approvalId) {
        return approvals.find(approvalId).orElseThrow(() -> new StudioNotFoundException("Unknown approval: " + approvalId));
    }

    /**
     * A different user approves a pending submission: the version exactly as submitted is persisted, registered and
     * activated. The submitter can never approve their own submission.
     */
    public synchronized Draft approve(String approvalId, String reason) {
        Approval pending = requirePending(approvalId);
        String approver = requireOtherUser(pending);
        if (onboarding.inProgress()) {
            throw new StudioConflictException("An onboarding run is in progress; approve when it has finished");
        }
        Draft draft = draft(pending.draftId());
        LoadedStatePack pack = approvals.document(approvalId);
        Approval decided = approvals.decide(approvalId, StatePackApprovalStore.APPROVED, approver, blankToNull(reason));
        Draft published = applyPublication(draft, pending.version(), pending.files(), pack, decided);
        log.info("Studio approved {} {} (submitted by {}, approved by {})", pending.packId(), pending.version(),
                pending.submittedBy(), approver);
        return published;
    }

    /** A different user rejects a pending submission with a reason; the draft can be edited and submitted again. */
    public synchronized Draft reject(String approvalId, String reason) {
        Approval pending = requirePending(approvalId);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A rejection needs a reason");
        }
        String approver = requireOtherUser(pending);
        Draft draft = draft(pending.draftId());
        publications.rejected(pending, approver, reason.strip(), fileMap(draft, pending.version()));
        Approval decided = approvals.decide(approvalId, StatePackApprovalStore.REJECTED, approver, reason.strip());
        DraftStatus previous = draft.published() == null ? DraftStatus.DRAFT
                : draft.lastRunId() != null ? DraftStatus.ONBOARDED : DraftStatus.PUBLISHED;
        Draft rejected = draft.withApproval(previous, decided, now());
        workspace.save(rejected);
        log.info("Studio rejected {} {}", pending.packId(), pending.version());
        return rejected;
    }

    /** Persists, registers and activates an approved (or, without maker-checker, a validated) version. */
    private Draft applyPublication(Draft draft, String version, List<String> paths, LoadedStatePack persisted,
                                   Approval approval) {
        String location = publications.activate(draft, version, persisted, approval, fileMap(draft, version), actor());
        if (evidenceMissing()) {
            evidence.record("before first publication");
        }
        evidence.record(draft.packId());
        Publication publication = new Publication(version, now(), location, paths, "ACTIVE");
        Draft updated = draft.withPublication(version, publication, approval, now());
        workspace.save(updated);
        log.info("Studio published {} {} ({} files)", draft.packId(), version, paths.size());
        return updated;
    }

    private Approval requirePending(String approvalId) {
        Approval approval = approval(approvalId);
        if (!StatePackApprovalStore.PENDING.equals(approval.status())) {
            throw new StudioConflictException("The submission is no longer waiting for approval");
        }
        return approval;
    }

    private static String requireOtherUser(Approval approval) {
        String user = actor();
        if (user.equalsIgnoreCase(approval.submittedBy())) {
            throw new AccessDeniedException("You cannot approve or reject a version you submitted");
        }
        return user;
    }

    private static void requireNotPending(Draft draft) {
        if (draft.status() == DraftStatus.PENDING_APPROVAL) {
            throw new StudioConflictException("The draft is waiting for approval; it cannot change until it is decided");
        }
    }

    /** The signed-in user (platform code acting outside a request is "platform"). */
    private static String actor() {
        String user = CurrentUser.username();
        return user == null ? "platform" : user;
    }

    // ---- onboarding -----------------------------------------------------------------------------------------

    public synchronized OnboardStarted onboard(String draftId, String requestedMode) {
        Draft draft = draft(draftId);
        if (draft.published() == null) {
            throw new StudioConflictException("Publish the pack before onboarding it");
        }
        OnboardStarted started = onboarding.start(draft.packId(), draft.published().version(),
                draft.source().sourceId(), requestedMode);
        Draft running = draft.withRun(started.runId(), draft.status(), now());
        workspace.save(running);
        onboarding.runInBackground(started.runId(), status -> runFinished(draftId, started.runId(), status));
        return started;
    }

    /** Records a Studio-started run's end; a COMPLETED run marks the draft ONBOARDED. */
    public synchronized void runFinished(String draftId, String run, String status) {
        Draft draft = draft(draftId);
        if (run.equals(draft.lastRunId()) && "COMPLETED".equals(status)) {
            workspace.save(draft.withRun(run, DraftStatus.ONBOARDED, now()));
        }
    }

    /** Records a run of the draft's published version started by the edge, where the Studio runs elsewhere (MSA). */
    public synchronized Draft runStarted(String draftId, String run) {
        Draft draft = draft(draftId);
        Draft running = draft.withRun(run, draft.status(), now());
        workspace.save(running);
        return running;
    }

    public boolean goldenDataExists() {
        return onboarding.goldenDataExists();
    }

    public StudioModels.PortabilityEvidence portability() {
        return evidence.evidence(workspace.all());
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private Preview preview(Draft draft, int rows) {
        Source source = requireSource(draft);
        if (draft.rules() == null) {
            return new Preview(0, 0, 0, "The draft has no rules yet", List.of());
        }
        String version = nextVersion(draft);
        Path staging = null;
        // A workbook's rows are read already shaped (its sheets, layout and parse steps applied), so they are mapped
        // with a plain CSV copy of the source; the published pack keeps the workbook source as it is.
        Draft mapped = "file".equals(source.sourceType()) && source.location() != null
                && RawSheets.isWorkbook(Path.of(source.location()))
                ? draft.withSource(new Source(source.sourceId(), source.sourceType(), source.sourceId() + ".csv",
                        source.location(), source.sizeBytes(), source.recordIdField(), source.recordKeyFields(),
                        null, null, null), draft.updatedAt())
                : draft;
        try {
            staging = writeTemporary(mapped, generate(mapped, version));
            LoadedStatePack pack = packs.load(staging);
            var table = sourceRows.read(source, rows);
            return previews.preview(pack, source.sourceId(), table.header(), table.rows());
        } catch (StatePackLoadException | IllegalArgumentException e) {
            return new Preview(0, 0, 0, e.getMessage(), List.of());
        } finally {
            if (staging != null) {
                deleteTree(staging.getParent());
            }
        }
    }

    private List<GeneratedFile> generate(Draft draft, String version) {
        requireSource(draft);
        if (draft.source().recordIdField() == null) {
            throw new IllegalArgumentException("Choose the record ID column on the Map step");
        }
        return generator.generate(draft, version, repositoryRoot);
    }

    /** 1.0.0 for a new pack; the next patch version once the published files would change. */
    private String nextVersion(Draft draft) {
        if (draft.published() == null) {
            return draft.version();
        }
        String current = draft.published().version();
        List<GeneratedFile> files = generator.generate(draft, current, repositoryRoot);
        String[] parts = current.split("\\.");
        return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
    }

    private void requireAgreeingThresholds(Draft draft) {
        Matching matching = draft.rules().matching();
        for (LoadedStatePack pack : publications.published()) {
            if (pack.manifest().packId().equals(draft.packId())) {
                continue;
            }
            for (var definition : pack.matching()) {
                if ("Resident".equals(definition.entity())
                        && (!Objects.equals(definition.thresholds().autoMatch(), matching.autoMatch())
                        || !Objects.equals(definition.thresholds().possibleMatch(), matching.possibleMatch()))) {
                    throw new StudioConflictException("Matching thresholds must agree with published pack "
                            + pack.manifest().packId() + " (autoMatch " + definition.thresholds().autoMatch()
                            + ", possibleMatch " + definition.thresholds().possibleMatch()
                            + "): one matching run compares residents across states");
                }
            }
        }
    }

    private boolean evidenceMissing() {
        return !Files.isRegularFile(workspace.evidenceFile());
    }

    private Rules defaultRules(String sourceId) {
        var defaults = metadata.defaults();
        List<ValidationRule> validation = new ArrayList<>();
        for (DefaultValidation rule : defaults.validation()) {
            validation.add(new ValidationRule(rule.id(), rule.type(), rule.field(), rule.required(),
                    rule.allowedValues(), rule.pattern(), rule.referenceSet(), rule.severity()));
        }
        Matching matching = new Matching(defaults.matching().fields(),
                defaults.matching().thresholds().get("autoMatch"), defaults.matching().thresholds().get("possibleMatch"));
        Map<String, List<String>> survivorship = new LinkedHashMap<>();
        defaults.survivorship().fieldPriority().forEach((field, sources) ->
                survivorship.put(field, sources.stream().map(s -> s.replace("<sourceId>", sourceId)).toList()));
        return new Rules(validation, matching, survivorship);
    }

    private void validate(DraftDetails details, String draftId) {
        if (details.packId() == null || !PACK_ID.matcher(details.packId().trim()).matches()) {
            throw new IllegalArgumentException("Pack ID must be lower case letters, digits and hyphens, e.g. state-5");
        }
        if (details.stateCode() == null || !STATE_CODE.matcher(details.stateCode().trim()).matches()) {
            throw new IllegalArgumentException("State code must be upper case letters and digits, e.g. ST5");
        }
        if (details.displayName() == null || details.displayName().isBlank()) {
            throw new IllegalArgumentException("Display name is required");
        }
        for (Draft other : workspace.all()) {
            if (other.draftId().equals(draftId)) {
                continue;
            }
            if (other.packId().equals(details.packId().trim()) || other.stateCode().equals(details.stateCode().trim())) {
                throw new StudioConflictException("Another draft already uses pack ID " + other.packId()
                        + " or state code " + other.stateCode());
            }
        }
        for (LoadedStatePack pack : publications.published()) {
            boolean ownPack = draftId != null && draft(draftId).packId().equals(pack.manifest().packId());
            if (!ownPack && (pack.manifest().packId().equals(details.packId().trim())
                    || pack.manifest().stateCode().equals(details.stateCode().trim()))) {
                throw new StudioConflictException("A loaded State Pack already uses pack ID "
                        + pack.manifest().packId() + " or state code " + pack.manifest().stateCode());
            }
        }
    }

    private static Source requireSource(Draft draft) {
        if (draft.source() == null) {
            throw new StudioConflictException("Upload a source file (or choose a database table) first");
        }
        return draft.source();
    }

    private static String sourceId(String fileName) {
        String base = fileName.replaceFirst("\\.[^.]+$", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            base = "source-" + base;
        }
        base = base.length() > 40 ? base.substring(0, 40) : base;
        if (!SOURCE_ID.matcher(base).matches()) {
            throw new IllegalArgumentException("Cannot derive a source ID from " + fileName);
        }
        return base;
    }

    private Path writeTemporary(Draft draft, List<GeneratedFile> files) {
        try {
            Path root = Files.createTempDirectory("uddp-studio-");
            Path pack = root.resolve(draft.packId());
            for (GeneratedFile file : files) {
                Path path = pack.resolve(file.path());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.content(), StandardCharsets.UTF_8);
            }
            return pack;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the generated pack", e);
        }
    }

    private static boolean sameFiles(Path directory, List<GeneratedFile> files) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> existing = Files.walk(directory)) {
            long count = existing.filter(Files::isRegularFile).count();
            if (count != files.size()) {
                return false;
            }
            for (GeneratedFile file : files) {
                Path path = directory.resolve(file.path());
                if (!Files.isRegularFile(path) || !Files.readString(path, StandardCharsets.UTF_8).equals(file.content())) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void replaceDirectory(Path source, Path target) {
        try {
            deleteTree(target);
            Files.createDirectories(target.getParent());
            try (Stream<Path> files = Files.walk(source)) {
                for (Path file : files.toList()) {
                    Path destination = target.resolve(source.relativize(file).toString());
                    if (Files.isDirectory(file)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.copy(file, destination);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the pack to " + target, e);
        }
    }

    private static void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + directory, e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
