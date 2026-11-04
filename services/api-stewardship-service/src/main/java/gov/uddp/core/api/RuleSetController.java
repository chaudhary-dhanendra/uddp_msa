package gov.uddp.core.api;

import gov.uddp.core.rules.DecisionTableDmn;
import gov.uddp.core.rules.RuleConditions;
import gov.uddp.core.rules.RuleSet;
import gov.uddp.core.rules.RuleSetService;
import gov.uddp.core.security.Audited;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.studio.StudioMetadata;
import gov.uddp.core.studio.StudioModels;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Evaluates the currently effective approved rule set; authoring is a maker-checker workflow (draft, submit, approve). */
@RestController
@RequestMapping("/api/rule-sets")
public class RuleSetController {
    private final RuleSetService service;
    private final StudioMetadata metadata;

    public RuleSetController(RuleSetService service, StudioMetadata metadata) {
        this.service = service;
        this.metadata = metadata;
    }

    /** Code sets longer than this are not listed with a field; the Studio then takes the code as typed text. */
    static final int MAX_LISTED_CODES = 200;

    /**
     * A record attribute a condition can test: the canonical Resident and Family fields from platform metadata, with the
     * data type that decides which operators the Studio offers and, for a short code set, its values.
     */
    public record RuleField(String name, String label, String type, String codeSet, String valueKind,
                            RuleConditions.DataType dataType, List<RuleConditions.Operator> operators,
                            List<StudioModels.CodeValue> values) {
    }

    @GetMapping("/fields")
    @PreAuthorize("hasAnyAuthority('PERM_PLATFORM_SETTINGS', 'PERM_RULES_APPROVE')")
    public List<RuleField> fields() {
        Map<String, RuleField> byName = new LinkedHashMap<>();
        metadata.targets().stream()
                .filter(t -> !"Benefit".equals(t.entity()))
                .filter(t -> !t.target().contains("[]") && !t.target().startsWith("extensions."))
                .forEach(t -> byName.putIfAbsent(t.target(), field(t)));
        return List.copyOf(byName.values());
    }

    private RuleField field(StudioMetadata.Target t) {
        RuleConditions.DataType type = RuleConditions.dataType(t.type(), t.valueKind(), t.codeSet());
        List<StudioModels.CodeValue> values = null;
        if (type == RuleConditions.DataType.CODE && t.codeSet() != null && !metadata.liveCodeSet(t.codeSet())) {
            List<StudioModels.CodeValue> codes = metadata.codes(t.codeSet());
            values = codes.isEmpty() || codes.size() > MAX_LISTED_CODES ? null : codes;
        }
        return new RuleField(t.target(), t.entity() + " · " + t.label(), t.type(), t.codeSet(), t.valueKind(), type,
                RuleConditions.operators(type), values);
    }

    /** One Rules Studio row: either built {@code conditions} (all must hold) or a FEEL {@code condition}. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "RuleTableRow")
    public record TableRow(String condition, List<RuleConditions.Condition> conditions, String output,
                           String clauseReference) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "RuleTableDraft")
    public record TableRequest(RuleSet.Type type, RuleSet.OwnerType ownerType, String ownerId, LocalDate effectiveFrom,
                               LocalDate effectiveTo, List<TableRow> rows) {
    }

    @PostMapping("/table")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "RULE_SET_CREATE", resource = "RULE_SET")
    public RuleSet createFromTable(@RequestBody TableRequest draft) {
        return service.createFromTable(toDraft(draft), CurrentUser.username());
    }

    /** Built conditions are checked against field metadata and compiled to FEEL; a typed condition is kept as it is. */
    RuleSetService.TableDraft toDraft(TableRequest draft) {
        if (draft == null) {
            throw new IllegalArgumentException("A rule set draft is required");
        }
        Map<String, RuleField> known = new LinkedHashMap<>();
        fields().forEach(f -> known.put(f.name(), f));
        List<DecisionTableDmn.DecisionRow> rows = new ArrayList<>();
        List<TableRow> requested = draft.rows() == null ? List.of() : draft.rows();
        for (int i = 0; i < requested.size(); i++) {
            TableRow row = requested.get(i);
            if (row == null) {
                throw new IllegalArgumentException("Row " + (i + 1) + " is empty");
            }
            String condition = row.condition();
            if (row.conditions() != null && !row.conditions().isEmpty()) {
                condition = RuleConditions.compileAll(row.conditions(), name -> {
                    RuleField f = known.get(name);
                    return f == null ? null : new RuleConditions.FieldSpec(f.name(), f.label(), f.dataType(),
                            f.values() == null ? List.of() : f.values().stream().map(StudioModels.CodeValue::code).toList());
                });
            }
            rows.add(new DecisionTableDmn.DecisionRow(condition, row.output(), row.clauseReference()));
        }
        return new RuleSetService.TableDraft(draft.type(), draft.ownerType(), draft.ownerId(), draft.effectiveFrom(),
                draft.effectiveTo(), rows);
    }

