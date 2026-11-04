package gov.uddp.messaging;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Carries the distributed trace across Kafka (W3C {@code traceparent}). The outbox stores the trace current when an
 * event is written (as the envelope's {@code traceparent} field, not part of the contract), the relay copies it into
 * a Kafka header, and the consuming loop handles the event in a span that continues that trace, so one onboarding run
 * is one trace across the services. Without a tracing bridge (no tracer in the application) everything is a no-op.
 */
public final class TraceContext {

    /** The envelope field that holds the trace the event was written in. */
    public static final String FIELD = "traceparent";

    /** Work to run inside a span. */
    @FunctionalInterface
    public interface Work {
        void run() throws Exception;
    }

    /** The application's tracer, bridged (see {@code TraceContextAutoConfiguration}). */
    public interface Bridge {

        /** The current trace as a W3C traceparent, or null outside a trace. */
        String current();

        /** Runs {@code work} in a new span named {@code name}, the child of {@code traceparent} when it is given. */
        void inSpan(String traceparent, String name, Work work) throws Exception;
    }

    private static final Bridge NONE = new Bridge() {
        @Override
        public String current() {
            return null;
        }

        @Override
        public void inSpan(String traceparent, String name, Work work) throws Exception {
            work.run();
        }
    };

    private static volatile Bridge bridge = NONE;

    private TraceContext() {
    }

    public static void use(Bridge tracing) {
        bridge = tracing == null ? NONE : tracing;
    }

    public static String current() {
        return bridge.current();
    }

    public static void inSpan(String traceparent, String name, Work work) throws Exception {
        bridge.inSpan(traceparent, name, work);
    }

    /** The trace an envelope was written in, or null. */
    public static String of(JsonNode envelope) {
        JsonNode value = envelope == null ? null : envelope.get(FIELD);
        return value == null || !value.isTextual() ? null : value.asText();
    }
}
