package gov.uddp.core.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * An authenticated session's {@link UddpPrincipal}. Authorities are {@code PERM_<permission>} for each effective
 * permission and {@code ROLE_<role>} for each role; a user who must change the password holds only
 * {@value #PASSWORD_CHANGE_REQUIRED}, so every permission check fails until the password is changed.
 */
public final class UddpAuthentication extends AbstractAuthenticationToken {
    public static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

    private final UddpPrincipal principal;

    public UddpAuthentication(UddpPrincipal principal) {
        super(authorities(principal));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public UddpPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.username();
    }

    private static List<GrantedAuthority> authorities(UddpPrincipal principal) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (principal.mustChangePassword()) {
            authorities.add(new SimpleGrantedAuthority(PASSWORD_CHANGE_REQUIRED));
            return authorities;
        }
        for (Permission permission : principal.effectivePermissions()) {
            authorities.add(new SimpleGrantedAuthority(permission.authority()));
        }
        for (String role : principal.roles()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return authorities;
    }
}
