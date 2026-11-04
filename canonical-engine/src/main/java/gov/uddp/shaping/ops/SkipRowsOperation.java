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
 * {@code skip-rows@1.0} drops rows (totals, banners, blank lines).
 *
 * <p>Config: {@code rules: [...]}, a row is dropped when any rule matches. Rule shapes:
 * <ul>
 *   <li>{@code {firstColumn: regex}} the first cell contains a match;</li>
 *   <li>{@code {anyColumn: regex}} any cell contains a match;</li>
 *   <li>{@code {onlyFilled: [cols]}} exactly those columns (names or zero-based indexes) are filled;</li>
 *   <li>{@code {allBlank: true}} every cell is blank.</li>
 * </ul>
 */
@Component
public class SkipRowsOperation implements LayoutOperation {

    @Override
    public String name() {
        return "skip-rows";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<RawRow> apply(List<RawRow> rows, Map<String, Object> config) {
        List<Map<?, ?>> rules = new ArrayList<>();
        if (config.get("rules") instanceof List<?> list) {
            list.stream().filter(Map.class::isInstance).forEach(rule -> rules.add((Map<?, ?>) rule));
        }
        return rows.stream().filter(row -> rules.stream().noneMatch(rule -> matches(rule, row))).toList();
    }

    private static boolean matches(Map<?, ?> rule, RawRow row) {
        if (rule.get("firstColumn") != null) {
            String cell = OpSupport.cell(row, 0);
            return cell != null && Pattern.compile(rule.get("firstColumn").toString()).matcher(cell).find();
        }
        if (rule.get("anyColumn") != null) {
            Pattern pattern = Pattern.compile(rule.get("anyColumn").toString());
            return row.cells().stream().anyMatch(cell -> cell != null && pattern.matcher(cell).find());
        }
        if (rule.get("onlyFilled") != null) {
            List<String> columns = rule.get("onlyFilled") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList() : List.of(rule.get("onlyFilled").toString());
            return OpSupport.onlyFilled(row, columns);
        }
        if (rule.get("allBlank") != null) {
            return Boolean.parseBoolean(rule.get("allBlank").toString())
                    && row.cells().stream().allMatch(cell -> blank(cell));
        }
        throw new IllegalArgumentException(
                "skip-rows rule needs firstColumn, anyColumn, onlyFilled or allBlank: " + rule);
    }
}
