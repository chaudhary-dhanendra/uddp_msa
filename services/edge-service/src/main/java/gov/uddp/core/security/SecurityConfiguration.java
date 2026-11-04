package gov.uddp.core.security;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.security.oidc.OidcLoginHandlers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP security (ADR-0017, ADR-0018): server-side sessions, a CSRF token in the {@code XSRF-TOKEN} cookie that the UI
 * echoes in the {@code X-XSRF-TOKEN} header on every write, JSON 401/403 answers, and every {@code /api/**} request
 * authenticated. Which permission each endpoint needs is declared on the endpoint ({@code @PreAuthorize}); endpoints
 * without a rule are denied ({@link DefaultDenyInterceptor}). Optional OIDC single sign-on when configured.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {
    /** Endpoints anyone may call; each still declares {@code @PreAuthorize("permitAll()")} or its own rule. */
    static final String[] PUBLIC = {
        "/health", "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
        "/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/auth/providers", "/api/v1/auth/me",
        "/api/setup/**", "/oauth2/authorization/**", "/login/oauth2/code/**", "/error",
        // Health and metrics for the platform's monitoring; the gateway never routes /actuator.
        "/actuator/health", "/actuator/health/**", "/actuator/prometheus"
    };

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, SecurityContextRepository contexts, UserAccountStore users,
                                 DepartmentScopeResolver departments,
                                 PostgresConnectionFactory connections, JsonSecurityResponses responses,
                                 SecurityProperties properties,
                                 ObjectProvider<ClientRegistrationRepository> registrations,
                                 ObjectProvider<OidcLoginHandlers> oidcHandlers) throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookiePath("/");
        http
                .securityContext(context -> context.securityContextRepository(contexts))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        // The SPA reads the cookie and sends the same value back (no per-request masking).
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), AnonymousAuthenticationFilter.class)
                .addFilterAfter(new PrincipalRefreshFilter(users, connections, contexts, departments), AnonymousAuthenticationFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(responses::unauthenticated)
                        .accessDeniedHandler(responses::denied))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp ->
                            csp.policyDirectives(
                                "default-src 'self'; " +
                                "script-src 'self' 'unsafe-inline'; " +
                                "style-src 'self' 'unsafe-inline'; " +
                                "img-src 'self' data:; " +
                                "font-src 'self' data:; " +
                                "connect-src 'self'; " +
                                "frame-ancestors 'none'"
                            ))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(PUBLIC).permitAll()
                        // Signing out and changing the password work while a password change is required.
                        .requestMatchers("/api/v1/auth/**").authenticated()
                        // Held records: the permission is checked here, before any request body is read (403 before 400).
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/held-records",
                                "/api/v1/held-records/**").hasAuthority("PERM_STEWARD_HELD_VIEW")
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/held-records/*/reopen")
                        .access(new org.springframework.security.web.access.expression.WebExpressionAuthorizationManager(
                                "hasAuthority('PERM_STEWARD_HELD_DECIDE') and hasAuthority('PERM_PLATFORM_SETTINGS')"))
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/held-records/**")
                        .hasAuthority("PERM_STEWARD_HELD_DECIDE")
                        // Identifier types validate in the record constructor, so the body would fail before
                        // @PreAuthorize; the permission is checked here instead (403 before 400).
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/identity/identifier-type-changes")
                        .hasAuthority("PERM_IDENTITY_MANAGE")
                        .requestMatchers("/api/**").access(SecurityConfiguration::signedInWithUsablePassword)
                        .anyRequest().denyAll());
        if (properties.getOidc().isEnabled() && registrations.getIfAvailable() != null) {
            OidcLoginHandlers handlers = oidcHandlers.getObject();
            http.oauth2Login(login -> login
                    .clientRegistrationRepository(registrations.getObject())
                    .successHandler(handlers::success)
                    .failureHandler(handlers::failure));
        }
        return http.build();
    }

    /** Authenticated, and not waiting for a required password change. */
    static AuthorizationDecision signedInWithUsablePassword(Supplier<Authentication> authentication,
                                                            RequestAuthorizationContext request) {
        Authentication current = authentication.get();
        boolean allowed = current != null
                && current.isAuthenticated()
                && !(current instanceof AnonymousAuthenticationToken)
                && current.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals(UddpAuthentication.PASSWORD_CHANGE_REQUIRED));
        return new AuthorizationDecision(allowed);
    }

    /** Makes sure every response carries the CSRF cookie, so the UI can send the token on its first write. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
