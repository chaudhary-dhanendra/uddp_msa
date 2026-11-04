package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.OpSupport.VERSION;

import gov.uddp.shaping.api.LayoutOperation;
import gov.uddp.shaping.api.RawRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code section-rows@1.0} turns section title rows into a value carried by the records below them.
 *
 * <p>Config: {@code onlyFilled: [cols]} identifies a section row (exactly those columns filled) and
 * {@code carryAs: NAME} names the carried value. The section row is removed; its first filled cell, trimmed, is added
 * to the carried map under NAME of every following row of the same sheet until the next section row. Rows before the
 * first section row carry nothing.
 */
@Component
public class SectionRowsOperation implements LayoutOperation {

    @Override
    public String name() {
        return "section-rows";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<RawRow> apply(List<RawRow> rows, Map<String, Object> config) {
        List<String> columns = OpSupport.strings(config, "onlyFilled");
        String carryAs = OpSupport.requiredString(config, "carryAs", name());
        List<RawRow> result = new ArrayList<>();
        RawRow previous = null;
        String section = null;
        for (RawRow row : rows) {
            if (!OpSupport.sameSheet(previous, row)) {
                section = null;
            }
            previous = row;
            if (OpSupport.onlyFilled(row, columns)) {
                section = row.cells().get(OpSupport.filled(row).getFirst()).trim();
                continue;
            }
            result.add(section == null ? row : OpSupport.withCarried(row, carryAs, section));
        }
        return result;
    }
}
