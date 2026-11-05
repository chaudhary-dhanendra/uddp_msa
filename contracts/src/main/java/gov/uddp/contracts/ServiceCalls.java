package gov.uddp.contracts;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resilience for the services' HTTP calls to each other (most traffic is Kafka, which retries by itself).
 *
 * <ul>
 *   <li><b>Retries</b>: a call that may be repeated ({@code retryable}: reads, and writes the target makes idempotent)
 *       is retried up to {@link #ATTEMPTS} times with growing backoff when the connection fails or the target
 *       answers 502, 503 or 504. Any other call is retried only when the connection was refused, so it never ran.</li>
 *   <li><b>Circuit breaker</b> per target (host and port): after {@link #FAILURE_THRESHOLD} failures in a row it opens
 *       and calls fail at once for {@link #OPEN_MILLIS}; then one trial call is let through (half open), and a success
 *       closes it again. Callers see {@link Unavailable}, an {@link IllegalStateException}.</li>
 * </ul>
 * A 4xx answer is the target's decision, never a failure of the target.
 */
public final class ServiceCalls {

    static final int ATTEMPTS = 3;
    static final long BACKOFF_MILLIS = 200;
    static final int FAILURE_THRESHOLD = 5;
    static final long OPEN_MILLIS = 30_000;

    /** The target is unreachable or its circuit is open. */
    public static final class Unavailable extends IllegalStateException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Breaker {
        int failures;
        long openUntil;
        boolean trial;
    }

    private static final Map<String, Breaker> BREAKERS = new ConcurrentHashMap<>();

    private ServiceCalls() {
    }

    /** Sends the request with retries and the target's circuit breaker; the body is read as text. */
    public static HttpResponse<String> send(HttpClient client, HttpRequest request, boolean retryable)
            throws IOException, InterruptedException {
        String target = request.uri().getHost() + ":" + request.uri().getPort();
        Breaker breaker = BREAKERS.computeIfAbsent(target, t -> new Breaker());
        admit(target, breaker);
        IOException failure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                boolean unavailable = status == 502 || status == 503 || status == 504;
                if (!unavailable) {
                    succeeded(breaker);
                    return response;
                }
                if (!retryable || attempt == ATTEMPTS) {
                    failed(breaker);
                    return response;
                }
            } catch (IOException e) {
                failure = e;
                boolean neverSent = e instanceof ConnectException
                        || (e instanceof HttpTimeoutException && e.getMessage() != null
                        && e.getMessage().contains("connect"));
                if ((!retryable && !neverSent) || attempt == ATTEMPTS) {
                    failed(breaker);
                    throw new Unavailable("Cannot reach " + target + ": " + e.getMessage(), e);
                }
            }
            Thread.sleep(BACKOFF_MILLIS * attempt);
        }
        failed(breaker);
        throw new Unavailable("Cannot reach " + target, failure);
    }

    private static void admit(String target, Breaker breaker) {
        synchronized (breaker) {
            long now = System.currentTimeMillis();
            if (breaker.openUntil == 0) {
                return;
            }
            if (now < breaker.openUntil) {
                throw new Unavailable("Circuit open for " + target + " after " + breaker.failures
                        + " failures; retry later", null);
            }
            if (breaker.trial) {
                throw new Unavailable("Circuit half open for " + target + "; a trial call is in progress", null);
            }
            breaker.trial = true;
        }
    }

    private static void succeeded(Breaker breaker) {
        synchronized (breaker) {
            breaker.failures = 0;
            breaker.openUntil = 0;
            breaker.trial = false;
        }
    }

    private static void failed(Breaker breaker) {
        synchronized (breaker) {
            breaker.failures++;
            breaker.trial = false;
            if (breaker.failures >= FAILURE_THRESHOLD) {
                breaker.openUntil = System.currentTimeMillis() + OPEN_MILLIS;
            }
        }
    }

    /** Forgets every breaker (tests). */
    static void reset() {
        BREAKERS.clear();
    }
}
