package gov.uddp.core.monitoring;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Posts the signed alert to the configured URL; redirects are not followed so the signature stays with that host. */
@Component
public class HttpWebhookSender implements AlertService.WebhookSender {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public void send(String url, String body, Map<String, String> headers) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        try {
            HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("The webhook answered HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling the webhook", e);
        }
    }
}
