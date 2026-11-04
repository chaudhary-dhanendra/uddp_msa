package gov.uddp.core.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {
    @Bean
    AiDraftService aiDraftService(AiProperties properties, ObjectMapper json) {
        return properties.configured() ? new HttpAiDraftService(properties, json) : new DisabledAiDraftService();
    }
}
