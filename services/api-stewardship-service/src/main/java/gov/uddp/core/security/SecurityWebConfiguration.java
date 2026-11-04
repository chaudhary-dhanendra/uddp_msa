package gov.uddp.core.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers default deny and the audit trail for every handler. */
@Configuration
public class SecurityWebConfiguration implements WebMvcConfigurer {
    private final DefaultDenyInterceptor defaultDeny;
    private final AuditInterceptor audit;

    public SecurityWebConfiguration(DefaultDenyInterceptor defaultDeny, AuditInterceptor audit) {
        this.defaultDeny = defaultDeny;
        this.audit = audit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(audit);
        registry.addInterceptor(defaultDeny);
    }
}
