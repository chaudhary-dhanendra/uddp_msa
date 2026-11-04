package gov.uddp.core.api.v1;

/** The error body of every {@code /api/v1} endpoint. */
public record ApiError(int status, String error, String message, String path) {
}
