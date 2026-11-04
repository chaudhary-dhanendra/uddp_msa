package gov.uddp.core.canonical;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.mapping.ResidentMappingEngine;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.validation.CanonicalValidationException;
import gov.uddp.core.validation.ResidentValidator;
import gov.uddp.core.validation.ValidationIssue;
import gov.uddp.core.validation.ValidationIssue.Severity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Generic Raw-to-canonical Resident flow. State Packs, not Java branches, select mapping and validation semantics.
 * Error-severity validation failures are recorded as processing errors and the record is skipped;
 * warning-severity failures are stored against the persisted canonical record.
 * Canonical rows are versioned per source record (M11, ADR-0003): a re-delivery with unchanged content under the same
 * State Pack version is skipped, a changed one (or a new pack version) becomes the next version of that record.
 * When a {@link CanonicalFamilyProcessor} is present (M14), every stored or unchanged Resident delivery is also
 * observed for the households its pack's Family rules name, and, when a {@link CanonicalBenefitProcessor} is present
 * (M15), for the benefit its pack's Benefit rules name.
 */
@Service
public class CanonicalResidentProcessingService {
    private final RawRecordRepository raws;
    private final CanonicalResidentRepository canonicals;
    private final StatePackLoader packs;
    private final ResidentMappingEngine mapper;
    private final ResidentValidator validator;
    private final ObjectMapper json;
    private final gov.uddp.core.validation.DqRepository dq;
    private final gov.uddp.core.validation.PlatformReferenceData references;
    private final SourceRecordKey keys;
    private CanonicalFamilyProcessor families;
    private CanonicalBenefitProcessor benefits;
    private CanonicalApplicationProcessor applications;
    private CanonicalAssetProcessor assets;
    private gov.uddp.core.shaped.ShapedRecordRepository shapedRecords;
    private gov.uddp.core.privacy.RawNationalIdRedactor nationalIdRedactor;
    private gov.uddp.core.reference.ReferenceDataService referenceData;
    private gov.uddp.core.held.HeldRecordRepository held;
    private boolean holdLowConfidence;
    private boolean holdInvalidIdentifiers;

    private gov.uddp.core.events.PlatformEvents events;

