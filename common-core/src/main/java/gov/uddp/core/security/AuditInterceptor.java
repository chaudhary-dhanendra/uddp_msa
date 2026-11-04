package gov.uddp.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records one audit event for every completed call of an {@link Audited} handler, whatever its outcome (ADR-0020).
 * Denied calls (401/403 from method security) are recorded as DENIED, so attempts are visible too.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {
    private final AuditService audit;

    public AuditInterceptor(AuditService audit) {
        this.audit = audit;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception exception) {
        if (!(handler instanceof HandlerMethod method)) {
            return;
        }
        Audited audited = method.getMethodAnnotation(Audited.class);
        if (audited == null || audited.recordedByHandler()) {
            return;
        }
        RequestAttributes attributes = new ServletRequestAttributes(request);
        String resourceId = AuditContext.resource(attributes);
        if (resourceId == null && !audited.idVariable().isEmpty()) {
            Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            if (variables instanceof Map<?, ?> map && map.get(audited.idVariable()) != null) {
                resourceId = map.get(audited.idVariable()).toString();
            }
        }
        int status = response.getStatus();
        String result = status < 400 && exception == null ? AuditService.SUCCESS
                : status == 401 || status == 403 ? AuditService.DENIED : AuditService.FAILURE;
        Map<String, Object> details = new LinkedHashMap<>(AuditContext.details(attributes));
        details.put("method", request.getMethod());
        details.put("path", request.getRequestURI());
        details.put("status", status);
        audit.record(new AuditService.AuditEvent(null, audited.action(), audited.resource(), resourceId,
                AuditContext.state(attributes), result, details));
    }
}
