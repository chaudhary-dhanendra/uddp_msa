package gov.uddp.core.statepack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactIntegrityTest {
    @Test void existingJsonYamlAndSampleCsvArtifactsAreReadable() throws Exception {
        ObjectMapper json = new ObjectMapper(); ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try (Stream<Path> files = Files.list(Path.of("../../common-model"))) { for (Path file : files.toList()) assertNotNull(json.readTree(file.toFile()), file.toString()); }
        try (Stream<Path> files = Files.walk(Path.of("../../services/statepack-service/seed/state-packs"))) { for (Path file : files.filter(p -> p.toString().endsWith(".yaml")).toList()) assertNotNull(yaml.readTree(file.toFile()), file.toString()); }
        try (Stream<Path> files = Files.walk(Path.of("../../services/ingestion-service/seed/sample-data"))) { for (Path file : files.filter(p -> p.toString().endsWith(".csv")).toList()) { var lines=Files.readAllLines(file); assertTrue(lines.size() > 1, file.toString()); assertFalse(lines.getFirst().isBlank(), file.toString()); } }
    }
}