    public CanonicalResidentProcessingService(RawRecordRepository raws, CanonicalResidentRepository canonicals,
                                              StatePackLoader packs, ResidentMappingEngine mapper,
                                              ResidentValidator validator, ObjectMapper json) {
        this(raws, canonicals, packs, mapper, validator, json, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CanonicalResidentProcessingService(RawRecordRepository raws, CanonicalResidentRepository canonicals,
                                              StatePackLoader packs, ResidentMappingEngine mapper,
                                              ResidentValidator validator, ObjectMapper json,
                                              gov.uddp.core.validation.DqRepository dq,
                                              gov.uddp.core.validation.PlatformReferenceData references) {
        this.raws = raws;
        this.canonicals = canonicals;
        this.packs = packs;
        this.mapper = mapper;
        this.validator = validator;
        this.json = json;
        this.dq = dq;
        this.references = references;
        this.keys = new SourceRecordKey(new gov.uddp.core.mapping.RawCsvRecordParser(json), mapper.shaping());
    }

    /** Enables Family observation (M14); without it only Residents are processed. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFamilyProcessor(CanonicalFamilyProcessor families) {
        this.families = families;
    }

    /**
     * National ID protection (P1 Part 5b): the mapping engine keeps only keyed hashes, and after a record is processed
     * its national ID columns are redacted in the raw payload.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setNationalIdProtection(gov.uddp.core.privacy.NationalIdProtector protector,
                                        gov.uddp.core.privacy.RawNationalIdRedactor redactor) {
        if (this.mapper.identifiers() == null) {
            this.mapper.setNationalIds(protector);
        }
        this.nationalIdRedactor = redactor;
    }

    /** The leak guard over the mapper's identifier registry (Track E2), else the platform seed types. */
    private gov.uddp.core.privacy.NationalIdLeakGuard leakGuard() {
        var identifiers = mapper.identifiers();
        return identifiers == null ? gov.uddp.core.privacy.NationalIdLeakGuard.platform()
                : gov.uddp.core.privacy.NationalIdLeakGuard.of(identifiers.registry());
    }

    /** Live reference sets (Track F2): {@code lgd-*} REFERENCE_LIST rules read the LGD master once it is loaded. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReferenceData(gov.uddp.core.reference.ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    /** Enables persistence of tidy records for shaped sources; without it they are only used for mapping. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setShapedRecords(gov.uddp.core.shaped.ShapedRecordRepository shapedRecords) {
        this.shapedRecords = shapedRecords;
    }

    /** Enables Benefit observation (M15); without it no benefits are observed. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBenefitProcessor(CanonicalBenefitProcessor benefits) {
        this.benefits = benefits;
    }

    /**
     * Enables held records: a mapped row that fails an ERROR rule or hits a SEND_TO_STEWARD rule is held for a steward
     * instead of rejected. Without it those rows are rejected. Low-confidence shaped fields and INVALID identifiers
     * hold the row only when {@code uddp.processing.hold-low-confidence} / {@code hold-invalid-identifiers} are true.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHeldRecords(gov.uddp.core.held.HeldRecordRepository held,
            @org.springframework.beans.factory.annotation.Value("${uddp.processing.hold-low-confidence:false}")
            boolean holdLowConfidence,
            @org.springframework.beans.factory.annotation.Value("${uddp.processing.hold-invalid-identifiers:false}")
            boolean holdInvalidIdentifiers) {
        this.held = held;
        this.holdLowConfidence = holdLowConfidence;
        this.holdInvalidIdentifiers = holdInvalidIdentifiers;
    }

    /** Enables Asset observation; without it no assets are observed. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAssetProcessor(CanonicalAssetProcessor assets) {
        this.assets = assets;
    }

    /** Enables Application observation; without it no applications are observed. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setApplicationProcessor(CanonicalApplicationProcessor applications) {
        this.applications = applications;
    }

    /** Raw rows read through the cursor at a time. */
    static final int FETCH_SIZE = 5_000;

    private int chunkSize = 5_000;
    private ProcessingProgress progress;
    private java.util.function.IntConsumer afterChunk = number -> { };

    /** Rows per chunk (one transaction each); {@code uddp.processing.chunk-size}, default 5,000. */
    @org.springframework.beans.factory.annotation.Autowired
    public void setChunkSize(
            @org.springframework.beans.factory.annotation.Value("${uddp.processing.chunk-size:5000}") int chunkSize) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("uddp.processing.chunk-size must be at least 1");
        }
        this.chunkSize = chunkSize;
    }

    /** Enables a transaction per chunk and resume after a failure; without it chunks run without a transaction. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProgress(ProcessingProgress progress) {
        this.progress = progress;
    }

    /** Called with the chunk number after each chunk has committed (crash simulation in tests). */
    public void setAfterChunk(java.util.function.IntConsumer afterChunk) {
        this.afterChunk = afterChunk;
    }

