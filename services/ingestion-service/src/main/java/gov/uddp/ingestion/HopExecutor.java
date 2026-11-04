package gov.uddp.ingestion;

import java.util.Map;

/** Runs one generic Hop template that lands rows in this service's raw_record. Hop does technical ingestion only. */
public interface HopExecutor {

    Result run(String template, Map<String, String> parameters);

    record Result(int exitCode, String output) {
        public boolean succeeded() {
            return exitCode == 0;
        }
    }
}
