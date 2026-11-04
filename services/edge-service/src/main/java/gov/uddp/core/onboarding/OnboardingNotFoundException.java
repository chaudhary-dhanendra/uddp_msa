package gov.uddp.core.onboarding;

/** A requested onboarding run or State Pack does not exist. */
public class OnboardingNotFoundException extends IllegalArgumentException {
    public OnboardingNotFoundException(String message) {
        super(message);
    }
}