    /**
     * Processes the batch in chunks of {@code uddp.processing.chunk-size} rows read through a cursor, one transaction
     * per chunk. A batch whose earlier processing stopped part-way resumes after its last committed chunk; a batch
     * that was fully processed is processed again from the start (its records are then skipped as unchanged).
     * A source record claimed by more than one row of the batch is rejected for every row, wherever the rows are;
     * UNIQUE_WITHIN_BATCH validation compares the rows of one chunk.
     */
    public CanonicalProcessingSummary processBatch(UUID batchId) {
        if (events != null) {
            events.rawBatchLanded(batchId);
        }
        final var referenceValues = referenceValues(true);
        var duplicateKeys = duplicateKeys(batchId);
        int committed = progress == null ? 0 : progress.begin(batchId, chunkSize);
        // A chunked batch has one DQ evaluation; a resumed one carries on with the evaluation its first chunks made.
        var evaluation = new java.util.concurrent.atomic.AtomicReference<UUID>(
                committed > 0 && dq != null ? dq.latestEvaluation(batchId).orElse(null) : null);
        var totals = new ProcessingProgress.Counts[] {
                committed > 0 ? progress.totals(batchId) : ProcessingProgress.Counts.NONE};
        var chunk = new ArrayList<RawRecord>(chunkSize);
        int[] number = {0};
        Runnable flush = () -> {
            if (chunk.isEmpty()) {
                return;
            }
            int current = ++number[0];
            if (current > committed) {
                var started = OffsetDateTime.now();
                var rows = List.copyOf(chunk);
                var counts = progress == null ? processChunk(rows, duplicateKeys, referenceValues, evaluation)
                        : progress.inTransaction(() -> {
                            var done = processChunk(rows, duplicateKeys, referenceValues, evaluation);
                            progress.commit(batchId, current, done, started);
                            return done;
                        });
                totals[0] = totals[0].plus(counts);
                afterChunk.accept(current);
            }
            chunk.clear();
        };
        raws.forEachByBatchId(batchId, FETCH_SIZE, raw -> {
            chunk.add(raw);
            if (chunk.size() >= chunkSize) {
                flush.run();
            }
        });
        flush.run();
        if (progress != null) {
            progress.complete(batchId);
        }
        var sum = totals[0];
        var summary = new CanonicalProcessingSummary(batchId, sum.read(), sum.mapped(), sum.skipped(), sum.failed(),
                sum.held());
        if (events != null) {
            events.batchProcessed(summary);
        }
        return summary;
    }

    /**
     * MSA Phase 2: with events enabled, processing a batch first publishes its landed raw records (idempotent, so
     * every path that lands a batch is covered) and finally announces the processed batch.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEvents(gov.uddp.core.events.PlatformEvents events) {
        this.events = events;
    }

    /** The reference sets rules check against; {@code persist} also stores the pack's sets for the DQ report. */
    private Map<String, java.util.Set<String>> referenceValues(boolean persist) {
        Map<String, java.util.Set<String>> resolved = references == null ? Map.of() : references.values();
        if (dq != null) {
            if (persist) {
                dq.persistReferenceData(resolved);
            }
            resolved = dq.referenceValues();
        }
        if (referenceData != null) {
            resolved = referenceData.resolve(resolved);
        }
        return resolved;
    }

    /** Source record keys claimed by more than one row of the batch; a row whose key cannot be built is left to its chunk. */
    private java.util.Set<String> duplicateKeys(UUID batchId) {
        var seen = new java.util.HashSet<String>();
        var duplicates = new java.util.HashSet<String>();
        raws.forEachByBatchId(batchId, FETCH_SIZE, raw -> {
            try {
                String key = keys.of(raw, packs.find(raw.stateCode()).orElseThrow());
                if (!seen.add(key)) {
                    duplicates.add(key);
                }
            } catch (Exception ignored) {
                // The chunk reports a row it cannot key or map.
            }
        });
        return duplicates;
    }

