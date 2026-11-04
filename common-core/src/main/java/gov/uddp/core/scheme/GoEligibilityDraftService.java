package gov.uddp.core.scheme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.ai.AiDisabledException;
import gov.uddp.core.ai.AiDraftService;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.rules.DecisionTableDmn;
import gov.uddp.core.rules.RuleSet;
import gov.uddp.core.rules.RuleSetService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.camunda.bpm.dmn.engine.DmnEngine;
import org.camunda.bpm.dmn.engine.DmnEngineConfiguration;
import org.camunda.bpm.model.dmn.Dmn;
import org.springframework.stereotype.Service;

/**
 * Track A6: drafts a Scheme Pack's eligibility rule set from its government order (GO) PDF. Only the GO's text is
 * sent to the configured {@link AiDraftService} (never resident data); the answer must be a Rules Studio decision
 * table with a clause reference per row. The result is stored as an ordinary DRAFT rule set marked
 * {@value #MARKER}; it follows the normal maker-checker and is never approved automatically.
 */
@Service
public class GoEligibilityDraftService {
    public static final String MARKER = "AI draft - verify against GO";
    static final int MAX_DOCUMENT_CHARS = 200_000;

    static final String INSTRUCTION = """
            You draft eligibility rules from the government order (GO) text supplied as content.
            Answer with JSON only, exactly in this shape:
            {"rows":[{"condition":"<FEEL boolean expression over applicant attributes>","output":"ELIGIBLE or NOT_ELIGIBLE","clauseReference":"<GO paragraph or clause the row comes from>"}]}
            Use one row per condition stated in the GO. Every row must cite the clause it comes from.
            Use plain attribute names such as age, gender, annualIncome. Do not invent conditions the GO does not state.""";

    private final AiDraftService ai;
    private final RuleSetService rules;
    private final SchemePackService schemes;
    private final PostgresConnectionFactory connections;
    private final ObjectMapper json;
    private final DmnEngine engine = DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();

    public GoEligibilityDraftService(AiDraftService ai, RuleSetService rules, SchemePackService schemes,
                                     PostgresConnectionFactory connections, ObjectMapper json) {
        this.ai = ai;
        this.rules = rules;
        this.schemes = schemes;
        this.connections = connections;
        this.json = json;
    }

    public boolean enabled() {
        return ai.enabled();
    }

    public record AiRuleDraft(UUID ruleSetId, int version, RuleSet.Status status, String marker, String model,
                              String documentName, String documentSha256, List<String> requiredAttributes,
                              List<DecisionTableDmn.DecisionRow> rows) {
    }

    public AiRuleDraft draftFromGo(String schemeId, UUID versionId, String documentName, byte[] pdf, String maker) {
        if (!ai.enabled()) {
            throw new AiDisabledException();
        }
        if (maker == null || maker.isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException("A signed-in user is required");
        }
        SchemePackService.Version version = schemes.version(schemeId, versionId);
        if (!"DRAFT".equals(version.status())) {
            throw new IllegalArgumentException("Only a DRAFT Scheme Pack version can receive an AI eligibility draft");
        }
        String text = extractText(pdf);

        DecisionTableDmn.DecisionTable table = parse(ai.complete(INSTRUCTION, text));
        List<String> attributes = DecisionTableDmn.attributes(table);
        String dmn = DecisionTableDmn.toDmn(table, MARKER + " (" + schemeId + ")");
        checkEvaluates(dmn, attributes);

        RuleSet draft = rules.createDraft(new RuleSetService.Draft(RuleSet.Type.ELIGIBILITY, RuleSet.OwnerType.SCHEME,
                schemeId, version.effectiveFrom(), version.effectiveTo(), dmn, attributes), maker);
        String sha256 = sha256(pdf);
        record(draft.id(), schemeId, versionId, documentName, sha256, maker);
        schemes.setEligibilityRuleSet(versionId, draft.id());
        return new AiRuleDraft(draft.id(), draft.version(), draft.status(), MARKER, ai.model(), documentName, sha256,
                draft.requiredAttributes(), table.rows());
    }

    /** The AI provenance of a rule set, if it was drafted by a model. */
    public java.util.Optional<String> marker(UUID ruleSetId) {
        try (var c = connections.open(); var s = c.prepareStatement("select marker from ai_rule_draft where rule_set_id=?")) {
            s.setObject(1, ruleSetId);
            var r = s.executeQuery();
            return r.next() ? java.util.Optional.of(r.getString(1)) : java.util.Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read AI draft provenance", e);
        }
    }

    static String extractText(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("Upload the GO as a PDF");
        }
        String text;
        try (var document = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(document);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("The uploaded file is not a readable PDF", e);
        }
        text = text == null ? "" : text.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("The PDF has no extractable text (a scanned GO needs OCR first)");
        }
        return text.length() > MAX_DOCUMENT_CHARS ? text.substring(0, MAX_DOCUMENT_CHARS) : text;
    }

    private DecisionTableDmn.DecisionTable parse(String raw) {
        try {
            String body = raw == null ? "" : raw.strip();
            if (body.startsWith("```")) {
                body = body.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
            }
            JsonNode rows = json.readTree(body).path("rows");
            if (!rows.isArray()) {
                throw new IllegalArgumentException("no rows array");
            }
            List<DecisionTableDmn.DecisionRow> out = new ArrayList<>();
            for (JsonNode row : rows) {
                out.add(new DecisionTableDmn.DecisionRow(text(row, "condition"), text(row, "output"), text(row, "clauseReference")));
            }
            DecisionTableDmn.DecisionTable table = new DecisionTableDmn.DecisionTable(out);
            DecisionTableDmn.validate(table);
            return table;
        } catch (Exception e) {
            throw new IllegalArgumentException("AI output rejected: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || !value.isValueNode() ? null : value.asText();
    }

    /** Every condition must be valid FEEL; evaluating with all attributes absent surfaces syntax errors. */
    private void checkEvaluates(String dmn, List<String> attributes) {
        try {
            var model = Dmn.readModelFromStream(new ByteArrayInputStream(dmn.getBytes(StandardCharsets.UTF_8)));
            Map<String, Object> blank = new HashMap<>();
            attributes.forEach(a -> blank.put(a.contains(".") ? a.substring(0, a.indexOf('.')) : a, null));
            engine.evaluateDecisionTable(engine.parseDecision("decision", model), blank);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("AI output rejected: a condition is not valid FEEL", e);
        }
    }

    private void record(UUID ruleSetId, String schemeId, UUID versionId, String documentName, String sha256, String maker) {
        String sql = "insert into ai_rule_draft(rule_set_id,scheme_id,scheme_pack_version_id,marker,document_name,"
                + "document_sha256,model,created_by) values (?,?,?,?,?,?,?,?)";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, ruleSetId);
            s.setString(2, schemeId);
            s.setObject(3, versionId);
            s.setString(4, MARKER);
            s.setString(5, documentName);
            s.setString(6, sha256);
            s.setString(7, ai.model());
            s.setString(8, maker);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record AI draft provenance", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
