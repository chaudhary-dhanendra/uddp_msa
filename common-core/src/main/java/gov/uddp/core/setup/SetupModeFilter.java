package gov.uddp.core.setup;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Setup mode: until a platform database is configured, every {@code /api/**} request except {@code /api/setup/**}
 * answers 503 {@code SETUP_REQUIRED}. {@code /health}, the OpenAPI document and the UI's static files still work.
 */
@Component
public class SetupModeFilter extends OncePerRequestFilter {
    private final PostgresConnectionFactory connections;

    public SetupModeFilter(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.startsWith("/api/setup/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (connections.isConfigured()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":503,\"error\":\"SETUP_REQUIRED\",\"message\":"
                + "\"The platform database is not configured; complete the Setup screen first\",\"path\":\""
                + request.getRequestURI().replace("\"", "") + "\"}");
    }
}
