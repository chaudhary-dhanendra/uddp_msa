package gov.uddp.core.api.v1;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document at /v3/api-docs (Swagger UI at /swagger-ui.html); the UI's TypeScript client is generated from it. */
@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI uddpOpenApi() {
        return new OpenAPI().info(new Info()
                .title("UDDP Common Core API")
                .version("m12")
                .description("Read APIs (/api/v1) for the operational UI, and the existing onboarding, canonical and"
                        + " Golden endpoints. Development build: there is no authentication until the security"
                        + " milestone."));
    }
}
