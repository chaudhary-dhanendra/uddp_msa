package gov.uddp.core.studio;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The Onboarding Studio's draft (persisted as JSONB) and its API shapes. Nothing here is
 * state-specific: a draft is configuration that the Studio turns into a State Pack in the existing spec format.
 */
public final class StudioModels {
    private StudioModels() {
    }

    /** PENDING_APPROVAL: a publication was submitted and waits for a second user (maker-checker). */
    public enum DraftStatus { DRAFT, PENDING_APPROVAL, PUBLISHED, ONBOARDED }

    @Schema(description = "An Onboarding Studio draft: state details, its source, mappings, crosswalks and rules")
    public record Draft(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String draftId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String packId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String stateCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String version,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DraftStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime updatedAt,
            @Schema(nullable = true) Source source,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MappingRule> mappings,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Crosswalk> crosswalks,
            @Schema(nullable = true) Rules rules,
            @Schema(nullable = true) Publication published,
            @Schema(nullable = true) String lastRunId,
            @Schema(nullable = true, description = "The latest submission for approval, if any") Approval approval) {

        Draft withSource(Source value, OffsetDateTime now) {
            return new Draft(draftId, packId, stateCode, displayName, version, status, createdAt, now, value,
                    mappings, crosswalks, rules, published, lastRunId, approval);
        }

        Draft withConfiguration(List<MappingRule> newMappings, List<Crosswalk> newCrosswalks, Rules newRules,
                                OffsetDateTime now) {
            return new Draft(draftId, packId, stateCode, displayName, version, status, createdAt, now, source,
                    newMappings, newCrosswalks, newRules, published, lastRunId, approval);
        }

        Draft withDetails(String newPackId, String newStateCode, String newDisplayName, OffsetDateTime now) {
            return new Draft(draftId, newPackId, newStateCode, newDisplayName, version, status, createdAt, now, source,
                    mappings, crosswalks, rules, published, lastRunId, approval);
        }

        Draft withPublication(String newVersion, Publication publication, Approval decided, OffsetDateTime now) {
            return new Draft(draftId, packId, stateCode, displayName, newVersion, DraftStatus.PUBLISHED, createdAt, now,
                    source, mappings, crosswalks, rules, publication, lastRunId, decided);
        }

        Draft withRun(String runId, DraftStatus newStatus, OffsetDateTime now) {
            return new Draft(draftId, packId, stateCode, displayName, version, newStatus, createdAt, now, source,
                    mappings, crosswalks, rules, published, runId, approval);
        }

        /** Submitted (PENDING_APPROVAL) or rejected (back to its earlier status) with the approval record. */
        Draft withApproval(DraftStatus newStatus, Approval value, OffsetDateTime now) {
            return new Draft(draftId, packId, stateCode, displayName, version, newStatus, createdAt, now, source,
                    mappings, crosswalks, rules, published, lastRunId, value);
        }
    }

