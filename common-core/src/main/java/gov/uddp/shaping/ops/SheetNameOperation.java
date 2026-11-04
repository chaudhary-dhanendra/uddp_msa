package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.OpSupport.VERSION;

import gov.uddp.shaping.api.LayoutOperation;
import gov.uddp.shaping.api.RawRow;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code sheet-name@1.0} exposes the sheet name as a value.
 *
 * <p>Config: {@code as: COLUMN}. Every row's carried map gains COLUMN = the row's sheet name (rows without a sheet
 * name, e.g. from CSV, are left as they are).
 */
@Component
public class SheetNameOperation implements LayoutOperation {

    @Override
    public String name() {
        return "sheet-name";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<RawRow> apply(List<RawRow> rows, Map<String, Object> config) {
        String column = OpSupport.requiredString(config, "as", name());
        return rows.stream().map(row -> row.sheet() == null ? row : OpSupport.withCarried(row, column, row.sheet()))
                .toList();
    }
}
