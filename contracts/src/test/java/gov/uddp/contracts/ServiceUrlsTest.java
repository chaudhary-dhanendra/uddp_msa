package gov.uddp.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** lb:// addresses resolve through the registry at call time, round robin; other addresses are used as they are. */
class ServiceUrlsTest {

    @AfterEach
    void forget() {
        ServiceUrls.useRegistry(null);
        ServiceUrls.waitForInstances(10_000);
    }

    @Test
    void namesResolveThroughTheRegistryAndAddressesPassThrough() {
        assertEquals("http://10.0.0.1:8101", ServiceUrls.resolve("http://10.0.0.1:8101/"));
        assertThrows(IllegalStateException.class, () -> ServiceUrls.resolve("lb://statepack-service"));

        Map<String, List<URI>> registry = Map.of("statepack-service",
                List.of(URI.create("http://10.0.0.5:8105"), URI.create("http://10.0.0.6:8105/")));
        ServiceUrls.useRegistry(name -> registry.getOrDefault(name, List.of()));
        String first = ServiceUrls.resolve("lb://statepack-service");
        String second = ServiceUrls.resolve("lb://statepack-service/");
        assertEquals(List.of("http://10.0.0.5:8105", "http://10.0.0.6:8105"), List.of(first, second).stream().sorted()
                .toList(), "both instances are used");
        assertEquals("http://10.0.0.5:8105/api/x", ServiceUrls.resolve("lb://statepack-service/api/x")
                .replace("10.0.0.6", "10.0.0.5"));
        ServiceUrls.waitForInstances(300);
        var missing = assertThrows(ServiceCalls.Unavailable.class, () -> ServiceUrls.resolve("lb://identity-service"));
        assertEquals("No instance of identity-service is registered", missing.getMessage());
    }

    @Test
    void aServiceThatRegistersShortlyAfterStartupIsWaitedFor() {
        long registersAt = System.currentTimeMillis() + 600;
        ServiceUrls.useRegistry(name -> System.currentTimeMillis() < registersAt
                ? List.of() : List.of(URI.create("http://10.0.0.7:8101")));
        assertEquals("http://10.0.0.7:8101/api/ingestion", ServiceUrls.resolve("lb://ingestion-service/api/ingestion"));
    }
}
