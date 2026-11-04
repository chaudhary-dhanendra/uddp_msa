package gov.uddp.core.setup;

/**
 * Something that may be running onboarding work in this process. The connection cannot be changed or the demo
 * database reset while any activity reports work in progress.
 */
public interface OnboardingActivity {
    boolean inProgress();
}
