package gov.uddp.core.api;

import gov.uddp.core.onboarding.OnboardingConflictException;
import gov.uddp.core.onboarding.OnboardingNotFoundException;
import gov.uddp.core.onboarding.OnboardingStageFailedException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Error contract for the onboarding API: a JSON body with status, error and message. 404 for an unknown run or State
 * Pack, 409 for a request the current run or lifecycle state does not allow, 400 for malformed input, and 500 when a
 * stage executed and failed (the failure is persisted; runId and stage say what to resume).
 */
@RestControllerAdvice(assignableTypes = {OnboardingController.class, OperationsController.class, MonitoringController.class,
        RuleSetController.class, SchemePackController.class, IdentityController.class,
        GeographyController.class})
public class OnboardingExceptionHandler {
    @ExceptionHandler(OnboardingNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(OnboardingNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler({OnboardingConflictException.class, gov.uddp.core.rules.ApprovalConflictException.class,
            gov.uddp.core.ai.AiDisabledException.class})
    ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, "CONFLICT", e.getMessage());
    }

    @ExceptionHandler(OnboardingStageFailedException.class)
    ResponseEntity<Map<String, Object>> stageFailed(OnboardingStageFailedException e) {
        var response = body(HttpStatus.INTERNAL_SERVER_ERROR, "STAGE_FAILED", e.getMessage());
        response.getBody().put("runId", e.runId());
        response.getBody().put("stage", e.stage());
        return response;
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        String message;
        if (e instanceof MethodArgumentTypeMismatchException mismatch) {
            message = "Invalid value for " + mismatch.getName() + ": " + mismatch.getValue();
        } else if (e instanceof HttpMessageNotReadableException) {
            message = "Malformed request body";
        } else {
            message = e.getMessage();
        }
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
