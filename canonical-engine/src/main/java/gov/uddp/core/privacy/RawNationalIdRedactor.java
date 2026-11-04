package gov.uddp.core.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.identity.IdentifierProtection;
import gov.uddp.core.identity.IdentifierType;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.mapping.ResidentMappingEngine;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * After a raw record has been processed successfully, its identifier columns ({@code identifiers.*} and
 * {@code nationalIds.*} targets whose type is not stored PLAIN) are replaced in the raw payload by the protected token
 * ({@code uddp-id:TYPE:ISSUER:last4:value}), so the clear value leaves the platform's staging table too while the
 * record can still be replayed (P1 Part 5b, Track E2). On by default; {@code uddp.privacy.redact-raw-national-ids}
 * turns it off.
 */
@Component
public class RawNationalIdRedactor {
    private final PostgresConnectionFactory connections;
    private final ObjectMapper json;
    private final boolean enabled;
    private IdentifierProtection identifiers;

    public RawNationalIdRedactor(PostgresConnectionFactory connections, NationalIdProtector protector, ObjectMapper json,
                                 @Value("${uddp.privacy.redact-raw-national-ids:true}") boolean enabled) {
        this.connections = connections;
        this.json = json;
        this.enabled = enabled;
        this.identifiers = IdentifierProtection.withSeed(protector);
    }

    @Autowired(required = false)
    public void setIdentifiers(IdentifierProtection identifiers) {
        this.identifiers = identifiers;
    }

    /** The source columns the pack maps to identifier targets for this source, with their rules. */
    static Map<String, MappingRule> identifierColumns(LoadedStatePack pack, String sourceId) {
        Map<String, MappingRule> columns = new LinkedHashMap<>();
        pack.mappings().stream().filter(mapping -> mapping.sourceId().equals(sourceId)).forEach(mapping ->
                mapping.entities().values().forEach(rules -> rules.forEach((MappingRule rule) -> {
                    if (ResidentMappingEngine.identifierType(rule.target()) != null) {
                        columns.put(rule.sourceField(), rule);
                    }
                })));
        return columns;
    }

    /** Redacts the record's national ID columns; true when the payload changed. */
    public boolean redact(RawRecord raw, LoadedStatePack pack) {
        if (!enabled) {
            return false;
        }
        Map<String, MappingRule> columns = identifierColumns(pack, raw.sourceSystem());
        if (columns.isEmpty()) {
            return false;
        }
        try {
            ObjectNode payload = (ObjectNode) json.readTree(raw.rawPayload());
            if ("xlsx".equals(payload.path("format").asText(null))) {
                // Cells carry no column names here, so a protected (non-PLAIN) identifier cannot be replaced yet:
                // fail closed rather than leave the clear value in staging. PLAIN evidence types need no redaction.
                boolean protectedColumn = columns.values().stream().anyMatch(rule ->
                        identifiers.registry().find(ResidentMappingEngine.identifierType(rule.target()))
                                .map(type -> type.storage() != IdentifierType.Storage.PLAIN).orElse(false));
                if (protectedColumn) {
                    throw new IllegalStateException("Raw redaction of protected identifiers in xlsx sources is not supported");
                }
                return false;
            }
            List<String> header = record(payload.path("csvHeader").asText());
            List<String> values = new ArrayList<>(record(payload.path("rawLine").asText()));
            boolean changed = false;
            for (int i = 0; i < header.size() && i < values.size(); i++) {
                MappingRule rule = columns.get(header.get(i));
                String value = values.get(i);
                if (rule == null || value == null || value.isBlank() || IdentifierProtection.isToken(value)) {
                    continue;
                }
                String type = ResidentMappingEngine.identifierType(rule.target());
                var known = identifiers.registry().find(type);
                if (known.isEmpty() || known.get().storage() == IdentifierType.Storage.PLAIN) {
                    continue;
                }
                try {
                    values.set(i, identifiers.protect(type, ResidentMappingEngine.issuer(rule, raw.stateCode()), value).token());
                } catch (IllegalArgumentException invalid) {
                    if (!rule.flagsInvalid()) {
                        throw invalid;
                    }
                    values.set(i, IdentifierProtection.INVALID_TOKEN_PREFIX + known.get().code());
                }
                changed = true;
            }
            if (!changed) {
                return false;
            }
            StringWriter line = new StringWriter();
            try (CSVPrinter printer = new CSVPrinter(line, CSVFormat.RFC4180.builder().setRecordSeparator("").build())) {
                printer.printRecord(values);
            }
            payload.put("rawLine", line.toString());
            try (var c = connections.open();
                    var s = c.prepareStatement("update raw_record set raw_payload = ? where raw_record_id = ?")) {
                s.setString(1, json.writeValueAsString(payload));
                s.setObject(2, raw.rawRecordId());
                s.executeUpdate();
            }
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot redact the raw record's identifiers", e);
        }
    }

    private static List<String> record(String text) throws java.io.IOException {
        try (CSVParser parser = new CSVParser(new StringReader(text), CSVFormat.RFC4180)) {
            return parser.getRecords().getFirst().stream().toList();
        }
    }
}
