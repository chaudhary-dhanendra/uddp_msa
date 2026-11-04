package gov.uddp.core.security;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reloads the signed-in user's row on every request, so a disabled or deleted account loses its session at once and
 * role, permission and department-scope changes apply to the next request (ADR-0017). The record-level scope is
 * resolved again from the departments each time, so a Source Pack moving between departments applies at once.
 * Principals without a user ID exist only in tests: only their scope is resolved.
 */
public class PrincipalRefreshFilter extends OncePerRequestFilter {
    private final UserAccountStore users;
    private final PostgresConnectionFactory connections;
    private final SecurityContextRepository contexts;
    private final DepartmentScopeResolver departments;

    public PrincipalRefreshFilter(UserAccountStore users, PostgresConnectionFactory connections,
                                  SecurityContextRepository contexts, DepartmentScopeResolver departments) {
        this.users = users;
        this.departments = departments;
        this.connections = connections;
        this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof UddpAuthentication current
                && current.getPrincipal().userId() != null
                && connections.isConfigured()) {
            Optional<UserAccount> account = users.findById(current.getPrincipal().userId());
            if (account.isEmpty() || !account.get().enabled()) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            } else {
                UddpPrincipal refreshed = account.get().principal(departments);
                if (!refreshed.equals(current.getPrincipal())) {
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new UddpAuthentication(refreshed));
                    SecurityContextHolder.setContext(context);
                    contexts.saveContext(context, request, response);
                }
            }
        } else if (SecurityContextHolder.getContext().getAuthentication() instanceof UddpAuthentication current
                && current.getPrincipal().userId() == null
                && connections.isConfigured()) {
            UddpPrincipal resolved = departments.resolve(current.getPrincipal());
            if (resolved != current.getPrincipal()) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UddpAuthentication(resolved));
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }
}
