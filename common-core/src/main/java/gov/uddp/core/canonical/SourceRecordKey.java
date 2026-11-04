package gov.uddp.core.canonical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.shaping.api.RecordShaper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Identity of one source record across re-deliveries (ADR-0003):
 * {@code stateCode|sourceSystem|sourceEntity|<values of the source's record key fields>}. The fields come from the
 * State Pack source definition ({@code recordKeyFields}, else {@code recordIdField}); nothing here is state-specific.
 */
public final class SourceRecordKey {
    private final RawCsvRecordParser parser;
    private final RecordShaper shaping;

    public SourceRecordKey(RawCsvRecordParser parser) {
        this(parser, null);
    }

    /** With a shaper, the key fields of an {@code xlsx} source are read from its tidy record (named by its header). */
    public SourceRecordKey(RawCsvRecordParser parser, RecordShaper shaping) {
        this.parser = parser;
        this.shaping = shaping;
    }

    public String of(RawRecord raw, LoadedStatePack pack) {
        var source = pack.sources().stream()
                .filter(item -> item.sourceId().equals(raw.sourceSystem()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("State Pack has no source: " + raw.sourceSystem()));
        Map<String, Object> values = shaping != null && "xlsx".equals(source.formatOrDefault())
                ? shaping.shape(raw, pack, source.sourceId()).fields()
                : parser.parse(raw.rawPayload());
        List<String> parts = new ArrayList<>(List.of(raw.stateCode(), raw.sourceSystem(), raw.sourceEntity()));
        for (String field : source.keyFields()) {
            Object value = values.get(field);
            if (value == null || value.toString().isBlank()) {
                throw new IllegalArgumentException("Source record key field is blank: " + field);
            }
            parts.add(value.toString().strip());
        }
        return String.join("|", parts);
    }

    /**
     * The canonical content that a re-delivery must change to create a new version: the payload without its
     * per-ingestion {@code metadata} and {@code uddp.*} lineage extensions.
     */
    public static JsonNode content(JsonNode payload) {
        ObjectNode copy = payload.deepCopy();
        copy.remove("metadata");
        if (copy.get("extensions") instanceof ObjectNode extensions) {
            var lineage = new ArrayList<String>();
            extensions.fieldNames().forEachRemaining(name -> {
                if (name.startsWith("uddp.")) {
                    lineage.add(name);
                }
            });
            extensions.remove(lineage);
        }
        return copy;
    }
}
