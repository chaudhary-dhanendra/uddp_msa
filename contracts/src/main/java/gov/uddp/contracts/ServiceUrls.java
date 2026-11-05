package gov.uddp.contracts;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Service addresses resolved at call time (MSA). A configured address {@code lb://<service-name>} is looked up in the
 * service registry (Eureka, through the application's discovery client) on every call, round robin over the
 * registered instances; any other address ({@code http://host:port}) is used as it is. The application registers its
 * discovery lookup once at startup; without one, {@code lb://} addresses fail with a clear message.
 */
public final class ServiceUrls {

    private static final String SCHEME = "lb://";
    private static volatile Function<String, List<URI>> registry;
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final long POLL_MILLIS = 250;
    /** How long a call waits for the first instance of a service to appear in the registry. */
    private static volatile long waitMillis = 10_000;

    /** The header that carries the platform's service-to-service token (UDDP_INTERNAL_TOKEN). */
    public static final String TOKEN_HEADER = "X-UDDP-Service-Token";
    private static volatile String token;

    private ServiceUrls() {
    }

    /** The application's discovery lookup: service name to the base URIs of its registered instances. */
    public static void useRegistry(Function<String, List<URI>> lookup) {
        registry = lookup;
    }

    /** The address to call now, without a trailing slash. */
    public static String resolve(String configured) {
        String address = configured.strip().replaceAll("/+$", "");
        if (!address.startsWith(SCHEME)) {
            return address;
        }
        String rest = address.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash);
        Function<String, List<URI>> lookup = registry;
        if (lookup == null) {
            throw new IllegalStateException("No service registry to resolve " + configured
                    + "; enable discovery or configure an http:// address");
        }
        List<URI> instances = lookup.apply(name);
        // Just after startup the local registry copy can still be empty; give discovery a moment before giving up.
        long deadline = System.currentTimeMillis() + waitMillis;
        while ((instances == null || instances.isEmpty()) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            instances = lookup.apply(name);
        }
        if (instances == null || instances.isEmpty()) {
            throw new ServiceCalls.Unavailable("No instance of " + name + " is registered", null);
        }
        URI instance = instances.get(Math.floorMod(NEXT.getAndIncrement(), instances.size()));
        return instance.toString().replaceAll("/+$", "") + path;
    }

    /** How long {@link #resolve} waits for a service that has no registered instance yet (0: fail at once). */
    public static void waitForInstances(long millis) {
        waitMillis = Math.max(0, millis);
    }

    /** The service-to-service token this process sends (null: none configured). */
    public static void useToken(String serviceToken) {
        token = serviceToken == null || serviceToken.isBlank() ? null : serviceToken;
    }

    /** A request to another UDDP service, carrying the service token when one is configured. */
    public static HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        String current = token;
        return current == null ? builder : builder.header(TOKEN_HEADER, current);
    }
}
