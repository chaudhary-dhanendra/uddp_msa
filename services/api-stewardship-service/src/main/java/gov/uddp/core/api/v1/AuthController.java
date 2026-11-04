package gov.uddp.core.api.v1;

import gov.uddp.core.security.AuditService;
import gov.uddp.core.security.Audited;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.security.LocalAuthenticationService;
import gov.uddp.core.security.Permission;
import gov.uddp.core.security.SecurityProperties;
import gov.uddp.core.security.SessionSignIn;
import gov.uddp.core.security.UddpPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in, sign-out, "who am I" and password change (ADR-0017). The session is a server-side session behind an
 * HttpOnly cookie; every write needs the CSRF token from the {@code XSRF-TOKEN} cookie in {@code X-XSRF-TOKEN}.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {
    private final LocalAuthenticationService authentication;
    private final SessionSignIn sessions;
    private final SecurityProperties properties;
    private final AuditService audit;

    public AuthController(LocalAuthenticationService authentication, SessionSignIn sessions,
                          SecurityProperties properties, AuditService audit) {
        this.authentication = authentication;
        this.sessions = sessions;
        this.properties = properties;
        this.audit = audit;
    }

    public record LoginRequest(String username, String password) {
    }

    public record PasswordChangeRequest(String currentPassword, String newPassword) {
    }

    /**
     * The signed-in user.
     *
     * @param departmentScope        {@code ["ALL"]} or the department codes the user may see
     * @param sessionTimeoutSeconds  idle time after which the session expires
     */
    public record Me(String username, String displayName, List<String> roles, List<Permission> permissions,
                     List<String> departmentScope, boolean mustChangePassword, String authSource,
                     int sessionTimeoutSeconds) {
    }

    public record CsrfTokenView(String headerName, String token) {
    }

    /** How users may sign in: always locally, and through single sign-on when it is configured. */
    public record Providers(boolean local, boolean oidc, String oidcDisplayName, String oidcLoginUrl) {
    }

    @Operation(operationId = "getCsrfToken", summary = "The CSRF token (also set as the XSRF-TOKEN cookie)")
    @GetMapping("/csrf")
    @PreAuthorize("permitAll()")
    public CsrfTokenView csrf(CsrfToken token) {
        return new CsrfTokenView(token.getHeaderName(), token.getToken());
    }

    @Operation(operationId = "getAuthProviders", summary = "Sign-in methods")
    @GetMapping("/providers")
    @PreAuthorize("permitAll()")
    public Providers providers() {
        boolean oidc = properties.getOidc().isEnabled();
        return new Providers(true, oidc, oidc ? properties.getOidc().getDisplayName() : null,
                oidc ? "/oauth2/authorization/uddp" : null);
    }

    @Operation(operationId = "login", summary = "Sign in with username and password; starts a new session")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "401", description = "Wrong credentials, or the account is locked or disabled",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    @Audited(action = "LOGIN", resource = "SESSION", recordedByHandler = true)
    public Me login(@RequestBody LoginRequest login, HttpServletRequest request, HttpServletResponse response) {
        UddpPrincipal principal = authentication.authenticate(login.username(), login.password());
        sessions.signIn(request, response, principal);
        return me(principal, request);
    }

    @Operation(operationId = "logout",
            summary = "Sign out: ends the session and clears its cookies; harmless (204) when already signed out")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("permitAll()")
    @Audited(action = "LOGOUT", resource = "SESSION", recordedByHandler = true)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        CurrentUser.principal().ifPresent(principal -> audit.record(AuditService.AuditEvent.of("LOGOUT", "SESSION",
                principal.userId() == null ? null : principal.userId().toString(), null, AuditService.SUCCESS)));
        sessions.signOut(request, response);
    }

    @Operation(operationId = "getCurrentUser", summary = "The signed-in user; 401 when there is no session")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "401", description = "No session",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/me")
    @PreAuthorize("permitAll()")
    public Me current(HttpServletRequest request) {
        UddpPrincipal principal = CurrentUser.principal()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No session"));
        return me(principal, request);
    }

    @Operation(operationId = "changePassword", summary = "Change your own password (required after a reset)")
    @ApiResponse(responseCode = "200", description = "Changed")
    @ApiResponse(responseCode = "400", description = "Wrong current password or the new one breaks the policy",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    @Audited(action = "PASSWORD_CHANGE", resource = "USER", recordedByHandler = true)
    public Me changePassword(@RequestBody PasswordChangeRequest change, HttpServletRequest request,
                             HttpServletResponse response) {
        UddpPrincipal principal = CurrentUser.principal()
                .orElseThrow(() -> new IllegalArgumentException("Sign in first"));
        UddpPrincipal updated = authentication.changePassword(principal, change.currentPassword(), change.newPassword());
        sessions.replace(request, response, updated);
        return me(updated, request);
    }

    static Me me(UddpPrincipal principal, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return new Me(principal.username(), principal.displayName(), List.copyOf(principal.roles()),
                principal.effectivePermissions().stream().sorted().toList(),
                principal.departmentScope().values(), principal.mustChangePassword(), principal.authSource(),
                session == null ? 0 : session.getMaxInactiveInterval());
    }
}
