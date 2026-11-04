package gov.uddp.core.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/** Parses the generic Hop raw payload without knowing a State Pack's physical schema. */
@Component
public class RawCsvRecordParser {
    private final ObjectMapper objectMapper;

    public RawCsvRecordParser(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public Map<String, Object> parse(String rawPayload) {
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            String header = requiredText(payload, "csvHeader");
            String line = requiredText(payload, "rawLine");
            List<String> fields = singleRecord(header, CSVFormat.RFC4180);
            List<String> values = singleRecord(line, CSVFormat.RFC4180);
            if (fields.size() != values.size()) throw new IllegalArgumentException("CSV header/value column count differs");
            Map<String, Object> result = new LinkedHashMap<>();
            for (int index = 0; index < fields.size(); index++) {
                if (fields.get(index).isBlank()) throw new IllegalArgumentException("CSV header contains a blank field name");
                if (result.put(fields.get(index), values.get(index)) != null) throw new IllegalArgumentException("CSV header contains duplicate field: " + fields.get(index));
            }
            return result;
        } catch (IOException e) {
            throw new IllegalArgumentException("Raw payload is not valid CSV-in-JSON", e);
        }
    }

    private static String requiredText(JsonNode payload, String name) {
        if (payload == null || !payload.path(name).isTextual()) throw new IllegalArgumentException("Raw payload is missing textual " + name);
        return payload.get(name).asText();
    }

    private static List<String> singleRecord(String text, CSVFormat format) throws IOException {
        try (CSVParser parser = new CSVParser(new StringReader(text), format)) {
            List<CSVRecord> records = parser.getRecords();
            if (records.size() != 1) throw new IllegalArgumentException("Expected one CSV record, found " + records.size());
            return records.getFirst().stream().toList();
        }
    }
}
