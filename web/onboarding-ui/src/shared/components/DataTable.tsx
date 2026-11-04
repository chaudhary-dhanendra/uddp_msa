import type { ReactNode } from "react";
import { useI18n } from "../../i18n/i18n";
import type { PageMeta } from "../../api/types";
import styles from "./components.module.css";
import { Button } from "./forms";

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  align?: "start" | "end";
}

/** A semantic table with a caption; wide tables scroll inside their frame instead of the page. */
export function DataTable<T>({
  caption,
  columns,
  rows,
  rowKey,
  emptyText,
  testId,
}: {
  caption: string;
  columns: Column<T>[];
  rows: readonly T[];
  rowKey: (row: T) => string;
  emptyText?: string;
  testId?: string;
}) {
  const { t } = useI18n();
  return (
    <div className={styles.tableWrap} data-testid={testId}>
      <table className={styles.table}>
        <caption>{caption}</caption>
        <thead>
          <tr>
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                className={column.align === "end" ? styles.alignEnd : undefined}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="muted">
                {emptyText ?? t("common.noRows")}
              </td>
            </tr>
          ) : (
            rows.map((row) => (
              <tr key={rowKey(row)}>
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={
                      column.align === "end" ? styles.alignEnd : undefined
                    }
                  >
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}

/** Previous/next paging for a {@link PageMeta}; `onPage` receives the 0-based page to show. */
export function Pagination({
  page,
  onPage,
  label,
}: {
  page: PageMeta;
  onPage: (page: number) => void;
  label: string;
}) {
  const { t, number } = useI18n();
  const last = Math.max(page.totalPages - 1, 0);
  return (
    <nav className={styles.pagination} aria-label={label}>
      <span className="muted" aria-live="polite">
        {t("common.pageOf", {
          page: page.totalPages === 0 ? 0 : page.page + 1,
          pages: page.totalPages,
          total: number(page.totalItems),
        })}
      </span>
      <span className="row">
        <Button
          variant="secondary"
          disabled={page.page <= 0}
          onClick={() => onPage(page.page - 1)}
        >
          {t("common.previous")}
        </Button>
        <Button
          variant="secondary"
          disabled={page.page >= last}
          onClick={() => onPage(page.page + 1)}
        >
          {t("common.next")}
        </Button>
      </span>
    </nav>
  );
}
