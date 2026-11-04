package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.OpSupport.VERSION;
import static gov.uddp.shaping.ops.OpSupport.blank;

import gov.uddp.shaping.api.LayoutOperation;
import gov.uddp.shaping.api.RawRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code carry-down@1.0} fills blank cells with the value from the row above (merged cells, grouped listings).
 *
 * <p>Config: {@code columns: [..]} names or zero-based indexes. The remembered value resets at each new sheet, so a
 * blank first cell of a sheet stays blank.
 */
@Component
public class CarryDownOperation implements LayoutOperation {

    @Override
    public String name() {
        return "carry-down";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<RawRow> apply(List<RawRow> rows, Map<String, Object> config) {
        List<String> columns = OpSupport.strings(config, "columns");
        List<RawRow> result = new ArrayList<>();
        RawRow previous = null;
        Map<Integer, String> last = new HashMap<>();
        for (RawRow row : rows) {
            if (!OpSupport.sameSheet(previous, row)) {
                last.clear();
            }
            previous = row;
            List<String> cells = new ArrayList<>(row.cells());
            for (int index : OpSupport.existingColumnIndexes(row, columns)) {
                if (index >= cells.size()) {
                    continue;
                }
                if (blank(cells.get(index))) {
                    if (last.get(index) != null) {
                        cells.set(index, last.get(index));
                    }
                } else {
                    last.put(index, cells.get(index));
                }
            }
            result.add(OpSupport.withCells(row, cells));
        }
        return result;
    }
}
