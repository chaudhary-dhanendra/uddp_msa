package gov.uddp.core.studio;

import gov.uddp.core.onboarding.DatabaseSources;
import gov.uddp.core.studio.CsvProfiler.Table;
import gov.uddp.core.studio.StudioModels.Source;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Reads a draft's source rows: the landed CSV file, a workbook's tidy records (its selected sheets shaped by the
 * source's layout and parse steps), or a table or query of a named connection.
 */
@Component
public class SourceRows {
    private final CsvProfiler profiler;
    private final DatabaseSources databases;
    private final ShapePreviewService shaping;

    public SourceRows(CsvProfiler profiler, DatabaseSources databases, ShapePreviewService shaping) {
        this.profiler = profiler;
        this.databases = databases;
        this.shaping = shaping;
    }

    /** Workbooks are read whole by the shaper, so their sample is the first rows (docs/perf.md). */
    private static final int WORKBOOK_ROWS = 5000;

    /** The first {@code head} rows plus {@code random} drawn from the rest of a file, with the file's row count. */
    public Table sample(Source source, int head, int random) {
        if ("database".equals(source.sourceType())) {
            var rows = databases.read(source.connectionName(), source.table(), source.query(), head + random);
            return new Table(rows.header(), rows.rows());
        }
        if (RawSheets.isWorkbook(Path.of(source.location()))) {
            return shaping.table(source, WORKBOOK_ROWS);
        }
        return profiler.sample(Path.of(source.location()), head, random, new java.util.Random());
    }

    public Table read(Source source, int limit) {
        if ("database".equals(source.sourceType())) {
            var rows = databases.read(source.connectionName(), source.table(), source.query(), limit);
            return new Table(rows.header(), rows.rows());
        }
        if (RawSheets.isWorkbook(Path.of(source.location()))) {
            return shaping.table(source, limit);
        }
        Table table = profiler.read(Path.of(source.location()));
        return new Table(table.header(), table.rows().subList(0, Math.min(limit, table.rows().size())));
    }
}
