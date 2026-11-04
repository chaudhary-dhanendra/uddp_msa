package gov.uddp.ingestion.connector;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

/** Rows read from a source: header and values in column order (the shape the edge's Studio profiles). */
public record Table(List<String> header, List<Map<String, String>> rows, long totalRows) {

    public Table(List<String> header, List<Map<String, String>> rows) {
        this(header, rows, rows.size());
    }

    /** Reads a CSV file with a header row, as the edge's CsvProfiler does. */
    public static Table read(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true)
                    .setTrim(false).build().parse(reader);
            List<String> header = parser.getHeaderNames().stream()
                    .map(name -> name.startsWith("﻿") ? name.substring(1) : name).toList();
            if (header.isEmpty() || header.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("The file needs a header row with a name for every column");
            }
            if (header.stream().distinct().count() != header.size()) {
                throw new IllegalArgumentException("The header row names a column twice");
            }
            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (record.size() != header.size()) {
                    throw new IllegalArgumentException("Row " + (record.getRecordNumber() + 1) + " has " + record.size()
                            + " values but the header has " + header.size() + " columns");
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    row.put(header.get(i), record.get(i));
                }
                rows.add(row);
            }
            return new Table(header, rows);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file.getFileName(), e);
        } catch (UncheckedIOException | IllegalStateException e) {
            throw new IllegalArgumentException("The file is not valid CSV: " + e.getMessage(), e);
        }
    }
}