    private ProcessingProgress.Counts processChunk(List<RawRecord> records, java.util.Set<String> duplicateKeys,
                                                   Map<String, java.util.Set<String>> referenceValues,
                                                   java.util.concurrent.atomic.AtomicReference<UUID> evaluationRef) {
        int mapped = 0;
        int skipped = 0;
        int failed = 0;
        int heldCount = 0;
        var prepared = new ArrayList<Prepared>();
        for (RawRecord raw : records) {
            gov.uddp.core.statepack.LoadedStatePack found = null;
            try {
                var pack = packs.find(raw.stateCode()).orElseThrow();
                found = pack;
                if (canonicals.exists(raw.rawRecordId(), pack.manifest().version())
                        || held != null && held.exists(raw.rawRecordId())) {
                    skipped++;
                    continue;
                }
                var resident = mapper.map(raw, pack, raw.sourceSystem());
                var shaped = shapedFor(raw, pack);
                String payload = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(resident);
                leakGuard().check(json.readTree(payload));
                String key = keys.of(raw, pack);
                prepared.add(new Prepared(raw, pack, payload, json.readTree(payload), key, shaped));
            } catch (gov.uddp.shaping.api.RowDroppedException dropped) {
                skipped++;
            } catch (Exception error) {
                failed++;
                // An error without a message (a NullPointerException) still needs a reason a person can read; the
                // failure row's message is required, so recording it would otherwise fail the whole stage.
                String message = error.getMessage() != null ? error.getMessage()
                        : error.getClass().getSimpleName() + " at " + java.util.Arrays.stream(error.getStackTrace())
                                .filter(f -> f.getClassName().startsWith("gov.uddp")).findFirst()
                                .map(StackTraceElement::toString).orElse("unknown");
                canonicals.recordFailure(failure(raw, found, message,
                        gov.uddp.core.validation.ReasonCodes.of(error), error));
            }
        }
        // Rows of one delivery that claim the same source record are ambiguous: none of them may become (or replace)
        // that record's canonical version, so all of them are rejected.
        for (var item : List.copyOf(prepared)) {
            if (duplicateKeys.contains(item.key())) {
                failed++;
                canonicals.recordFailure(failure(item.raw(), item.pack(),
                        "Duplicate source record key in batch: " + item.key(),
                        gov.uddp.core.validation.ReasonCodes.DUPLICATE_SOURCE_KEY, null));
                prepared.remove(item);
            }
        }
        var issues = prepared.isEmpty() ? List.<List<ValidationIssue>>of() : validator.validateBatch(
                prepared.stream().map(Prepared::node).toList(), prepared.getFirst().pack(), referenceValues,
                java.time.Clock.systemUTC());
        int errors = (int) issues.stream().flatMap(List::stream).filter(i -> i.severity() != Severity.WARNING).count();
        int warnings = (int) issues.stream().flatMap(List::stream).filter(i -> i.severity() == Severity.WARNING).count();
        int ruleEvaluations = prepared.isEmpty() ? 0 : prepared.size() * prepared.getFirst().pack().validations().stream()
                .filter(d -> ResidentValidator.ENTITY.equals(d.entity()) && d.rules() != null)
                .mapToInt(d -> d.rules().size())
                .sum();
        int affected = (int) issues.stream().filter(x -> !x.isEmpty()).count();
        UUID known = dq == null || prepared.isEmpty() ? null : evaluationRef.get();
        if (dq != null && !prepared.isEmpty()) {
            if (known == null) {
                known = dq.start(prepared.getFirst().raw(), prepared.size(), ruleEvaluations, errors, warnings, affected);
                evaluationRef.set(known);
            } else {
                dq.add(known, prepared.size(), ruleEvaluations, errors, warnings, affected);
            }
        }
        final UUID evaluation = known;
        for (int i = 0; i < prepared.size(); i++) {
            var item = prepared.get(i);
            var itemIssues = issues.get(i);
            var errorsFor = itemIssues.stream().filter(x -> x.severity() != Severity.WARNING).toList();
            var reasons = holdReasons(errorsFor, item.node(), item.shaped());
            if (!reasons.isEmpty()) {
                if (evaluation != null) {
                    errorsFor.forEach(x -> dq.finding(evaluation, item.raw(), null, x));
                }
                if (held == null) {
                    failed++;
                    canonicals.recordFailure(new CanonicalProcessingError(item.raw().rawRecordId(),
                            item.raw().batchId(), item.raw().statePackVersion(),
                            new CanonicalValidationException(errorsFor).getMessage(), reasons.getFirst().code(),
                            item.pack().manifest().packId(), item.raw().sourceSystem(),
                            gov.uddp.core.held.FixTarget.of(reasons.getFirst().code(), reasons.getFirst().field(),
                                    Map.of(), item.pack(), item.raw().sourceSystem())));
                } else if (held.insert(heldRecord(item.raw(), item.pack(), reasons, item.node()), null)) {
                    heldCount++;
                } else {
                    skipped++;
                }
                continue;
            }
            UUID id = UUID.randomUUID();
            var stored = insertVersion(id, item.raw(), item.pack().manifest().version(), item.payload(), item.node(),
                    item.key());
            if (stored.unchanged()) {
                // An unchanged re-delivery writes no canonical version; its batch's DQ findings point at the latest one.
                skipped++;
                if (evaluation != null) {
                    for (var warning : itemIssues.stream().filter(x -> x.severity() == Severity.WARNING).toList()) {
                        dq.finding(evaluation, item.raw(), stored.canonicalResidentId(), warning);
                    }
                }
                observeEntities(item.raw(), item.pack(), item.key(), stored.canonicalResidentId());
                continue;
            }
            boolean inserted = stored.inserted();
            if (inserted) {
                mapped++;
                observeEntities(item.raw(), item.pack(), item.key(), id);
                persistShaped(item.raw(), item.pack(), item.shaped(), id);
                recordInvalidIdentifiers(item.raw(), item.pack(), item.node(), id);
                for (var warning : itemIssues.stream().filter(x -> x.severity() == Severity.WARNING).toList()) {
                    canonicals.recordValidationWarning(new CanonicalValidationWarning(id, item.raw().rawRecordId(),
                            item.raw().batchId(), item.pack().manifest().version(), warning.field(), warning.rule(),
                            warning.message()));
                    if (evaluation != null) {
                        dq.finding(evaluation, item.raw(), id, warning);
                    }
                }
            } else {
                skipped++;
            }
        }
        return new ProcessingProgress.Counts(records.size(), mapped, skipped, failed, heldCount);
    }

