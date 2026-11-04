package gov.uddp.core.api.v1;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the Vite dev server, only in the {@code dev} profile. The dev server normally proxies {@code /api}, so this
 * matters only when the UI calls the backend directly. No other profile allows cross-origin requests.
 */
@Configuration
@Profile("dev")
public class DevCorsConfiguration implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "POST");
    }
}
