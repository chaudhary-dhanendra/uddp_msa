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
 * {@code numbers@1.0} removes digit grouping from numbers.
 *
 * <p>Config: {@code grouping: indian|western} (default indian) and {@code columns: [..]} names or zero-based indexes.
 * "25,23,809" (indian) or "2,523,809" (western) becomes "2523809"; an optional sign and decimal part are kept.
 * Cells that do not have the configured grouping (including already plain numbers) are left unchanged.
 */
@Component
public class NumbersOperation implements LayoutOperation {

    private static final Pattern INDIAN = Pattern.compile("[-+]?\\d{1,2}(,\\d{2})*,\\d{3}(\\.\\d+)?");
    private static final Pattern WESTERN = Pattern.compile("[-+]?\\d{1,3}(,\\d{3})+(\\.\\d+)?");

    @Override
    public String name() {
        return "numbers";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<RawRow> apply(List<RawRow> rows, Map<String, Object> config) {
        String grouping = String.valueOf(config.getOrDefault("grouping", "indian")).toLowerCase();
        if (!grouping.equals("indian") && !grouping.equals("western")) {
            throw new IllegalArgumentException("numbers grouping must be indian or western: " + grouping);
        }
        Pattern pattern = grouping.equals("indian") ? INDIAN : WESTERN;
        List<String> columns = OpSupport.strings(config, "columns");
        List<RawRow> result = new ArrayList<>();
        for (RawRow row : rows) {
            List<String> cells = new ArrayList<>(row.cells());
            for (int index : OpSupport.existingColumnIndexes(row, columns)) {
                if (index < cells.size() && !blank(cells.get(index))
                        && pattern.matcher(cells.get(index).trim()).matches()) {
                    cells.set(index, cells.get(index).trim().replace(",", ""));
                }
            }
            result.add(OpSupport.withCells(row, cells));
        }
        return result;
    }
}
