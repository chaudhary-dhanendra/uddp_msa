package gov.uddp.core.security;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * What the current request's handler knows about the audited resource: its ID, its state and extra (redacted)
 * metadata. Read by {@link AuditInterceptor} when the request completes; a no-op outside a request.
 */
public final class AuditContext {
    private static final String RESOURCE = AuditContext.class.getName() + ".resource";
    private static final String STATE = AuditContext.class.getName() + ".state";
    private static final String DETAILS = AuditContext.class.getName() + ".details";

    private AuditContext() {
    }

    public static void resource(Object resourceId) {
        set(RESOURCE, resourceId == null ? null : resourceId.toString());
    }

    public static void state(String stateCode) {
        set(STATE, stateCode);
    }

    public static void detail(String key, Object value) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return;
        }
        details(attributes).put(key, value);
    }

    static String resource(RequestAttributes attributes) {
        return (String) attributes.getAttribute(RESOURCE, RequestAttributes.SCOPE_REQUEST);
    }

    static String state(RequestAttributes attributes) {
        return (String) attributes.getAttribute(STATE, RequestAttributes.SCOPE_REQUEST);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> details(RequestAttributes attributes) {
        Object existing = attributes.getAttribute(DETAILS, RequestAttributes.SCOPE_REQUEST);
        if (existing instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        attributes.setAttribute(DETAILS, details, RequestAttributes.SCOPE_REQUEST);
        return details;
    }

    private static void set(String name, String value) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(name, value, RequestAttributes.SCOPE_REQUEST);
        }
    }
}