    @Schema(description = "Where the draft's records come from: an uploaded file or a database table")
    public record Source(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sourceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"file", "database"}) String sourceType,
            @Schema(nullable = true) String fileName,
            @Schema(nullable = true, description = "The landed file (absolute path) for file sources") String location,
            @Schema(nullable = true) Long sizeBytes,
            @Schema(nullable = true) String recordIdField,
            @Schema(nullable = true) List<String> recordKeyFields,
            @Schema(nullable = true, description = "Named connection for database sources (credentials stay in the local store)")
            String connectionName,
            @Schema(nullable = true) String table,
            @Schema(nullable = true) String query,
            @Schema(nullable = true, description = "Accepted layout steps, written to the pack's source layout:")
            List<Map<String, Object>> layout,
            @Schema(nullable = true, description = "Accepted field parsers, written to the pack's source parse:")
            List<Map<String, Object>> parse,
            @Schema(nullable = true, description = "Workbook sheets read (xlsx files)") List<String> sheets) {

        /** A source without shaping (plain CSV or database table). */
        public Source(String sourceId, String sourceType, String fileName, String location, Long sizeBytes,
                      String recordIdField, List<String> recordKeyFields, String connectionName, String table,
                      String query) {
            this(sourceId, sourceType, fileName, location, sizeBytes, recordIdField, recordKeyFields, connectionName,
                    table, query, null, null, null);
        }

        public Source withShaping(List<Map<String, Object>> layout, List<Map<String, Object>> parse,
                                  List<String> sheets) {
            return new Source(sourceId, sourceType, fileName, location, sizeBytes, recordIdField, recordKeyFields,
                    connectionName, table, query, layout, parse, sheets);
        }

        public Source withRecordKey(String recordId, List<String> keys) {
            return new Source(sourceId, sourceType, fileName, location, sizeBytes, recordId, keys, connectionName,
                    table, query, layout, parse, sheets);
        }
    }

    @Schema(description = "A layout step or parser proposed for the Shape step, as a ready-to-save step with its evidence")
    public record ShapeProposal(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "layout or parse") String section,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String kind,
            @Schema(nullable = true) String sheet,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int evidenceCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int evidenceTotal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The step as saved in layout: or parse:")
            Map<String, Object> step,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The step in flow-style YAML")
            String yaml,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "True when the draft already has this step")
            boolean enabled) {
    }

    @Schema(description = "What the Shape step shows: the proposals and the steps the draft already keeps")
    public record ShapeState(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ShapeProposal> proposals,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Map<String, Object>> layout,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Map<String, Object>> parse,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> sheets) {
    }

    @Schema(description = "The layout steps, parsers and sheets to keep in a draft's source (the Shape step)")
    public record ShapingConfig(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Map<String, Object>> layout,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Map<String, Object>> parse,
            @Schema(nullable = true) List<String> sheets) {
    }

    @Schema(description = "Before/after of the first rows with the layout steps and parsers applied, and the YAML they become")
    public record ShapePreview(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String yaml,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> beforeColumns,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<List<String>> beforeRows,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> afterColumns,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<List<String>> afterRows,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Parser warnings, one per affected cell")
            List<String> warnings,
            @Schema(nullable = true, description = "The sheet of each before row (workbooks only)") List<String> beforeSheets,
            @Schema(nullable = true, description = "The sheet of each after row (workbooks only)") List<String> afterSheets) {
    }

    @Schema(description = "One declarative mapping rule: a source column to a canonical target of an entity")
    public record MappingRule(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sourceField,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"Resident", "Family", "Benefit"})
            String entity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String target,
            @Schema(nullable = true, description = "For example date:DD-MM-YYYY") String conversion,
            @Schema(nullable = true) String crosswalk,
            @Schema(nullable = true) Boolean required) {
    }

    @Schema(description = "A crosswalk from local values to platform codes")
    public record Crosswalk(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String codeSet,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CrosswalkEntry> entries) {
    }

    @Schema(description = "One crosswalk row; a blank targetValue is not yet mapped")
    public record CrosswalkEntry(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sourceValue,
            @Schema(nullable = true) String targetValue,
            @Schema(nullable = true, description = "How the suggestion was made") String basis,
            @Schema(nullable = true) Double confidence,
            @Schema(nullable = true, description = "True when the confidence reaches the pre-accept threshold;"
                    + " false entries wait for review in the exception inbox") Boolean accepted) {
        public CrosswalkEntry(String sourceValue, String targetValue, String basis, Double confidence) {
            this(sourceValue, targetValue, basis, confidence, null);
        }
    }

    @Schema(description = "Validation, matching and survivorship rules of the draft")
    public record Rules(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ValidationRule> validation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Matching matching,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, List<String>> survivorship) {
    }

    @Schema(description = "A Resident validation rule (State Pack validation/ format)")
    public record ValidationRule(
            @Schema(nullable = true) String id,
            @Schema(nullable = true) String type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String field,
            @Schema(nullable = true) Boolean required,
            @Schema(nullable = true) List<String> allowedValues,
            @Schema(nullable = true) String pattern,
            @Schema(nullable = true) String referenceSet,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"error", "warning"}) String severity) {
    }

    @Schema(description = "Resident matching configuration; thresholds must agree across packs")
    public record Matching(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> fields,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double autoMatch,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double possibleMatch) {
    }

    /** A State Pack version submitted for approval and its outcome. */
    @Schema(description = "A State Pack version submitted for approval (maker-checker)")
    public record Approval(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String approvalId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String draftId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String packId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String stateCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String version,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"PENDING_APPROVAL", "APPROVED",
                "REJECTED"}) String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String submittedBy,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime submittedAt,
            @Schema(nullable = true) String decidedBy,
            @Schema(nullable = true) OffsetDateTime decidedAt,
            @Schema(nullable = true) String decisionReason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> files) {
    }

    @Schema(description = "Approve or reject a submitted version; a rejection needs a reason")
    public record ApprovalDecision(@Schema(nullable = true) String reason) {
    }

    @Schema(description = "The State Pack version generated from the draft")
    public record Publication(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String version,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime publishedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String packDirectory,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> files,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String lifecycleStatus) {
    }

    // ---- requests --------------------------------------------------------------------------------------------

    @Schema(description = "State details of a new or edited draft")
    public record DraftDetails(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Lower-case pack ID, e.g. state-5") String packId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Upper-case state code, e.g. ST5") String stateCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName) {
    }

    @Schema(description = "The draft's editable configuration (Map, Crosswalks and Rules steps)")
    public record DraftConfiguration(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MappingRule> mappings,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Crosswalk> crosswalks,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Rules rules,
            @Schema(nullable = true) String recordIdField,
            @Schema(nullable = true) List<String> recordKeyFields) {
    }

    @Schema(description = "Start onboarding the published pack")
    public record OnboardRequest(
            @Schema(nullable = true, allowableValues = {"FULL", "INCREMENTAL"},
                    description = "Default: FULL when no Golden data exists yet, otherwise INCREMENTAL")
            String processingMode) {
    }

    @Schema(description = "A named PostgreSQL source connection; a blank password keeps the saved one")
    public record SourceConnectionRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String host,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer port,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String database,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String user,
            @Schema(nullable = true) String password) {
        @Override
        public String toString() {
            return "SourceConnectionRequest[host=" + host + ", port=" + port + ", database=" + database
                    + ", user=" + user + ", password=******]";
        }
    }

    @Schema(description = "Use a table or a single SELECT of a named connection as the draft's source")
    public record DatabaseSourceRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String connectionName,
            @Schema(nullable = true) String table,
            @Schema(nullable = true) String query) {
    }

    // ---- responses -------------------------------------------------------------------------------------------

    @Schema(description = "A saved source connection with its password masked")
    public record SourceConnection(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String host,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int port,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String database,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String user,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean passwordSet) {
    }

    @Schema(description = "Profile of an uploaded source")
    public record Profile(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int rowCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ColumnProfile> columns,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Map<String, String>> sampleRows,
            @Schema(description = "Rows the column statistics were computed from", requiredMode = Schema.RequiredMode.REQUIRED)
            int rowsSampled,
            @Schema(description = "Rows in the source (rowCount is the same number)", requiredMode = Schema.RequiredMode.REQUIRED)
            long rowsTotal,
            @Schema(nullable = true, description = "A workbook's sheets with their row counts (null for CSV and tables)")
            List<SheetSummary> sheets) {
        public Profile(int rowCount, List<ColumnProfile> columns, List<Map<String, String>> sampleRows) {
            this(rowCount, columns, sampleRows, rowCount, rowCount, null);
        }

        public Profile(int rowCount, List<ColumnProfile> columns, List<Map<String, String>> sampleRows,
                       int rowsSampled, long rowsTotal) {
            this(rowCount, columns, sampleRows, rowsSampled, rowsTotal, null);
        }

        public Profile withSheets(List<SheetSummary> summaries) {
            return new Profile(rowCount, columns, sampleRows, rowsSampled, rowsTotal, summaries);
        }
    }

    @Schema(description = "One sheet of an uploaded workbook: its name, its non-blank rows and whether it is loaded")
    public record SheetSummary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int rows,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean selected) {
    }

    @Schema(description = "Profile of one column")
    public record ColumnProfile(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"text", "integer", "decimal", "date", "mobile", "empty"}) String inferredType,
            @Schema(nullable = true, description = "Detected date format, e.g. DD-MM-YYYY") String dateFormat,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int blanks,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int distinct,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ValueCount> topValues,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean unique) {
    }

    @Schema(description = "A value and how often it occurs")
    public record ValueCount(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String value,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int count) {
    }

    @Schema(description = "Suggested mappings and crosswalks for the uploaded source")
    public record Suggestions(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ColumnSuggestion> columns,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Crosswalk> crosswalks,
            @Schema(nullable = true) String recordIdField,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> recordKeyFields,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LayoutProposal> layoutProposals) {

        public Suggestions(List<ColumnSuggestion> columns, List<Crosswalk> crosswalks, String recordIdField,
                           List<String> recordKeyFields) {
            this(columns, crosswalks, recordIdField, recordKeyFields, List.of());
        }

        public Suggestions withLayoutProposals(List<LayoutProposal> proposals) {
            return new Suggestions(columns, crosswalks, recordIdField, recordKeyFields, List.copyOf(proposals));
        }
    }

    @Schema(description = "A layout step or parser the profiler proposes for a messy source, with the evidence for it; "
            + "it is written into the pack's source YAML only after the user accepts it")
    public record LayoutProposal(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "layout or parse") String section,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Operation or parser, e.g. skip-rows")
            String kind,
            @Schema(nullable = true, description = "Sheet the evidence was found in; null for a flat file") String sheet,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Rows or values that show the pattern")
            int evidenceCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Rows or values examined") int evidenceTotal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Flow-style YAML entry for the source file")
            String yaml,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean accepted) {
    }

    @Schema(description = "Suggested targets for one column; none means it is kept as an extension")
    public record ColumnSuggestion(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String column,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TargetSuggestion> targets) {
    }

    @Schema(description = "One suggested target with its confidence and the reason")
    public record TargetSuggestion(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String entity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String target,
            @Schema(nullable = true) String conversion,
            @Schema(nullable = true) String crosswalk,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double confidence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String basis,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Pre-accepted: the confidence reaches"
                    + " uddp.studio.suggestion-accept-confidence and the suggestion is not from a model alone")
            boolean accepted) {
    }

    @Schema(description = "Mapping targets and code sets the generic engines accept")
    public record MappingTargets(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MappingTarget> targets,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, List<CodeValue>> codeSets,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> conversions) {
    }

    @Schema(description = "A canonical target")
    public record MappingTarget(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String entity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String target,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
            @Schema(nullable = true) String codeSet,
            @Schema(nullable = true, description = "The crosswalk ID a coded target uses") String crosswalkId) {
    }

    @Schema(description = "A platform code with its display name")
    public record CodeValue(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label) {
    }

    @Schema(description = "Dry run of the draft on the first rows with the existing engines; nothing is written")
    public record Preview(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int rowsPreviewed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int blockingErrors,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int warnings,
            @Schema(nullable = true, description = "A configuration problem that stops the whole preview") String packError,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PreviewRow> rows,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Issues per reason code (the codes held records and rejected rows carry)")
            Map<String, Integer> countsByCode) {
        public Preview(int rowsPreviewed, int blockingErrors, int warnings, String packError, List<PreviewRow> rows) {
            this(rowsPreviewed, blockingErrors, warnings, packError, rows, Map.of());
        }
    }

    @Schema(description = "One previewed source row")
    public record PreviewRow(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int rowNumber,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> source,
            @Schema(nullable = true) Map<String, Object> resident,
            @Schema(nullable = true) Map<String, Object> family,
            @Schema(nullable = true) Map<String, Object> benefit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PreviewIssue> issues) {
    }

    @Schema(description = "A mapping or validation problem of a previewed row")
    public record PreviewIssue(
            @Schema(nullable = true) String field,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"error", "warning"}) String severity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Reason code, as on held records")
            String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Where in the pack it is fixed")
            gov.uddp.core.held.FixTarget fix,
            @Schema(nullable = true, description = "A suggested fix (no row values)") String suggestion) {
        public PreviewIssue(String field, String severity, String message) {
            this(field, severity, message, gov.uddp.core.validation.ReasonCodes.OTHER,
                    gov.uddp.core.held.FixTarget.of(gov.uddp.core.validation.ReasonCodes.OTHER, field), null);
        }
    }

    @Schema(description = "The State Pack files that publishing the draft would write (read-only YAML)")
    public record GeneratedPack(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String packId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String version,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<GeneratedFile> files) {
    }

    @Schema(description = "One generated State Pack file")
    public record GeneratedFile(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String path,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content) {
    }

    @Schema(description = "An onboarding run started from the Studio")
    public record OnboardStarted(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String runId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String processingMode) {
    }

    @Schema(description = "A state in the Studio's States list")
    public record StateEntry(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String draftId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String packId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String stateCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DraftStatus status,
            @Schema(nullable = true) String version,
            @Schema(nullable = true) String sourceFile,
            @Schema(nullable = true) String lastRunId,
            @Schema(nullable = true) String lastRunStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime updatedAt) {
    }

    @Schema(description = "Studio configuration and staging limits; published packs are held in PostgreSQL")
    public record StudioStatus(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean demoMode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String studioPacksDirectory,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String activePackDirectory,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long uploadLimitBytes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean goldenDataExists) {
    }

    @Schema(description = "Evidence that onboarding each state needed configuration only")
    public record PortabilityEvidence(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String baselineRef,
            @Schema(nullable = true) String baselineCommit,
            @Schema(nullable = true) String currentCommit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> platformPaths,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Platform code files that differ from the baseline ref")
            List<String> changedSinceBaseline,
            @Schema(nullable = true) String gitError,
            @Schema(nullable = true) GuardResult guard,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<StateEvidence> states) {
    }

    @Schema(description = "Per-state portability evidence")
    public record StateEvidence(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String packId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String stateCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> generatedFiles,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> hopTemplates,
            @Schema(nullable = true, description = "Platform commit and working-tree state recorded when the pack was published")
            String platformCommitAtPublish,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Platform code files changed between the previous state's publication and this one")
            List<String> platformCodeChanges) {
    }

    @Schema(description = "Latest result of the portability guard test (from the Maven surefire report)")
    public record GuardResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String testClass,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int tests,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int failures,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int errors,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime reportTime) {
    }
}
