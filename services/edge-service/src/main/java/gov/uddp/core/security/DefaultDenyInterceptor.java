package gov.uddp.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Default deny (ADR-0018): a platform API handler without a {@link PreAuthorize} rule is refused, so a new endpoint
 * is closed until someone decides who may call it. Framework handlers (OpenAPI document, error page) are outside the
 * platform package and unaffected; {@code EndpointAuthorizationTest} also fails the build for such an endpoint.
 */
@Component
public class DefaultDenyInterceptor implements HandlerInterceptor {
    static final String PLATFORM_PACKAGE = "gov.uddp.core";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && !hasRule(method)) {
            throw new AccessDeniedException("No authorization rule is declared for this endpoint");
        }
        return true;
    }

    /** True for handlers outside the platform package, or with a method- or class-level {@link PreAuthorize}. */
    public static boolean hasRule(HandlerMethod method) {
        if (!method.getBeanType().getName().startsWith(PLATFORM_PACKAGE)) {
            return true;
        }
        return AnnotatedElementUtils.hasAnnotation(method.getMethod(), PreAuthorize.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), PreAuthorize.class);
    }
}
