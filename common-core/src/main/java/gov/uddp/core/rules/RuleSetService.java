package gov.uddp.core.rules;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.camunda.bpm.dmn.engine.DmnDecision;
import org.camunda.bpm.dmn.engine.DmnDecisionRuleResult;
import org.camunda.bpm.dmn.engine.DmnEngine;
import org.camunda.bpm.dmn.engine.DmnEngineConfiguration;
import org.camunda.bpm.model.dmn.Dmn;
import org.springframework.stereotype.Service;

@Service
public class RuleSetService {
    private final RuleSetStore store;
    private final DmnEngine engine = DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();
    private final MakerChecker approvals;

    public RuleSetService(RuleSetStore store) {
        this.store = store;
        this.approvals = new MakerChecker(store.connections(), "rule_set", "rule_set_id", "rule set");
    }

    /** A new DRAFT version of one logical rule set; only an approval by another user makes it effective. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "RuleSetDraft")
    public record Draft(RuleSet.Type type, RuleSet.OwnerType ownerType, String ownerId, LocalDate effectiveFrom,
                        LocalDate effectiveTo, String dmnXml, List<String> requiredAttributes) {
    }

    public RuleSet createDraft(Draft draft, String maker) {
        if (draft.type() == null || draft.ownerType() == null || draft.ownerId() == null || draft.ownerId().isBlank()
                || draft.effectiveFrom() == null || draft.dmnXml() == null || draft.dmnXml().isBlank()) {
            throw new IllegalArgumentException("A rule set draft needs type, ownerType, ownerId, effectiveFrom and dmnXml");
        }
        if (maker == null || maker.isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException("A signed-in user is required to author a rule set");
        }
        parse(draft.dmnXml());
        RuleSet set = new RuleSet(java.util.UUID.randomUUID(), draft.type(), draft.ownerType(), draft.ownerId().strip(),
                store.nextVersion(draft.type(), draft.ownerType(), draft.ownerId().strip()), RuleSet.Status.DRAFT,
                draft.effectiveFrom(), draft.effectiveTo(), draft.dmnXml(), draft.requiredAttributes());
        store.save(set, maker);
        return getById(set.id());
    }

    /** A Rules Studio decision table; its conditions name the attributes the rule set requires. */
    public record TableDraft(RuleSet.Type type, RuleSet.OwnerType ownerType, String ownerId, LocalDate effectiveFrom,
                             LocalDate effectiveTo, List<DecisionTableDmn.DecisionRow> rows) {
    }

    public RuleSet createFromTable(TableDraft draft, String maker) {
        var table = new DecisionTableDmn.DecisionTable(draft.rows());
        DecisionTableDmn.validate(table);
        String name = (draft.type() == null ? "rules" : draft.type().name()) + " " + (draft.ownerId() == null ? "" : draft.ownerId());
        return createDraft(new Draft(draft.type(), draft.ownerType(), draft.ownerId(), draft.effectiveFrom(),
                draft.effectiveTo(), DecisionTableDmn.toDmn(table, name.strip()), DecisionTableDmn.attributes(table)), maker);
    }

    /** Boundary and missing-data cases generated from the rule set's conditions, each evaluated (nothing stored). */
    public List<RuleTestCaseGenerator.TestCase> testCases(java.util.UUID id) {
        return RuleTestCaseGenerator.generate(getById(id), this);
    }

    public RuleSet submit(java.util.UUID id, String actor) {
        approvals.submit(id, actor);
        return getById(id);
    }

    public RuleSet approve(java.util.UUID id, String approver, String reason) {
        approvals.approve(id, approver, reason);
        return getById(id);
    }

    public RuleSet reject(java.util.UUID id, String approver, String reason) {
        approvals.reject(id, approver, reason);
        return getById(id);
    }

    public MakerChecker.State approvalState(java.util.UUID id) {
        return approvals.state(id);
    }

    public List<RuleSet> history(RuleSet.Type type, RuleSet.OwnerType ownerType, String ownerId) {
        return store.history(type, ownerType, ownerId);
    }
    public RuleSet getById(java.util.UUID id) { return store.byId(id).orElseThrow(() -> new IllegalArgumentException("Rule set does not exist: " + id)); }
    public List<RuleSet> list(RuleSet.Type type) { return store.list(type); }
    public Evaluation evaluateById(java.util.UUID id, Map<String,Object> record) { return evaluate(getById(id), record); }

    public record FiredRule(Map<String, Object> outputs, String clauseReference) { }
    /** One part of a row's condition (split at top-level {@code and}) and whether the record satisfies it. */
    public record ConditionCheck(String clauseReference, String condition, boolean matched) { }

