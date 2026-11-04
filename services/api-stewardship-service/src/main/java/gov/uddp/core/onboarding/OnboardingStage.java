package gov.uddp.core.onboarding;

public enum OnboardingStage {
    PACK_VALIDATED, SOURCE_TESTED, INGESTED, MAPPED, DQ_CHECKED, MATCHED, GOLDEN_BUILT, PUBLISHED;

    public OnboardingStage next() {
        return this == PUBLISHED ? null : values()[ordinal() + 1];
    }
}
