package gov.uddp.core.monitoring;

import gov.uddp.core.monitoring.MonitoringService.Metric;
import java.util.Map;

/**
 * Platform-wide metrics that belong to no source (Track F3: LGD master age and sync failures). Alert rules on these
 * metrics are evaluated once, against {@link MonitoringService#PLATFORM}, instead of once per source.
 */
public interface PlatformMetricsSource {
    /** Current values; a metric that cannot be measured yet is absent. */
    Map<Metric, Double> platformMetrics();
}
