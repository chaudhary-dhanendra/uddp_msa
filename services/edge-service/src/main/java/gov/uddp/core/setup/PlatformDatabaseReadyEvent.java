package gov.uddp.core.setup;

/** Published when the Setup screen has applied a platform database or reset a demo database. */
public record PlatformDatabaseReadyEvent(String reason) {
}
