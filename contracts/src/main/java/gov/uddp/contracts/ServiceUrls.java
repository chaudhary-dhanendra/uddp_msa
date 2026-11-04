package gov.uddp.contracts;

import java.net.URI;
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
        if (instances == null || instances.isEmpty()) {
            throw new IllegalStateException("No instance of " + name + " is registered");
        }
        URI instance = instances.get(Math.floorMod(NEXT.getAndIncrement(), instances.size()));
        return instance.toString().replaceAll("/+$", "") + path;
    }
}
