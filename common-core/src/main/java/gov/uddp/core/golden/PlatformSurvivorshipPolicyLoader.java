package gov.uddp.core.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import gov.uddp.core.config.StatePackProperties;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class PlatformSurvivorshipPolicyLoader {
    private final Path file;
    public PlatformSurvivorshipPolicyLoader(StatePackProperties properties) {
        file = Path.of(properties.getPlatformDirectory(), "survivorship", "resident.yaml").toAbsolutePath().normalize();
    }
    public PlatformSurvivorshipPolicy load() {
        try {
            var value = new ObjectMapper(new YAMLFactory()).findAndRegisterModules().readValue(file.toFile(), PlatformSurvivorshipPolicy.class);
            if (!"Resident".equals(value.entity()) || value.version() == null || value.fields() == null) throw new IllegalStateException("Invalid platform Resident survivorship policy");
            return value;
        } catch (Exception e) { throw new IllegalStateException("Cannot load platform survivorship policy " + file, e); }
    }
}
