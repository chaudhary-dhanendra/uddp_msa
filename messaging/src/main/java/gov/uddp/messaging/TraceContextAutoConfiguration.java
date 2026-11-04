package gov.uddp.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Bridges {@link TraceContext} to the application's Micrometer tracer (OpenTelemetry), when the application traces.
 * Every service that uses the messaging module gets trace propagation over Kafka without code of its own.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration"})
@ConditionalOnClass(Tracer.class)
public class TraceContextAutoConfiguration {

    @Bean
    @ConditionalOnBean({Tracer.class, Propagator.class})
    TraceContext.Bridge micrometerTraceBridge(Tracer tracer, Propagator propagator) {
        TraceContext.Bridge bridge = new TraceContext.Bridge() {
            @Override
            public String current() {
                Span span = tracer.currentSpan();
                if (span == null) {
                    return null;
                }
                Map<String, String> carrier = new HashMap<>();
                propagator.inject(span.context(), carrier, Map::put);
                return carrier.get(TraceContext.FIELD);
            }

            @Override
            public void inSpan(String traceparent, String name, TraceContext.Work work) throws Exception {
                Span span = traceparent == null ? tracer.nextSpan().name(name).start()
                        : propagator.extract(Map.of(TraceContext.FIELD, traceparent), Map::get).name(name)
                                .kind(Span.Kind.CONSUMER).start();
                try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
                    work.run();
                } catch (Exception | Error e) {
                    span.error(e);
                    throw e;
                } finally {
                    span.end();
                }
            }
        };
        TraceContext.use(bridge);
        return bridge;
    }
}
