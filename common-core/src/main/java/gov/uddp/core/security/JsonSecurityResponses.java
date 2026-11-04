package gov.uddp.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.api.v1.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

/**
 * The API's answers when security stops a request, in the {@link ApiError} shape: 401 {@code UNAUTHENTICATED} (no or
 * expired session; the UI then shows the sign-in screen), 403 {@code FORBIDDEN}, {@code CSRF_TOKEN_INVALID} or
 * {@code PASSWORD_CHANGE_REQUIRED}.
 */
@Component
public class JsonSecurityResponses {
    private final ObjectMapper json;

    public JsonSecurityResponses(ObjectMapper json) {
        this.json = json;
    }

    public void unauthenticated(HttpServletRequest request, HttpServletResponse response,
                                AuthenticationException exception) throws IOException {
        write(response, unauthenticated(request));
    }

    public void denied(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        write(response, denied(request, exception));
    }

    public static ApiError unauthenticated(HttpServletRequest request) {
        return new ApiError(401, "UNAUTHENTICATED", "Sign in to continue; the session has expired or does not exist",
                request.getRequestURI());
    }

    public static ApiError denied(HttpServletRequest request, AccessDeniedException exception) {
        if (exception instanceof CsrfException) {
            return new ApiError(403, "CSRF_TOKEN_INVALID", "The request's CSRF token is missing or invalid; reload"
                    + " the page and try again", request.getRequestURI());
        }
        if (CurrentUser.principal().map(UddpPrincipal::mustChangePassword).orElse(false)) {
            return new ApiError(403, "PASSWORD_CHANGE_REQUIRED", "Change your password before continuing",
                    request.getRequestURI());
        }
        return new ApiError(403, "FORBIDDEN", "You do not have permission for this action", request.getRequestURI());
    }

    private void write(HttpServletResponse response, ApiError error) throws IOException {
        response.setStatus(error.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), error);
    }
}
