package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.OpSupport.HEADER_PREFIX;
import static gov.uddp.shaping.ops.OpSupport.VERSION;

import gov.uddp.shaping.api.LayoutOperation;
import gov.uddp.shaping.api.RawRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code column-aliases@1.0} renames detected column names to logical names.
 *
 * <p>Config: {@code aliases: {LOGICAL: [variant, ...]}}. A column whose name equals the logical name or one of its
 * variants, ignoring case and whitespace, is renamed to LOGICAL; other columns keep their names. Needs header-detect
 * to run first (rows without column names pass through).
 */
@Component
public class ColumnAliasesOperation implements LayoutOperation {

    @Override
    public String name() {
        return "column-aliases";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<RawRow> apply(List<RawRow> rows, Map<String, Object> config) {
        Map<String, String> logicalByVariant = new HashMap<>();
        if (config.get("aliases") instanceof Map<?, ?> aliases) {
            aliases.forEach((logical, variants) -> {
                logicalByVariant.put(OpSupport.normalize(logical.toString()), logical.toString());
                if (variants instanceof List<?> list) {
                    list.forEach(variant -> logicalByVariant.put(OpSupport.normalize(String.valueOf(variant)),
                            logical.toString()));
                }
            });
        }
        List<RawRow> result = new ArrayList<>();
        for (RawRow row : rows) {
            Map<String, String> carried = new LinkedHashMap<>(row.carried());
            carried.replaceAll((key, value) -> key.startsWith(HEADER_PREFIX) && value != null
                    ? logicalByVariant.getOrDefault(OpSupport.normalize(value), value) : value);
            result.add(OpSupport.withCarried(row, carried));
        }
        return result;
    }
}
