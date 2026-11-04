import { useI18n } from "../../i18n/i18n";
import styles from "./components.module.css";

export type Tone = "success" | "warning" | "danger" | "neutral" | "info";

const TONES: Record<string, Tone> = {
  COMPLETED: "success",
  ACTIVE: "success",
  AUTO_MATCH: "success",
  SAME: "success",
  VALIDATED: "info",
  RUNNING: "warning",
  STARTED: "warning",
  POSSIBLE_MATCH: "warning",
  WARNING: "warning",
  DRAFT: "neutral",
  APPROVED: "success",
  PENDING_APPROVAL: "warning",
  REJECTED: "danger",
  MISSING: "neutral",
  FAILED: "danger",
  ERROR: "danger",
  DIFFERENT: "danger",
  RETIRED: "neutral",
  NO_MATCH: "neutral",
  PENDING: "neutral",
  UNREGISTERED: "neutral",
  FULL: "info",
  INCREMENTAL: "info",
};

export function toneOf(status: string | null | undefined): Tone {
  return (status && TONES[status]) || "neutral";
}

/** A status value as a coloured badge; the label comes from i18n `status.<VALUE>` and falls back to the value. */
export function StatusBadge({ status }: { status: string | null | undefined }) {
  const { t } = useI18n();
  if (!status) {
    return <span className="muted">—</span>;
  }
  const key = `status.${status}`;
  const label = t(key);
  return (
    <span className={`${styles.badge} ${styles[toneOf(status)]}`}>
      {label === key ? status : label}
    </span>
  );
}