    // ---- held records ----------------------------------------------------------------------------------------------

    /**
     * Why a mapped row is held: each ERROR or SEND_TO_STEWARD rule finding, plus (when enabled) each INVALID identifier
     * and each low-confidence shaped field. Empty when the row can go on to canonical.
     */
    public List<gov.uddp.core.held.HeldReason> holdReasons(List<ValidationIssue> blocking,
                                                     com.fasterxml.jackson.databind.JsonNode node,
                                                     gov.uddp.shaping.api.RecordShaper.Shaped shaped) {
        var reasons = new ArrayList<gov.uddp.core.held.HeldReason>();
        for (var issue : blocking) {
            reasons.add(new gov.uddp.core.held.HeldReason(gov.uddp.core.validation.ReasonCodes.ruleFailed(issue.rule()),
                    issue.message(), issue.field(), issue.severity()));
        }
        if (held != null && holdInvalidIdentifiers) {
            for (var entry : identifierEntries(node)) {
                if (gov.uddp.core.mapping.ResidentMappingEngine.INVALID_STATUS.equals(entry.path("status").asText())) {
                    reasons.add(new gov.uddp.core.held.HeldReason(gov.uddp.core.validation.ReasonCodes.ID_FORMAT,
                            entry.path("reason").asText(), "identifiers." + entry.path("type").asText(),
                            Severity.SEND_TO_STEWARD));
                }
            }
        }
        if (held != null && holdLowConfidence && shaped != null) {
            for (var warning : shaped.warnings()) {
                if (gov.uddp.shaping.ShapingEngine.LOW_CONFIDENCE_RULE.equals(warning.rule())) {
                    reasons.add(new gov.uddp.core.held.HeldReason(gov.uddp.core.validation.ReasonCodes.LOW_CONFIDENCE,
                            warning.message(), warning.field(), Severity.SEND_TO_STEWARD));
                }
            }
        }
        return reasons;
    }

