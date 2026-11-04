package gov.uddp.core.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.shaping.api.RawRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawRowReaderTest {
    private final RawRowReader reader = new RawRowReader(new ObjectMapper());

    @Test
    void readsCsvPayloadLineAsCells() {
        RawRow row = reader.read("{\"csvHeader\":\"a,b,c\",\"rawLine\":\"1,\\\"x, y\\\",\"}");
        assertThat(row.cells()).containsExactly("1", "x, y", "");
        assertThat(row.file()).isNull();
        assertThat(row.sheet()).isNull();
    }

    @Test
    void readsXlsxPayloadWithFileSheetRowAndCells() {
        RawRow row = reader.read("{\"format\":\"xlsx\",\"file\":\"book.xlsx\",\"sheet\":\"S1\",\"row\":4,"
                + "\"cells\":[\"a\",\"\",null,\"1,5\"]}");
        assertThat(row.file()).isEqualTo("book.xlsx");
        assertThat(row.sheet()).isEqualTo("S1");
        assertThat(row.row()).isEqualTo(4);
        assertThat(row.cells()).isEqualTo(List.of("a", "", "", "1,5"));
    }

    @Test
    void rejectsMalformedPayloads() {
        assertThatThrownBy(() -> reader.read("{\"format\":\"xlsx\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read("{\"csvHeader\":\"a\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read("not json")).isInstanceOf(IllegalArgumentException.class);
    }
}
