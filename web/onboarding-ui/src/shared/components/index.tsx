import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import styles from "./components.module.css";

export { DataTable, Pagination, type Column } from "./DataTable";
export { QueryState } from "./QueryState";
export { StageTimeline, type TimelineItem } from "./StageTimeline";
export { Comparison, type ComparisonRow } from "./Comparison";
export { Field, TextInput, Select, Button, FilterBar } from "./forms";
export { StatusBadge, toneOf, type Tone } from "./StatusBadge";

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <header className={styles.pageHeader}>
      <div>
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {actions && <div className="row">{actions}</div>}
    </header>
  );
}

export function Panel({
  title,
  actions,
  children,
  headingLevel = 2,
  id,
}: {
  title?: string;
  actions?: ReactNode;
  children: ReactNode;
  headingLevel?: 2 | 3;
  id?: string;
}) {
  const Heading = headingLevel === 2 ? "h2" : "h3";
  const headingId = id ? `${id}-heading` : undefined;
  return (
    <section className={styles.panel} aria-labelledby={headingId} id={id}>
      {(title || actions) && (
        <div className={styles.panelHeader}>
          {title && <Heading id={headingId}>{title}</Heading>}
          {actions}
        </div>
      )}
      {children}
    </section>
  );
}

export interface DetailItem {
  label: string;
  value: ReactNode;
}

/** A detail panel's label/value pairs as a description list. */
export function DescriptionList({ items }: { items: DetailItem[] }) {
  return (
    <dl className={styles.descriptionList}>
      {items.map((item) => (
        <div key={item.label} style={{ display: "contents" }}>
          <dt>{item.label}</dt>
          <dd>{item.value ?? "—"}</dd>
        </div>
      ))}
    </dl>
  );
}

export function StatCard({
  label,
  value,
  testId,
}: {
  label: string;
  value: ReactNode;
  testId?: string;
}) {
  return (
    <div className={styles.stat} data-testid={testId}>
      <div className={styles.statValue}>{value}</div>
      <div className={styles.statLabel}>{label}</div>
    </div>
  );
}

export function StatGrid({
  children,
  label,
}: {
  children: ReactNode;
  label: string;
}) {
  return (
    <div className={styles.statGrid} role="group" aria-label={label}>
      {children}
    </div>
  );
}

/** A link to an ID, shortened for display with the full ID as its accessible name. */
export function IdLink({
  id,
  to,
  label,
}: {
  id: string | null | undefined;
  to: string;
  label?: string;
}) {
  if (!id) {
    return <span className="muted">—</span>;
  }
  return (
    <Link
      to={to}
      className={styles.idLink}
      title={id}
      aria-label={label ? `${label} ${id}` : id}
    >
      {id.slice(0, 8)}
    </Link>
  );
}

export function ShortId({ id }: { id: string | null | undefined }) {
  return id ? (
    <span className={styles.idLink} title={id}>
      {id.slice(0, 8)}
    </span>
  ) : (
    <span className="muted">—</span>
  );
}

/** Any JSON value, compactly: scalars inline, objects and arrays as formatted JSON. */
export function JsonValue({ value }: { value: unknown }) {
  if (value === null || value === undefined || value === "") {
    return <span className="muted">—</span>;
  }
  if (typeof value === "object") {
    return <pre className={styles.json}>{JSON.stringify(value, null, 2)}</pre>;
  }
  return <span>{String(value)}</span>;
}

export function JsonBlock({ value, label }: { value: unknown; label: string }) {
  return (
    <pre className={styles.json} aria-label={label} tabIndex={0}>
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}
