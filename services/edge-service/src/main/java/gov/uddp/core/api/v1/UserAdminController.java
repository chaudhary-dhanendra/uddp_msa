package gov.uddp.core.api.v1;

import gov.uddp.core.security.Audited;
import gov.uddp.core.security.UserAccountStore;
import gov.uddp.core.security.UserAccountStore.PermissionDefinition;
import gov.uddp.core.security.UserAccountStore.RoleDefinition;
import gov.uddp.core.security.UserAdminService;
import gov.uddp.core.security.UserAdminService.UserView;
import gov.uddp.core.security.UserAdminService.UserWithPassword;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User, role and department-scope management (P1 Part 6), for platform administrators (USER_MANAGE): list and search
 * users, create one with a one-time password (changed at first sign-in), enable and disable, unlock, reset the
 * password, set roles and department scope, and read the role and permission matrix. Every change is audited. Nobody
 * disables themselves or removes PLATFORM_ADMIN from themselves, and the last enabled platform administrator stays
 * (409). A disabled user's sessions end at their next request.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Users")
@PreAuthorize("hasAuthority('PERM_USER_MANAGE')")
public class UserAdminController {
    private final UserAdminService admin;
    private final UserAccountStore accounts;

    public UserAdminController(UserAdminService admin, UserAccountStore accounts) {
        this.admin = admin;
        this.accounts = accounts;
    }

    /** A new local user: roles and the departments they may see (department codes, or ALL). */
    public record CreateUserRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String username,
                                    @Schema(nullable = true) String displayName,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> roles,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> departmentScope) {
    }

    public record RolesRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> roles) {
    }

    public record DepartmentScopeRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> departmentScope) {
    }

    /** The role and permission matrix (read-only). */
    public record RoleMatrix(List<RoleDefinition> roles, List<PermissionDefinition> permissions) {
    }

    @Operation(operationId = "listUsers", summary = "Users, optionally filtered by text and status")
    @GetMapping("/users")
    public List<UserView> list(@RequestParam(required = false) String q,
                               @RequestParam(required = false) String status) {
        return admin.search(q, status);
    }

    @Operation(operationId = "getUser", summary = "One user")
    @GetMapping("/users/{id}")
    public UserView get(@PathVariable UUID id) {
        return admin.get(id);
    }

    @Operation(operationId = "createUser", summary = "Creates a local user; the one-time password is shown only here")
    @ApiResponse(responseCode = "201", description = "The user and the one-time password")
    @ApiResponse(responseCode = "409", description = "The username is taken",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Audited(action = "USER_CREATE", resource = "USER")
    public UserWithPassword create(@RequestBody CreateUserRequest request) {
        return admin.create(request.username(), request.displayName(), request.roles(), request.departmentScope());
    }

    @Operation(operationId = "enableUser", summary = "Enables a user")
    @PostMapping("/users/{id}/enable")
    @Audited(action = "USER_ENABLE", resource = "USER", idVariable = "id")
    public UserView enable(@PathVariable UUID id) {
        return admin.setEnabled(id, true);
    }

    @Operation(operationId = "disableUser", summary = "Disables a user and ends their sessions")
    @ApiResponse(responseCode = "409", description = "Yourself, or the last enabled platform administrator",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/users/{id}/disable")
    @Audited(action = "USER_DISABLE", resource = "USER", idVariable = "id")
    public UserView disable(@PathVariable UUID id) {
        return admin.setEnabled(id, false);
    }

    @Operation(operationId = "unlockUser", summary = "Clears a sign-in lockout")
    @PostMapping("/users/{id}/unlock")
    @Audited(action = "USER_UNLOCK", resource = "USER", idVariable = "id")
    public UserView unlock(@PathVariable UUID id) {
        return admin.unlock(id);
    }

    @Operation(operationId = "resetUserPassword",
            summary = "Sets a one-time password (shown only here) that must be changed at the next sign-in")
    @PostMapping("/users/{id}/reset-password")
    @Audited(action = "USER_PASSWORD_RESET", resource = "USER", idVariable = "id")
    public UserWithPassword resetPassword(@PathVariable UUID id) {
        return admin.resetPassword(id);
    }

    @Operation(operationId = "setUserRoles", summary = "Sets the user's roles (assigns and removes)")
    @ApiResponse(responseCode = "409", description = "PLATFORM_ADMIN removed from yourself or from the last admin",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/users/{id}/roles")
    @Audited(action = "USER_ROLES_UPDATE", resource = "USER", idVariable = "id")
    public UserView setRoles(@PathVariable UUID id, @RequestBody RolesRequest request) {
        return admin.setRoles(id, request.roles());
    }

    @Operation(operationId = "setUserDepartmentScope",
            summary = "Sets the departments the user may see (department codes, or ALL)")
    @PutMapping("/users/{id}/department-scope")
    @Audited(action = "USER_SCOPE_UPDATE", resource = "USER", idVariable = "id")
    public UserView setDepartmentScope(@PathVariable UUID id, @RequestBody DepartmentScopeRequest request) {
        return admin.setDepartmentScope(id, request.departmentScope());
    }

    @Operation(operationId = "listRoles", summary = "Roles with their permissions, and every permission (read-only)")
    @GetMapping("/roles")
    public RoleMatrix roles() {
        return new RoleMatrix(accounts.roles(), accounts.permissions());
    }
}