    /** Tests any version, drafts included, against a sample record. */
    @PostMapping("/{id}/evaluate")
    @PreAuthorize("hasAnyAuthority('PERM_PLATFORM_SETTINGS', 'PERM_RULES_APPROVE')")
    @Audited(action = "RULE_SET_EVALUATE", resource = "RULE_SET", idVariable = "id")
    public RuleSetService.Evaluation evaluateVersion(@PathVariable java.util.UUID id,
                                                     @RequestBody(required = false) Map<String, Object> record) {
        return service.explainById(id, record == null ? Map.of() : record);
    }

    @GetMapping("/{id}/test-cases")
    @PreAuthorize("hasAnyAuthority('PERM_PLATFORM_SETTINGS', 'PERM_RULES_APPROVE')")
    public java.util.List<gov.uddp.core.rules.RuleTestCaseGenerator.TestCase> testCases(@PathVariable java.util.UUID id) {
        return service.testCases(id);
    }

    public record EvaluateRequest(
            RuleSet.Type type,
            RuleSet.OwnerType ownerType,
            String ownerId,
            LocalDate on,
            Map<String, Object> record) {
    }

    @PostMapping("/evaluate")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "RULE_SET_EVALUATE", resource = "RULE_SET")
    public RuleSetService.Evaluation evaluate(@RequestBody EvaluateRequest request) {
        return service.evaluate(
                request.type(),
                request.ownerType(),
                request.ownerId(),
                request.on() == null ? LocalDate.now() : request.on(),
                request.record() == null ? Map.of() : request.record());
    }

    /** Selector metadata only: DMN definitions are deliberately excluded. */
    public record RuleSetSummary(
            java.util.UUID id,
            RuleSet.Type type,
            RuleSet.OwnerType ownerType,
            String ownerId,
            int version,
            RuleSet.Status status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            java.util.List<String> requiredAttributes) {
        static RuleSetSummary from(RuleSet rule) {
            return new RuleSetSummary(
                    rule.id(),
                    rule.type(),
                    rule.ownerType(),
                    rule.ownerId(),
                    rule.version(),
                    rule.status(),
                    rule.effectiveFrom(),
                    rule.effectiveTo(),
                    rule.requiredAttributes());
        }
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_PLATFORM_SETTINGS', 'PERM_RULES_APPROVE')")
    public java.util.List<RuleSetSummary> list(@RequestParam(required = false) RuleSet.Type type) {
        return service.list(type).stream().map(RuleSetSummary::from).toList();
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyAuthority('PERM_PLATFORM_SETTINGS', 'PERM_RULES_APPROVE')")
    public java.util.List<RuleSet> history(
            @RequestParam RuleSet.Type type,
            @RequestParam RuleSet.OwnerType ownerType,
            @RequestParam String ownerId) {
        return service.history(type, ownerType, ownerId);
    }

    public record DecisionRequest(String reason) {
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "RULE_SET_CREATE", resource = "RULE_SET")
    public RuleSet create(@RequestBody RuleSetService.Draft draft) {
        return service.createDraft(draft, CurrentUser.username());
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "RULE_SET_SUBMIT", resource = "RULE_SET", idVariable = "id")
    public RuleSet submit(@PathVariable java.util.UUID id) {
        return service.submit(id, CurrentUser.username());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PERM_RULES_APPROVE')")
    @Audited(action = "RULE_SET_APPROVE", resource = "RULE_SET", idVariable = "id")
    public RuleSet approve(@PathVariable java.util.UUID id, @RequestBody(required = false) DecisionRequest body) {
        return service.approve(id, CurrentUser.username(), body == null ? null : body.reason());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PERM_RULES_APPROVE')")
    @Audited(action = "RULE_SET_REJECT", resource = "RULE_SET", idVariable = "id")
    public RuleSet reject(@PathVariable java.util.UUID id, @RequestBody DecisionRequest body) {
        return service.reject(id, CurrentUser.username(), body == null ? null : body.reason());
    }
}