    private gov.uddp.core.held.HeldRecord heldRecord(RawRecord raw, gov.uddp.core.statepack.LoadedStatePack pack,
                                                     List<gov.uddp.core.held.HeldReason> reasons,
                                                     com.fasterxml.jackson.databind.JsonNode node) {
        // Each reason names the pack part that fixes it (rule, mapping column, crosswalk, shaping step).
        var located = reasons.stream().map(reason -> reason.withFix(gov.uddp.core.held.FixTarget.of(reason.code(),
                reason.field(), Map.of(), pack, raw.sourceSystem()))).toList();
        return new gov.uddp.core.held.HeldRecord(null, raw.batchId(), raw.rawRecordId(), raw.sourceSystem(),
                raw.stateCode(), departments == null ? null : departments.packAssignments().get(pack.manifest().packId()),
                pack.manifest().version(), located, "OPEN", null, null, null, null, null, null, node, node, 0,
                pack.manifest().packId(), null);
    }

    /** A rejected row with its reason code and the pack part that fixes it; {@code pack} may be null. */
    private static CanonicalProcessingError failure(RawRecord raw, gov.uddp.core.statepack.LoadedStatePack pack,
                                                    String message, String code, Throwable error) {
        var context = error == null ? Map.<String, String>of() : gov.uddp.core.validation.ReasonCodes.contextOf(error);
        return new CanonicalProcessingError(raw.rawRecordId(), raw.batchId(), raw.statePackVersion(), message, code,
                pack == null ? null : pack.manifest().packId(), raw.sourceSystem(),
                gov.uddp.core.held.FixTarget.of(code, context.get("target"), context, pack, raw.sourceSystem()));
    }

    private gov.uddp.core.department.DepartmentRepository departments;

    /** Names the department that owns a held record's pack; without it the department is left empty. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDepartments(gov.uddp.core.department.DepartmentRepository departments) {
        this.departments = departments;
    }

    /** What re-assessing a held record's raw row against today's pack and rules found. */
    public record Reassessment(boolean releasable, List<gov.uddp.core.held.HeldReason> reasons,
                               com.fasterxml.jackson.databind.JsonNode payload, UUID canonicalId) {
    }

