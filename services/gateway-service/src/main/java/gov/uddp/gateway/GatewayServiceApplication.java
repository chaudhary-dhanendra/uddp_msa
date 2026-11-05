package gov.uddp.gateway;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * The UDDP API gateway, the platform's single public entry. Everything the UI calls goes to the edge
 * (edge-service), found by name through discovery ({@code lb://}), which checks sign-in, permissions and
 * state scope; the pipeline services and their internal APIs are never routed here. The caller cannot impersonate the
 * edge or another service: incoming X-UDDP-Actor and X-UDDP-Service-Token headers are dropped.
 */
@SpringBootApplication
public class GatewayServiceApplication {

    /** The edge's public paths: the API, sign-in and sign-out, and the API documentation. */
    static final String[] EDGE_PATHS = {"/api/**", "/oauth2/**", "/login/**", "/logout/**", "/v3/api-docs/**",
        "/swagger-ui/**", "/swagger-ui.html"};

    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }

    @Bean
    RouteLocator uddpRoutes(RouteLocatorBuilder routes, @Value("${uddp.gateway.edge-uri}") String edge) {
        return routes.routes()
                .route("edge", r -> r.path(EDGE_PATHS)
                        .filters(f -> f.removeRequestHeader("X-UDDP-Actor")
                                .removeRequestHeader("X-UDDP-Service-Token").preserveHostHeader())
                        .uri(edge))
                .build();
    }

    /** The UI's origins (UDDP_UI_ORIGINS, comma separated) may call the API with credentials. */
    @Bean
    CorsWebFilter corsFilter(@Value("${uddp.gateway.ui-origins}") String origins) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return new CorsWebFilter(source);
    }
}
