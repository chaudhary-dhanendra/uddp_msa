package gov.uddp.ingestion;

import java.util.Map;

/** Runs one generic Hop template that lands rows in this service's raw_record. Hop does technical ingestion only. */
public interface HopExecutor {

    Result run(String template, Map<String, String> parameters);

    /**
     * As {@link #run(String, Map)}, with settings that must never appear on a command line (a database source's
     * connection and password); they reach Hop like the platform's own database settings.
     */
    default Result run(String template, Map<String, String> parameters, Map<String, String> secretSettings) {
        if (!secretSettings.isEmpty()) {
            throw new UnsupportedOperationException("This executor cannot run database sources");
        }
        return run(template, parameters);
    }

    record Result(int exitCode, String output) {
        public boolean succeeded() {
            return exitCode == 0;
        }
    }
}