    /** {@code checks} explains each row part by part; it is filled only when a single version is tested. */
    public record Evaluation(RuleSet ruleSet, List<FiredRule> firedRules, List<ConditionCheck> checks) {
        public Evaluation(RuleSet ruleSet, List<FiredRule> firedRules) {
            this(ruleSet, firedRules, List.of());
        }
    }

    /** Evaluates one version and explains every row condition by condition, so a tester sees why a rule did not fire. */
    public Evaluation explainById(java.util.UUID id, Map<String, Object> record) {
        RuleSet set = getById(id);
        Evaluation result = evaluate(set, record);
        return new Evaluation(set, result.firedRules(), checks(engine, set.dmnXml(), record));
    }

    static List<ConditionCheck> checks(DmnEngine engine, String dmnXml, Map<String, Object> record) {
        List<ConditionCheck> checks = new java.util.ArrayList<>();
        try {
            var model = Dmn.readModelFromStream(new ByteArrayInputStream(dmnXml.getBytes(StandardCharsets.UTF_8)));
            List<org.camunda.bpm.model.dmn.instance.Input> inputs =
                    new java.util.ArrayList<>(model.getModelElementsByType(org.camunda.bpm.model.dmn.instance.Input.class));
            for (var rule : model.getModelElementsByType(org.camunda.bpm.model.dmn.instance.Rule.class)) {
                var entries = new java.util.ArrayList<>(rule.getInputEntries());
                var outputs = new java.util.ArrayList<>(rule.getOutputEntries());
                String clause = outputs.isEmpty() ? "" : unquote(outputs.get(outputs.size() - 1).getText().getTextContent());
                for (int i = 0; i < entries.size() && i < inputs.size(); i++) {
                    if (!"true".equals(entries.get(i).getText().getTextContent().strip())
                            || inputs.get(i).getInputExpression() == null) {
                        continue;
                    }
                    String condition = inputs.get(i).getInputExpression().getText().getTextContent().strip();
                    for (String part : DecisionTableDmn.conjuncts(condition)) {
                        checks.add(new ConditionCheck(clause, part, holds(engine, part, record)));
                    }
                }
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return List.copyOf(checks);
    }

    private static boolean holds(DmnEngine engine, String condition, Map<String, Object> record) {
        try {
            String xml = DecisionTableDmn.toDmn(new DecisionTableDmn.DecisionTable(
                    List.of(new DecisionTableDmn.DecisionRow(condition, "true", "check"))), "check");
            var model = Dmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return !engine.evaluateDecisionTable(engine.parseDecision("decision", model), record).getResultList().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String unquote(String text) {
        String t = text == null ? "" : text.strip();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }

    public Evaluation evaluate(RuleSet.Type type, RuleSet.OwnerType ownerType, String ownerId, LocalDate on,
                               Map<String, Object> record) {
        return evaluateIfEffective(type, ownerType, ownerId, on, record)
                .orElseThrow(() -> new IllegalArgumentException("No approved effective rule set for " + type + "/" + ownerId));
    }

    /** Returns empty when this configuration owner has no approved rule set on the requested date. */
    public Optional<Evaluation> evaluateIfEffective(RuleSet.Type type, RuleSet.OwnerType ownerType, String ownerId,
                                                    LocalDate on, Map<String, Object> record) {
        Optional<RuleSet> selected = store.effective(type, ownerType, ownerId, on);
        if (selected.isEmpty()) return Optional.empty();
        return Optional.of(evaluate(selected.get(), record));
    }
    private Evaluation evaluate(RuleSet ruleSet, Map<String, Object> record) {
        try {
            DmnDecision decision = parse(ruleSet.dmnXml());
            List<FiredRule> fired = engine.evaluateDecisionTable(decision, record).stream().map(this::fired).toList();
            return new Evaluation(ruleSet, fired);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Rule set " + ruleSet.id() + " is not valid DMN", e);
        }
    }

    private DmnDecision parse(String dmnXml) {
        try {
            var model = Dmn.readModelFromStream(new ByteArrayInputStream(dmnXml.getBytes(StandardCharsets.UTF_8)));
            return engine.parseDecision("decision", model);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The rule set is not valid DMN with a decision named 'decision'", e);
        }
    }

    private FiredRule fired(DmnDecisionRuleResult result) {
        Map<String, Object> outputs = new LinkedHashMap<>(result);
        Object clause = outputs.get("clauseReference");
        if (clause == null || clause.toString().isBlank()) {
            throw new IllegalArgumentException("Every fired DMN rule must return clauseReference");
        }
        return new FiredRule(Map.copyOf(outputs), clause.toString());
    }
}
