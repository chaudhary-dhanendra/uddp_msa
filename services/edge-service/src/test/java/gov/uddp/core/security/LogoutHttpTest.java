package gov.uddp.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.security.UserAccountStore.NewUser;
import gov.uddp.core.testsupport.TestDatabase;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Signing out over real HTTP, as the browser does through the dev proxy: session and XSRF-TOKEN cookies, the CSRF
 * header echoed. Logout answers 204, ends the session and clears both cookies; a second logout is harmless (204).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LogoutHttpTest {
    static final String STRONG = "Correct-Horse-7-Battery";

    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory logoutConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    UserAccountStore users;

    @Autowired
    PasswordEncoder encoder;

    @Test
    void loginThenLogoutIs204AndMeIs401AfterwardsAndASecondLogoutIsHarmless() throws Exception {
        users.create(new NewUser("logout.user", "Logout User", encoder.encode(STRONG), false, "LOCAL", null,
                List.of("PROGRAM_USER"), List.of("ALL")));
        CookieManager cookies = new CookieManager();
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

        assertEquals(200, send(client, cookies, "GET", "/api/v1/auth/csrf", null).statusCode());
        var login = send(client, cookies, "POST", "/api/v1/auth/login",
                "{\"username\":\"logout.user\",\"password\":\"" + STRONG + "\"}");
        assertEquals(200, login.statusCode(), login.body());
        assertEquals(200, send(client, cookies, "GET", "/api/v1/auth/me", null).statusCode());

        var logout = send(client, cookies, "POST", "/api/v1/auth/logout", null);
        assertEquals(204, logout.statusCode(), logout.body());
        List<String> cleared = logout.headers().allValues("Set-Cookie");
        assertTrue(cleared.stream().anyMatch(c -> c.startsWith("UDDP_SESSION=") && c.contains("Max-Age=0")),
                cleared.toString());
        assertTrue(cleared.stream().anyMatch(c -> c.startsWith("XSRF-TOKEN=") && c.contains("Max-Age=0")),
                cleared.toString());

        assertEquals(401, send(client, cookies, "GET", "/api/v1/auth/me", null).statusCode());
        var again = send(client, cookies, "POST", "/api/v1/auth/logout", null);
        assertEquals(204, again.statusCode(), again.body());
    }

    /** One request with the cookie jar; writes fetch a CSRF cookie first when there is none and echo it. */
    private HttpResponse<String> send(HttpClient client, CookieManager cookies, String method, String path,
                                      String body) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri);
        if (method.equals("GET")) {
            request.GET();
        } else {
            if (csrf(cookies) == null) {
                send(client, cookies, "GET", "/api/v1/auth/csrf", null);
            }
            String token = csrf(cookies);
            assertNotNull(token, "the CSRF cookie is set");
            request.header("X-XSRF-TOKEN", token)
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String csrf(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
