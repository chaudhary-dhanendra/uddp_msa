package gov.uddp.core.onboarding;

/** The request is valid but not allowed in the current run or State Pack lifecycle state. */
public class OnboardingConflictException extends IllegalStateException {
    public OnboardingConflictException(String message) {
        super(message);
    }

    public OnboardingConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
