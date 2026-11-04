package gov.uddp.core.api.v1;

import gov.uddp.core.security.JsonSecurityResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Security failures raised inside a handler (method security, default deny, failed sign-in) become 401 or 403
 * {@link ApiError} bodies before any other advice can turn them into a 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> unauthenticated(AuthenticationException e, HttpServletRequest request) {
        ApiError error = new ApiError(401, "UNAUTHENTICATED", e.getMessage(), request.getRequestURI());
        return ResponseEntity.status(401).body(error);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(AccessDeniedException e, HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(401).body(JsonSecurityResponses.unauthenticated(request));
        }
        return ResponseEntity.status(403).body(JsonSecurityResponses.denied(request, e));
    }
}
