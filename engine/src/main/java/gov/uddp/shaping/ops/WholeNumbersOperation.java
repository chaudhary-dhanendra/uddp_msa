package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.OpSupport.VERSION;
import static gov.uddp.shaping.ops.OpSupport.blank;

import gov.uddp.shaping.api.LayoutOperation;
import gov.uddp.shaping.api.RawRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code whole-numbers@1.0} turns the "67.0" an Excel number cell is read as into "67".
 *
 * <p>Config: {@code columns: [..]} names or zero-based indexes. A cell that is an optionally signed integer followed by
 * a decimal part of zeros only ("1.0", "80000.00") loses the decimal part; every other cell is left unchanged.
 */
@Component
public class WholeNumbersOperation implements LayoutOperation {

    private static final Pattern WHOLE = Pattern.compile("([-+]?[0-9]+)[.]0+");

    @Override
    public String name() {
        return "whole-numbers";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<RawRow> apply(List<RawRow> rows, Map<String, Object> config) {
        List<String> columns = OpSupport.strings(config, "columns");
        List<RawRow> result = new ArrayList<>();
        for (RawRow row : rows) {
            List<String> cells = new ArrayList<>(row.cells());
            for (int index : OpSupport.existingColumnIndexes(row, columns)) {
                if (index < cells.size() && !blank(cells.get(index))) {
                    var whole = WHOLE.matcher(cells.get(index).trim());
                    if (whole.matches()) {
                        cells.set(index, whole.group(1));
                    }
                }
            }
            result.add(OpSupport.withCells(row, cells));
        }
        return result;
    }
}
