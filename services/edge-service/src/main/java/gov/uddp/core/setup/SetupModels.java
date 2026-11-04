package gov.uddp.core.setup;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request and response shapes of {@code /api/setup}. No response ever carries a password. */
public final class SetupModels {
    private SetupModels() {
    }

    @Schema(description = "A PostgreSQL connection. A blank password keeps the saved one for the same host, port and user.")
    public record ConnectionRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String host,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer port,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String database,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String user,
            String password,
            Boolean createDatabaseIfMissing) {
        @Override
        public String toString() {
            return "ConnectionRequest[host=" + host + ", port=" + port + ", database=" + database + ", user=" + user
                    + ", password=******, createDatabaseIfMissing=" + createDatabaseIfMissing + "]";
        }
    }

    @Schema(description = "Connection settings with the password masked")
    public record MaskedSettings(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String host,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int port,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String database,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String user,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Always true when shown; the value is never returned")
            boolean passwordSet) {
    }

    @Schema(description = "Whether the platform database is configured, and from which source")
    public record Status(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"SETUP", "READY"}) String mode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"ENVIRONMENT", "LOCAL_STORE", "NONE"})
            String source,
            @Schema(nullable = true) MaskedSettings settings,
            @Schema(nullable = true, description = "Shown only to platform administrators (and in setup mode)")
            String settingsFile,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean environmentVariablesPresent,
            @Schema(nullable = true) String schemaVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean onboardingInProgress,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The configured database name ends with _demo")
            boolean resetAllowed,
            @Schema(nullable = true) String problem,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The database is ready but has no user: the Setup screen creates the first administrator")
            boolean adminRequired) {
    }

    @Schema(description = "Result of a connection test")
    public record ConnectionTest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ok,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
            @Schema(nullable = true) String serverVersion,
            @Schema(nullable = true) Boolean databaseExists) {
    }

    @Schema(description = "Confirmation for resetting the demo database")
    public record ResetRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String confirmDatabaseName) {
    }
}
