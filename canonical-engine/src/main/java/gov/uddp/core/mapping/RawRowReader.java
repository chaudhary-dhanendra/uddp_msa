package gov.uddp.core.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.shaping.api.RawRow;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Turns either generic raw payload into a shaping {@link RawRow}: the CSV payload
 * ({@code csvHeader}/{@code rawLine}) or the Excel payload ({@code format: xlsx} with {@code cells}).
 * It never interprets the cells.
 */
@Component
public class RawRowReader {
    private final ObjectMapper objectMapper;

    public RawRowReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RawRow read(String rawPayload) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawPayload);
        } catch (IOException e) {
            throw new IllegalArgumentException("Raw payload is not valid JSON", e);
        }
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Raw payload is not a JSON object");
        }
        if ("xlsx".equals(payload.path("format").asText(null))) {
            return excel(payload);
        }
        return csv(payload);
    }

    private static RawRow excel(JsonNode payload) {
        JsonNode cells = payload.path("cells");
        if (!cells.isArray()) {
            throw new IllegalArgumentException("Raw xlsx payload is missing cells");
        }
        List<String> values = new ArrayList<>();
        cells.forEach(cell -> values.add(cell.isNull() ? "" : cell.asText()));
        return new RawRow(payload.path("file").asText(null), payload.path("sheet").asText(null),
                payload.path("row").asInt(0), values, Map.of());
    }

    private static RawRow csv(JsonNode payload) {
        if (!payload.path("rawLine").isTextual()) {
            throw new IllegalArgumentException("Raw payload is missing textual rawLine");
        }
        try (CSVParser parser = new CSVParser(new StringReader(payload.get("rawLine").asText()), CSVFormat.RFC4180)) {
            List<CSVRecord> records = parser.getRecords();
            if (records.size() != 1) {
                throw new gov.uddp.core.validation.ReasonCodes.ReasonedException(gov.uddp.core.validation.ReasonCodes.UNREADABLE_ROW, "Expected one CSV record, found " + records.size());
            }
            return new RawRow(null, null, 0, records.getFirst().stream().toList(), Map.of());
        } catch (IOException e) {
            throw new gov.uddp.core.validation.ReasonCodes.ReasonedException(gov.uddp.core.validation.ReasonCodes.UNREADABLE_ROW, "The row is not valid CSV: " + e.getMessage(), e);
        }
    }
}
