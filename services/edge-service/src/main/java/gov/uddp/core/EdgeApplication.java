package gov.uddp.core;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The edge application's configuration root (formerly the monolith's entry point). It has no main method: the
 * service starts only through {@code gov.uddp.edge.EdgeServiceApplication}, which always adds
 * edge.yml, so every pipeline stage and both Studios run in their services. Tests start this
 * configuration on application.yml alone and run the engines in-process.
 */
@SpringBootApplication(scanBasePackages = {"gov.uddp.core", "gov.uddp.shaping"})
public class EdgeApplication {
}
