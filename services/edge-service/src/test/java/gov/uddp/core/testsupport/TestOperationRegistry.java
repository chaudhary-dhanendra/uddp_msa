package gov.uddp.core.testsupport;

import gov.uddp.shaping.OperationRegistry;
import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.ParseResult;
import gov.uddp.shaping.ops.CarryDownOperation;
import gov.uddp.shaping.ops.ColumnAliasesOperation;
import gov.uddp.shaping.ops.DittoOperation;
import gov.uddp.shaping.ops.HeaderDetectOperation;
import gov.uddp.shaping.ops.NumbersOperation;
import gov.uddp.shaping.ops.SectionRowsOperation;
import gov.uddp.shaping.ops.SheetNameOperation;
import gov.uddp.shaping.ops.SkipRowsOperation;
import gov.uddp.shaping.ops.WholeNumbersOperation;
import gov.uddp.shaping.ops.WideToLongOperation;
import java.util.List;
import java.util.Map;

/** Built-in-only registry for fixture pack validation; plug-ins are deliberately never loaded in tests. */
public final class TestOperationRegistry {
    private TestOperationRegistry() { }

    public static OperationRegistry builtIns() {
        var operations = List.of(new CarryDownOperation(), new ColumnAliasesOperation(), new DittoOperation(),
                new HeaderDetectOperation(), new NumbersOperation(), new SectionRowsOperation(), new SheetNameOperation(),
                new SkipRowsOperation(), new WholeNumbersOperation(), new WideToLongOperation());
        var parsers = List.of("free-address", "id-classify", "masked-id", "name-normalise", "name-relation",
                "name-relation-age", "unit-quantity").stream().map(TestParser::new).map(p -> (FieldParser) p).toList();
        return new OperationRegistry(operations, parsers, "none", "none.yaml");
    }

    private record TestParser(String name) implements FieldParser {
        @Override public String version() { return "1.0"; }
        @Override public ParseResult parse(String value, Map<String, Object> config) { throw new UnsupportedOperationException(); }
    }
}
