package gov.uddp.core.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API entry point whose every call is written to the audit trail by {@link AuditInterceptor}: all writes
 * and the sensitive reads (Resident 360, lineage to raw, exports). The result is SUCCESS, DENIED (401/403) or
 * FAILURE (any other error status). The resource ID is the path variable named by {@link #idVariable()} unless the
 * handler sets one through {@link AuditContext}; the state comes from {@link AuditContext}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audited {
    /** For example {@code STUDIO_PUBLISH_SUBMIT}. */
    String action();

    /** For example {@code STATE_PACK}. */
    String resource();

    /** The path variable that identifies the resource, if any. */
    String idVariable() default "";

    /**
     * True when the handler records its own, richer event (sign-in and sign-out name the attempted user even when
     * nobody is signed in); the interceptor then writes nothing.
     */
    boolean recordedByHandler() default false;
}
