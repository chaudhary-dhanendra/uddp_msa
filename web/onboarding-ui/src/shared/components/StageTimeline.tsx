import type { ReactNode } from "react";
import styles from "./components.module.css";
import { StatusBadge, toneOf } from "./StatusBadge";

export interface TimelineItem {
  key: string;
  title: ReactNode;
  status: string;
  meta?: ReactNode;
  error?: string | null;
  details?: ReactNode;
}

/** An ordered vertical timeline (onboarding stages, Golden lifecycle events). */
export function StageTimeline({
  items,
  label,
  testId,
}: {
  items: TimelineItem[];
  label: string;
  testId?: string;
}) {
  return (
    <ol className={styles.timeline} aria-label={label} data-testid={testId}>
      {items.map((item) => (
        <li
          key={item.key}
          className={styles.timelineItem}
          data-tone={toneOf(item.status)}
          data-status={item.status}
        >
          <div className={styles.timelineTitle}>
            {item.title} <StatusBadge status={item.status} />
          </div>
          {item.meta && <div className={styles.timelineMeta}>{item.meta}</div>}
          {item.error && (
            <div className={styles.timelineError} role="note">
              {item.error}
            </div>
          )}
          {item.details}
        </li>
      ))}
    </ol>
  );
}
