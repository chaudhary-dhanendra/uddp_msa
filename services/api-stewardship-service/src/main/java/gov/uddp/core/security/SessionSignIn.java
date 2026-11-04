package gov.uddp.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Starts and ends server-side sessions. Signing in always starts a new session (no session fixation); the session
 * cookie is HttpOnly, SameSite=Lax and Secure over HTTPS ({@code server.servlet.session.cookie.*}).
 */
@Component
public class SessionSignIn {
    /** The cookie {@code CookieCsrfTokenRepository} writes the CSRF token to. */
    static final String CSRF_COOKIE = "XSRF-TOKEN";

    private final SecurityContextRepository contexts;
    private final String sessionCookieName;

    public SessionSignIn(SecurityContextRepository contexts,
                         @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName) {
        this.contexts = contexts;
        this.sessionCookieName = sessionCookieName;
    }

    public void signIn(HttpServletRequest request, HttpServletResponse response, UddpPrincipal principal) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        request.getSession(true);
        replace(request, response, principal);
    }

    /** Puts an updated principal into the existing session (after a password change). */
    public void replace(HttpServletRequest request, HttpServletResponse response, UddpPrincipal principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UddpAuthentication(principal));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    public void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    /** Signs out and tells the browser to drop the session and CSRF cookies. */
    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        signOut(request);
        expire(response, sessionCookieName, true);
        expire(response, CSRF_COOKIE, false);
    }

    private static void expire(HttpServletResponse response, String name, boolean httpOnly) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, "")
                .path("/")
                .maxAge(0)
                .httpOnly(httpOnly)
                .sameSite("Lax")
                .build()
                .toString());
    }
}