    /**
     * Maps and validates the held record's raw row again (after a pack or rule fix). A row that now passes becomes the
     * next canonical version and is observed for families, benefits, applications and assets; one that still fails
     * keeps its hold with the reasons found now.
     */
    public Reassessment reassess(gov.uddp.core.held.HeldRecord heldRecord) {
        var raw = raws.findById(heldRecord.rawRecordId())
                .orElseThrow(() -> new IllegalArgumentException("Raw record not found: " + heldRecord.rawRecordId()));
        var pack = packs.find(raw.stateCode()).orElseThrow();
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            var resident = mapper.map(raw, pack, raw.sourceSystem());
            node = json.readTree(json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writeValueAsString(resident));
            leakGuard().check(node);
        } catch (Exception error) {
            return new Reassessment(false, List.of(new gov.uddp.core.held.HeldReason(
                    gov.uddp.core.validation.ReasonCodes.of(error), String.valueOf(error.getMessage()), null,
                    Severity.ERROR)), heldRecord.currentPayload(), null);
        }
        var blocking = blockingIssues(node, pack);
        var reasons = holdReasons(blocking, node, shapedFor(raw, pack));
        if (!reasons.isEmpty()) {
            return new Reassessment(false, reasons, node, null);
        }
        return new Reassessment(true, List.of(), node, release(raw, pack, node));
    }

    /** The ERROR and SEND_TO_STEWARD findings of a canonical payload against the pack, with live reference sets. */
    public List<ValidationIssue> blockingIssues(com.fasterxml.jackson.databind.JsonNode payload,
                                                gov.uddp.core.statepack.LoadedStatePack pack) {
        return validator.validateBatch(List.of(payload), pack, referenceValues(false), java.time.Clock.systemUTC())
                .getFirst().stream().filter(x -> x.severity() != Severity.WARNING).toList();
    }

    /** Releases a steward-decided record to canonical as the next version of its source record. */
    public UUID release(gov.uddp.core.held.HeldRecord heldRecord, com.fasterxml.jackson.databind.JsonNode payload) {
        var raw = raws.findById(heldRecord.rawRecordId())
                .orElseThrow(() -> new IllegalArgumentException("Raw record not found: " + heldRecord.rawRecordId()));
        return release(raw, packs.find(raw.stateCode()).orElseThrow(), payload);
    }

    private UUID release(RawRecord raw, gov.uddp.core.statepack.LoadedStatePack pack,
                         com.fasterxml.jackson.databind.JsonNode node) {
        try {
            String payload = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(node);
            leakGuard().check(node);
            String key = keys.of(raw, pack);
            var stored = insertVersion(UUID.randomUUID(), raw, pack.manifest().version(), payload, node, key);
            if (stored.inserted() || stored.unchanged()) {
                observeEntities(raw, pack, key, stored.canonicalResidentId());
            }
            if (stored.inserted()) {
                persistShaped(raw, pack, shapedFor(raw, pack), stored.canonicalResidentId());
                recordInvalidIdentifiers(raw, pack, node, stored.canonicalResidentId());
            }
            return stored.canonicalResidentId();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise the released record", e);
        }
    }

    private static List<com.fasterxml.jackson.databind.JsonNode> identifierEntries(
            com.fasterxml.jackson.databind.JsonNode node) {
        var extensions = node.path("extensions");
        var entries = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        extensions.path(gov.uddp.core.identity.IdentifierProtection.EXTENSION).elements().forEachRemaining(entries::add);
        extensions.path(gov.uddp.core.mapping.ResidentMappingEngine.ALIAS_EXTENSION).elements()
                .forEachRemaining(list -> list.elements().forEachRemaining(entries::add));
        return entries;
    }

    private void observeEntities(RawRecord raw, gov.uddp.core.statepack.LoadedStatePack pack, String key, UUID canonicalId) {
        if (nationalIdRedactor != null) {
            nationalIdRedactor.redact(raw, pack);
        }
        if (families != null) {
            families.observe(raw, pack, key, canonicalId);
        }
        if (benefits != null) {
            benefits.observe(raw, pack, key, canonicalId);
        }
        if (applications != null) {
            applications.observe(raw, pack, key, canonicalId);
        }
        if (assets != null) {
            assets.observe(raw, pack, key, canonicalId);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String payload) {
        try {
            return json.readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Stored canonical payload is not JSON", e);
        }
    }

    private record Prepared(RawRecord raw, gov.uddp.core.statepack.LoadedStatePack pack, String payload,
                            com.fasterxml.jackson.databind.JsonNode node, String key,
                            gov.uddp.shaping.api.RecordShaper.Shaped shaped) { }

    /** The tidy record of a source with layout or parse steps; null for plain passthrough sources. */
    private gov.uddp.shaping.api.RecordShaper.Shaped shapedFor(RawRecord raw, gov.uddp.core.statepack.LoadedStatePack pack) {
        var shaping = mapper.shaping();
        return shaping.shaped(pack, raw.sourceSystem()) ? shaping.shapeWithWarnings(raw, pack, raw.sourceSystem()) : null;
    }

    /** Records a warning for each identifier kept as INVALID under {@code onInvalid: flag}; the reason holds no value. */
    private void recordInvalidIdentifiers(RawRecord raw, gov.uddp.core.statepack.LoadedStatePack pack,
                                          com.fasterxml.jackson.databind.JsonNode node, UUID canonicalId) {
        for (var entry : identifierEntries(node)) {
            if (gov.uddp.core.mapping.ResidentMappingEngine.INVALID_STATUS.equals(entry.path("status").asText())) {
                canonicals.recordValidationWarning(new CanonicalValidationWarning(canonicalId, raw.rawRecordId(),
                        raw.batchId(), pack.manifest().version(), "identifiers." + entry.path("type").asText(),
                        "IDENTIFIER_INVALID", entry.path("reason").asText()));
            }
        }
    }

    /** Stores the tidy record and its shaping warnings next to the canonical version they produced. */
    private void persistShaped(RawRecord raw, gov.uddp.core.statepack.LoadedStatePack pack,
                               gov.uddp.shaping.api.RecordShaper.Shaped shaped, UUID canonicalId) {
        if (shaped == null) {
            return;
        }
        try {
            if (shapedRecords != null) {
                shapedRecords.save(raw.rawRecordId(), pack.manifest().version(),
                        mapper.shaping().digest(pack, raw.sourceSystem()),
                        json.writeValueAsString(shaped.record().fields()),
                        json.writeValueAsString(shaped.record().provenance()));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise shaped record", e);
        }
        for (var warning : shaped.warnings()) {
            canonicals.recordValidationWarning(new CanonicalValidationWarning(canonicalId, raw.rawRecordId(),
                    raw.batchId(), pack.manifest().version(), warning.field(), warning.rule(), warning.message()));
        }
    }

    /** Outcome of storing one mapped record: the canonical ID that now represents it. */
    private record Stored(UUID canonicalResidentId, boolean inserted, boolean unchanged) {
    }

    /**
     * Inserts the record as the next canonical version of its source record. When the latest version already has
     * this content under this State Pack version (an unchanged re-delivery) nothing is written and that version is
     * returned as {@code unchanged}.
     */
    private Stored insertVersion(UUID id, RawRecord raw, String version, String payload,
                                 com.fasterxml.jackson.databind.JsonNode node, String key) {
        var latest = canonicals.latestVersion(key);
        if (latest.isPresent() && latest.get().statePackVersion().equals(version)
                && SourceRecordKey.content(readTree(latest.get().canonicalPayload()))
                        .equals(SourceRecordKey.content(node))) {
            return new Stored(latest.get().canonicalResidentId(), false, true);
        }
        int number = latest.map(v -> v.versionNumber() + 1).orElse(1);
        UUID supersedes = latest.map(CanonicalResidentRepository.LatestCanonicalVersion::canonicalResidentId).orElse(null);
        boolean inserted = canonicals.insertVersion(new CanonicalResidentRecord(id, raw.rawRecordId(), raw.batchId(),
                raw.stateCode(), raw.sourceSystem(), raw.sourceEntity(), version, payload, OffsetDateTime.now(),
                OffsetDateTime.now()), key, number, supersedes);
        return new Stored(id, inserted, false);
    }

    public boolean process(UUID rawRecordId) {
        return process(raws.findById(rawRecordId)
                .orElseThrow(() -> new IllegalArgumentException("Raw record not found: " + rawRecordId)));
    }

    private boolean process(RawRecord raw) {
        var pack = packs.find(raw.stateCode())
                .orElseThrow(() -> new IllegalArgumentException("No State Pack for " + raw.stateCode()));
        String version = pack.manifest().version();
        if (canonicals.exists(raw.rawRecordId(), version)) {
            return false;
        }
        String payload;
        String key;
        List<ValidationIssue> issues;
        try {
            var resident = mapper.map(raw, pack, raw.sourceSystem());
            payload = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(resident);
            leakGuard().check(json.readTree(payload));
            key = keys.of(raw, pack);
            issues = validator.validate(json.readTree(payload), pack);
        } catch (Exception error) {
            throw new IllegalStateException("Canonical Resident mapping failed for raw record " + raw.rawRecordId()
                    + ": " + error.getMessage(), error);
        }
        var errors = issues.stream().filter(issue -> issue.severity() != Severity.WARNING).toList();
        if (!errors.isEmpty()) {
            throw new CanonicalValidationException(errors);
        }
        UUID canonicalId = UUID.randomUUID();
        var stored = insertVersion(canonicalId, raw, version, payload, readTree(payload), key);
        boolean inserted = stored.inserted();
        if (inserted || stored.unchanged()) {
            observeEntities(raw, pack, key, stored.canonicalResidentId());
        }
        if (inserted) {
            persistShaped(raw, pack, shapedFor(raw, pack), canonicalId);
            recordInvalidIdentifiers(raw, pack, readTree(payload), canonicalId);
            for (var warning : issues) {
                canonicals.recordValidationWarning(new CanonicalValidationWarning(canonicalId, raw.rawRecordId(),
                        raw.batchId(), version, warning.field(), warning.rule(), warning.message()));
            }
        }
        return inserted;
    }
}
