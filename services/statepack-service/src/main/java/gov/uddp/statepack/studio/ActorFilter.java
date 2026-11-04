package gov.uddp.statepack.studio;

import gov.uddp.core.security.DepartmentScope;
import gov.uddp.core.security.StateScope;
import gov.uddp.core.security.UddpPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The user the edge acts for (X-UDDP-Actor), as the Studio's current user: submitter and checker names, and the rule
 * that nobody decides their own submission, use it. Permissions and state scope were checked at the edge.
 */
@Component
public class ActorFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String actor = request.getHeader("X-UDDP-Actor");
        if (actor == null || actor.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        var principal = new UddpPrincipal(null, actor.strip(), actor.strip(), Set.of(), Set.of(),
                DepartmentScope.of(List.of("ALL")), StateScope.everyState(), false, "EDGE");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
