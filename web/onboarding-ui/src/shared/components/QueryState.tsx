import type { ReactNode } from "react";
import type { UseQueryResult } from "@tanstack/react-query";
import { useI18n } from "../../i18n/i18n";
import { ApiFailure } from "../../api/client";
import styles from "./components.module.css";

/**
 * Loading, error and empty states for one query; renders `children` with the data once it is there. `isEmpty`
 * decides when data counts as empty (an empty page, for example).
 */
export function QueryState<T>({
  query,
  children,
  isEmpty,
  emptyText,
  label,
}: {
  query: UseQueryResult<T>;
  children: (data: T) => ReactNode;
  isEmpty?: (data: T) => boolean;
  emptyText?: string;
  label?: string;
}) {
  const { t } = useI18n();
  if (query.isPending) {
    return (
      <div className={styles.state} role="status" aria-live="polite">
        {label
          ? t("common.loadingNamed", { name: label })
          : t("common.loading")}
      </div>
    );
  }
  if (query.isError) {
    const error = query.error;
    const status = error instanceof ApiFailure ? error.status : 0;
    const message =
      status === 404
        ? t("common.notFound")
        : status === 0
          ? t("common.unreachable")
          : error.message;
    return (
      <div className={styles.errorState} role="alert">
        <strong>{t("common.errorTitle")}</strong> {message}
        {status !== 404 && (
          <>
            {" "}
            <button
              type="button"
              onClick={() => void query.refetch()}
              className="linkButton"
            >
              {t("common.retry")}
            </button>
          </>
        )}
      </div>
    );
  }
  if (isEmpty?.(query.data)) {
    return <div className={styles.state}>{emptyText ?? t("common.empty")}</div>;
  }
  return <>{children(query.data)}</>;
}
