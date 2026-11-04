package gov.uddp.core.studio;

import gov.uddp.core.studio.StudioModels.ColumnProfile;
import gov.uddp.core.studio.StudioModels.Profile;
import gov.uddp.core.studio.StudioModels.ValueCount;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Profiles a delimited source file: per column the inferred type, detected date format, blanks, distinct values, top
 * values and sample rows. Generic: it knows nothing about any source's columns.
 */
@Component
public class CsvProfiler {
    private static final int TOP_VALUES = 50;
    private static final int SAMPLE_ROWS = 5;
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");
    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+\\.[0-9]+");
    private static final Pattern MOBILE = Pattern.compile("(\\+91[- ]?)?[6-9][0-9]{9}");

    /** A parsed file: header and rows as maps in column order; {@code totalRows} is the file's row count (rows may be a sample). */
    public record Table(List<String> header, List<Map<String, String>> rows, long totalRows) {
        public Table(List<String> header, List<Map<String, String>> rows) {
            this(header, rows, rows.size());
        }
    }

    public Table read(Path file) {
        List<Map<String, String>> rows = new ArrayList<>();
        List<String> header = scan(file, rows::add);
        return new Table(header, rows);
    }

    /**
     * Profiles a large file from a sample without holding it: the first {@code head} rows plus {@code random} rows
     * drawn uniformly from the rest (reservoir sampling), and the file's total row count.
     */
    public Table sample(Path file, int head, int random, java.util.Random generator) {
        List<Map<String, String>> first = new ArrayList<>();
        List<Map<String, String>> reservoir = new ArrayList<>();
        long[] seen = {0};
        List<String> header = scan(file, row -> {
            long index = seen[0]++;
            if (index < head) {
                first.add(row);
            } else {
                long drawn = index - head;
                if (reservoir.size() < random) {
                    reservoir.add(row);
                } else {
                    long slot = (long) (generator.nextDouble() * (drawn + 1));
                    if (slot < random) {
                        reservoir.set((int) slot, row);
                    }
                }
            }
        });
        first.addAll(reservoir);
        return new Table(header, first, seen[0]);
    }

    /** Streams the file's rows to {@code rows} in order and returns the header; the file is never held in memory. */
    private List<String> scan(Path file, java.util.function.Consumer<Map<String, String>> rows) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var format = CSVFormat.RFC4180.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .setTrim(false)
                    .build();
            var parser = format.parse(reader);
            List<String> header = parser.getHeaderNames().stream().map(CsvProfiler::stripBom).toList();
            if (header.isEmpty() || header.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("The file needs a header row with a name for every column");
            }
            if (header.stream().distinct().count() != header.size()) {
                throw new IllegalArgumentException("The header row names a column twice");
            }
            for (CSVRecord record : parser) {
                if (record.size() != header.size()) {
                    throw new IllegalArgumentException("Row " + (record.getRecordNumber() + 1) + " has " + record.size()
                            + " values but the header has " + header.size() + " columns");
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    row.put(header.get(i), record.get(i));
                }
                rows.accept(row);
            }
            return header;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file.getFileName(), e);
        } catch (java.io.UncheckedIOException | IllegalStateException e) {
            throw new IllegalArgumentException("The file is not valid CSV: " + e.getMessage(), e);
        }
    }

    public Profile profile(Path file) {
        return profile(read(file));
    }

    public Profile profile(Table table) {
        List<ColumnProfile> columns = new ArrayList<>();
        for (String column : table.header()) {
            List<String> values = table.rows().stream().map(row -> row.get(column)).toList();
            columns.add(profile(column, values));
        }
        return new Profile((int) table.totalRows(), columns,
                table.rows().subList(0, Math.min(SAMPLE_ROWS, table.rows().size())), table.rows().size(),
                table.totalRows());
    }

    ColumnProfile profile(String column, List<String> values) {
        List<String> present = values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        present.forEach(v -> counts.merge(v, 1, Integer::sum));
        List<ValueCount> top = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(TOP_VALUES)
                .map(e -> new ValueCount(e.getKey(), e.getValue()))
                .toList();
        String dateFormat = dateFormat(present);
        String type = inferType(present, dateFormat);
        return new ColumnProfile(column, type, dateFormat, values.size() - present.size(), counts.size(), top,
                !present.isEmpty() && counts.size() == present.size() && present.size() == values.size());
    }

    /** The first configured date format every value parses with, as a State Pack conversion pattern. */
    static String dateFormat(List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        for (String conversion : StudioMetadata.DATE_CONVERSIONS) {
            String pattern = conversion.substring("date:".length());
            DateTimeFormatter formatter = formatter(pattern);
            boolean all = values.stream().allMatch(value -> parses(value, formatter));
            if (all) {
                return pattern;
            }
        }
        return null;
    }

    static DateTimeFormatter formatter(String configured) {
        String java = configured.replace("YYYY", "uuuu").replace("DD", "dd");
        return DateTimeFormatter.ofPattern(java).withResolverStyle(ResolverStyle.STRICT);
    }

    private static boolean parses(String value, DateTimeFormatter formatter) {
        try {
            LocalDate date = LocalDate.parse(value, formatter);
            return date.getYear() >= 1900 && date.getYear() <= 2100;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String inferType(List<String> values, String dateFormat) {
        if (values.isEmpty()) {
            return "empty";
        }
        if (dateFormat != null) {
            return "date";
        }
        if (values.stream().allMatch(v -> MOBILE.matcher(v).matches())) {
            return "mobile";
        }
        // Values of a sensitive registry identifier type: flagged so they are mapped to an identifiers target.
        if (values.stream().allMatch(v -> gov.uddp.core.privacy.NationalIdLeakGuard.sensitiveTypeOf(v.strip()).isPresent())) {
            return "national-id";
        }
        if (values.stream().allMatch(v -> INTEGER.matcher(v).matches())) {
            return "integer";
        }
        if (values.stream().allMatch(v -> INTEGER.matcher(v).matches() || DECIMAL.matcher(v).matches())) {
            return "decimal";
        }
        return "text";
    }

    private static String stripBom(String value) {
        return value.startsWith("﻿") ? value.substring(1) : value;
    }
}
