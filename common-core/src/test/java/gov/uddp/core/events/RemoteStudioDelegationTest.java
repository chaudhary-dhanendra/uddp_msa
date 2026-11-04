package gov.uddp.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import gov.uddp.core.api.v1.MatchingStudioController;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * With uddp.identity.url the edge's Matching Studio endpoints run nothing locally: the call goes to identity-service
 * with the same path, the JSON body and the user's name, and the answer comes back as the endpoint's return type.
 */
class RemoteStudioDelegationTest {

    private HttpServer identity;

    @AfterEach
    void stop() {
        if (identity != null) {
            identity.stop(0);
        }
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void studioCallsAreForwardedWithTheActorAndAnswersAreTyped() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identity.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("X-UDDP-Actor") + " " + body);
            boolean missing = exchange.getRequestURI().getPath().endsWith("/missing");
            byte[] answer = (missing ? "{\"message\":\"Matching profile does not exist\"}"
                    : "[{\"profileId\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"p\",\"status\":\"DRAFT\"}]")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(missing ? 404 : 200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        identity.start();

        var factory = new AspectJProxyFactory(new MatchingStudioController(null, null, null, null, null));
        factory.setProxyTargetClass(true);
        factory.addAspect(new RemoteStudioDelegation("http://127.0.0.1:" + identity.getAddress().getPort() + "/"));
        MatchingStudioController studio = factory.getProxy();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("steward-a", "n/a", List.of()));

        call("GET", "/api/v1/matching/profiles", null);
        var profiles = studio.list();
        assertEquals("DRAFT", profiles.get(0).status(), "typed as the endpoint's return type");
        assertEquals("GET /api/v1/matching/profiles steward-a ", seen.get(0));

        call("PUT", "/api/v1/matching/profiles/missing", null);
        var request = new MatchingStudioController.MatchingProfileRequest("p", Map.of("blocking", List.of("x")));
        var error = assertThrows(ResponseStatusException.class, () -> studio.update(null, request));
        assertEquals(404, error.getStatusCode().value());
        assertEquals("Matching profile does not exist", error.getReason());
        assertEquals("PUT /api/v1/matching/profiles/missing steward-a {\"name\":\"p\",\"config\":{\"blocking\":[\"x\"]}}",
                seen.get(1));
    }

    private static void call(String method, String path, String query) {
        var request = new MockHttpServletRequest(method, path);
        request.setQueryString(query);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
