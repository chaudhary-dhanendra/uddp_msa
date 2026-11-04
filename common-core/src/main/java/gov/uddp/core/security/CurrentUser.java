package gov.uddp.core.security;

import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The user of the current request. Every HTTP request passes the security filter chain and carries an
 * authentication (anonymous when nobody is signed in), so "no authentication at all" means platform code running
 * outside a request (startup, a background onboarding run, a scheduled job): that code acts for the platform and
 * sees every state (ADR-0019).
 */
public final class CurrentUser {
    private CurrentUser() {
    }

    public static Optional<UddpPrincipal> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UddpPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    /** The username for audit and decision records, or null outside a signed-in request. */
    public static String username() {
        return principal().map(UddpPrincipal::username).orElse(null);
    }

    /** The source codes the current request may see (from its departments); platform code sees every source. */
    public static StateScope scope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return StateScope.everyState();
        }
        if (authentication instanceof AnonymousAuthenticationToken) {
            return StateScope.none();
        }
        if (authentication.getPrincipal() instanceof UddpPrincipal principal) {
            return principal.mustChangePassword() ? StateScope.none() : principal.scope();
        }
        return StateScope.none();
    }

    public static boolean has(Permission permission) {
        return principal().map(principal -> principal.has(permission)).orElse(false);
    }
}
