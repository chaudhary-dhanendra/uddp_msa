import type { ReactNode } from "react";
import styles from "./components.module.css";
import { StatusBadge } from "./StatusBadge";

export interface ComparisonRow {
  key: string;
  label: string;
  left: ReactNode;
  right: ReactNode;
  /** SAME, DIFFERENT or MISSING; shown as a badge and used to shade the row. */
  outcome?: string;
}

/** Two records side by side, one row per field. */
export function Comparison({
  caption,
  leftHeader,
  rightHeader,
  outcomeHeader,
  rows,
  testId,
}: {
  caption: string;
  leftHeader: ReactNode;
  rightHeader: ReactNode;
  outcomeHeader?: string;
  rows: ComparisonRow[];
  testId?: string;
}) {
  return (
    <div className={styles.tableWrap} data-testid={testId}>
      <table className={`${styles.table} ${styles.compareTable}`}>
        <caption>{caption}</caption>
        <thead>
          <tr>
            <th scope="col">
              <span className="visually-hidden">{caption}</span>
            </th>
            <th scope="col">{leftHeader}</th>
            <th scope="col">{rightHeader}</th>
            {outcomeHeader && <th scope="col">{outcomeHeader}</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.key}>
              <th scope="row">{row.label}</th>
              <td data-outcome={row.outcome}>{row.left}</td>
              <td data-outcome={row.outcome}>{row.right}</td>
              {outcomeHeader && (
                <td>
                  <StatusBadge status={row.outcome} />
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
