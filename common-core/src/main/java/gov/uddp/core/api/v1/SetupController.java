package gov.uddp.core.api.v1;

import gov.uddp.core.security.Audited;
import gov.uddp.core.security.AuthorizationService;
import gov.uddp.core.security.LocalAuthenticationService;
import gov.uddp.core.security.SessionSignIn;
import gov.uddp.core.security.UddpPrincipal;
import gov.uddp.core.security.UserAdminService;
import gov.uddp.core.setup.SetupModels;
import gov.uddp.core.setup.SetupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform database setup. These endpoints (and {@code /health}) are the only ones that work in setup mode, before
 * a database is configured; then anyone may use them, because there is nothing to protect and nobody to sign in.
 * Once the platform is configured they need {@code PLATFORM_SETTINGS}, except the public status and creating the
 * first administrator while no user exists (ADR-0017). No response contains a password.
 */
@RestController
@RequestMapping("/api/setup")
@Tag(name = "Setup")
public class SetupController {
    private final SetupService setup;
    private final AuthorizationService access;
    private final UserAdminService users;
    private final LocalAuthenticationService authentication;
    private final SessionSignIn sessions;

    public SetupController(SetupService setup, AuthorizationService access, UserAdminService users,
                           LocalAuthenticationService authentication, SessionSignIn sessions) {
        this.setup = setup;
        this.access = access;
        this.users = users;
        this.authentication = authentication;
        this.sessions = sessions;
    }

    /** The first administrator of a new platform. */
    public record AdministratorRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String username,
            String displayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String password) {
    }

    @GetMapping("/status")
    @PreAuthorize("permitAll()")
    @Operation(operationId = "getSetupStatus",
            summary = "Setup or normal mode, whether the first administrator is needed, and (for administrators) the masked settings")
    public SetupModels.Status status() {
        return access.setupAllowed() ? setup.status() : setup.publicStatus();
    }

    @PostMapping("/test-connection")
    @PreAuthorize("@access.setupAllowed()")
    @Audited(action = "SETUP_TEST_CONNECTION", resource = "PLATFORM_DATABASE")
    @Operation(operationId = "testDatabaseConnection", summary = "Test a PostgreSQL connection without saving it")
    public SetupModels.ConnectionTest testConnection(@RequestBody SetupModels.ConnectionRequest request) {
        return setup.testConnection(request);
    }

    @PostMapping("/apply")
    @PreAuthorize("@access.setupAllowed()")
    @Audited(action = "SETUP_APPLY", resource = "PLATFORM_DATABASE")
    @Operation(operationId = "applyDatabaseSettings",
            summary = "Save the settings (password encrypted), run Flyway migrations and switch to normal mode")
    @ApiResponse(responseCode = "200", description = "The new setup status")
    @ApiResponse(responseCode = "400", description = "Invalid settings or the database cannot be reached",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Environment settings win, or an onboarding run is in progress",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public SetupModels.Status apply(@RequestBody SetupModels.ConnectionRequest request) {
        return setup.apply(request);
    }

    @PostMapping("/admin")
    @PreAuthorize("@access.bootstrapAllowed()")
    @Audited(action = "SETUP_ADMIN_CREATE", resource = "USER")
    @Operation(operationId = "createFirstAdministrator",
            summary = "Create the first user, a platform administrator, and sign in (only while no user exists)")
    @ApiResponse(responseCode = "200", description = "Created and signed in")
    @ApiResponse(responseCode = "400", description = "Invalid username or the password breaks the policy",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "The platform already has users",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public AuthController.Me createAdministrator(@RequestBody AdministratorRequest request,
                                                 HttpServletRequest http, HttpServletResponse response) {
        users.bootstrapAdministrator(request.username(), request.displayName(), request.password());
        UddpPrincipal principal = authentication.authenticate(request.username(), request.password());
        sessions.signIn(http, response, principal);
        return AuthController.me(principal, http);
    }

    @PostMapping("/reset-demo")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "SETUP_RESET_DEMO", resource = "PLATFORM_DATABASE")
    @Operation(operationId = "resetDemoDatabase",
            summary = "Drop and recreate a *_demo database and remove Studio-generated packs, drafts and uploads")
    @ApiResponse(responseCode = "200", description = "The setup status after the reset")
    @ApiResponse(responseCode = "400", description = "The confirmation does not match the database name",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Not a *_demo database, or an onboarding run is in progress",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public SetupModels.Status resetDemo(@RequestBody SetupModels.ResetRequest request) {
        return setup.resetDemo(request.confirmDatabaseName());
    }
}
