import { useI18n } from "../../../i18n/i18n";
import { Button } from "../../../shared/components";
import styles from "../studio.module.css";

/** Back and Save-and-continue buttons shared by every wizard step. */
export function StepFooter({
  onBack,
  onContinue,
  continueLabel,
  disabled,
  busy,
}: {
  onBack?: () => void;
  onContinue?: () => void;
  continueLabel?: string;
  disabled?: boolean;
  busy?: boolean;
}) {
  const { t } = useI18n();
  return (
    <div className={styles.footer}>
      <span>
        {onBack && (
          <Button variant="secondary" onClick={onBack}>
            {t("studio.back")}
          </Button>
        )}
      </span>
      {onContinue && (
        <Button
          onClick={onContinue}
          disabled={disabled || busy}
          data-testid="continue"
        >
          {busy ? t("studio.saving") : (continueLabel ?? t("studio.continue"))}
        </Button>
      )}
    </div>
  );
}

/** A mutation's failure, shown next to the step that caused it. */
export function ErrorNote({ error }: { error: Error | null }) {
  if (!error) {
    return null;
  }
  return (
    <p role="alert" className={styles.error} data-testid="step-error">
      {error.message}
    </p>
  );
}
