package gov.uddp.messaging;

import gov.uddp.contracts.ServiceUrls;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The platform's service-to-service token (UDDP_INTERNAL_TOKEN, property {@code uddp.internal.token}). Every UDDP
 * process sends it on its calls to other services ({@link ServiceUrls#request}); a service that sets
 * {@code uddp.internal.require-token=true} rejects any call to its {@code /api/*} without it (401), while health and
 * metrics stay open for probes and Prometheus. Without a token nothing is sent or checked (single-machine development,
 * where the services listen on 127.0.0.1 only). The edge does not require it: its API is the users', behind sign-in.
 */
@AutoConfiguration
public class ServiceTokenAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenAutoConfiguration.class);

    public ServiceTokenAutoConfiguration(@Value("${uddp.internal.token:}") String token) {
        ServiceUrls.useToken(token);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(Filter.class)
    @ConditionalOnProperty(name = "uddp.internal.require-token", havingValue = "true")
    static class Enforcement {

        @Bean
        FilterRegistrationBean<Filter> serviceTokenFilter(@Value("${uddp.internal.token:}") String token) {
            byte[] expected = token.getBytes(StandardCharsets.UTF_8);
            if (token.isBlank()) {
                log.warn("uddp.internal.require-token is set but UDDP_INTERNAL_TOKEN is not: internal APIs are not"
                        + " protected by a service token (acceptable only when they listen on 127.0.0.1)");
            }
            Filter filter = (request, response, chain) -> {
                if (token.isBlank()) {
                    chain.doFilter(request, response);
                    return;
                }
                String given = ((HttpServletRequest) request).getHeader(ServiceUrls.TOKEN_HEADER);
                if (given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected)) {
                    chain.doFilter(request, response);
                    return;
                }
                var http = (HttpServletResponse) response;
                http.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                http.setContentType("application/json");
                http.getWriter().write("{\"message\":\"A valid service token is required\"}");
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns("/api/*");
            registration.setName("uddpServiceToken");
            registration.setOrder(Integer.MIN_VALUE + 10);
            return registration;
        }
    }
}
