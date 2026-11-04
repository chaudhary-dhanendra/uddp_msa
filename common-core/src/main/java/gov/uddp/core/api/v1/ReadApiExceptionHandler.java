package gov.uddp.core.api.v1;

import gov.uddp.core.read.ReadNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Error contract of the read API: {@link ApiError} with 404 for an unknown resource and 400 for an invalid ID, filter,
 * page, size or sort, 409 for a stewardship, setup or Studio conflict. Other failures are 500 with a generic message (details stay in the server log).
 */
// The shaping administration controller lives in its own module and is named by package (no class dependency on it).
@RestControllerAdvice(basePackages = {"gov.uddp.core.api.v1", "gov.uddp.shaping.admin.web"})
public class ReadApiExceptionHandler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ReadApiExceptionHandler.class);

    @ExceptionHandler(ReadNotFoundException.class)
    ResponseEntity<ApiError> notFound(ReadNotFoundException e, HttpServletRequest request) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), request);
    }

    /** M13: a stewardship request that conflicts with the current state. */
    @ExceptionHandler({gov.uddp.core.stewardship.StewardshipConflictException.class,
            gov.uddp.core.setup.SetupConflictException.class, gov.uddp.core.studio.StudioConflictException.class,
            gov.uddp.core.onboarding.OnboardingConflictException.class,
            gov.uddp.core.security.UserConflictException.class, gov.uddp.core.rules.ApprovalConflictException.class})
    ResponseEntity<ApiError> conflict(RuntimeException e, HttpServletRequest request) {
        return body(HttpStatus.CONFLICT, "CONFLICT", e.getMessage(), request);
    }

    /** A browser upload over the multipart limit: large deliveries belong in the source's landing folder. */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge(Exception e, HttpServletRequest request) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE", "This file is too large to upload through the browser. "
                + "Copy it into the source's landing folder (the location in its source configuration) and start the "
                + "run from the Onboarding screen's landing-folder button.", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, gov.uddp.core.statepack.StatePackLoadException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> badRequest(Exception e, HttpServletRequest request) {
        String message = e instanceof MethodArgumentTypeMismatchException mismatch
                ? "Invalid value for " + mismatch.getName() + ": " + mismatch.getValue()
                : e instanceof org.springframework.http.converter.HttpMessageNotReadableException
                        ? "Request body is not valid JSON for this endpoint"
                        : e.getMessage();
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> failure(Exception e, HttpServletRequest request) {
        LOG.error("Read API request failed: {}", request.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be completed", request);
    }

    private static ResponseEntity<ApiError> body(HttpStatus status, String error, String message,
                                                 HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), error, message, request.getRequestURI()));
    }
}
