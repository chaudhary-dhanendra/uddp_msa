import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { api, unwrap } from "../../../api/client";
import type { DryRun } from "../../../api/types";
import { useI18n } from "../../../i18n/i18n";
import { Button, Panel } from "../../../shared/components";
import { fixLink } from "../../held/fixTargets";
import { ErrorNote } from "./StepFooter";
import styles from "../studio.module.css";

const ROWS = 50_000;

/**
 * Checks the pack against a large sample of the source (up to 50,000 rows) in the background, before a large source
 * is loaded: the rows that would be held, grouped by reason, each with the exact place in the pack that fixes it.
 */
export function DryRunPanel({ draftId }: { draftId: string }) {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const key = ["studio", "dry-run", draftId];
  const result = useQuery({
    queryKey: key,
    queryFn: () =>
      unwrap(
        api.GET("/api/studio/drafts/{draftId}/dry-run", {
          params: { path: { draftId } },
        }),
      ),
    refetchInterval: (query) =>
      query.state.data?.status === "RUNNING" ? 2000 : false,
  });
  const start = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/studio/drafts/{draftId}/dry-run", {
          params: { path: { draftId }, query: { rows: ROWS } },
        }),
      ),
    onSuccess: (started) => queryClient.setQueryData(key, started),
  });
  const run: DryRun | undefined = result.data;
  const running = run?.status === "RUNNING";
  return (
    <Panel
      title={t("studio.dryRun.title")}
      actions={
        <Button
          variant="secondary"
          onClick={() => start.mutate()}
          disabled={running || start.isPending}
          data-testid="dry-run-start"
        >
          {t("studio.dryRun.start", { rows: ROWS.toLocaleString() })}
        </Button>
      }
    >
      <p className="muted">{t("studio.dryRun.intro")}</p>
      <ErrorNote error={start.error} />
      {running && (
        <p role="status" data-testid="dry-run-running">
          {t("studio.dryRun.running")}
        </p>
      )}
      {run?.status === "FAILED" && (
        <p className={styles.error} role="alert">
          {t("studio.dryRun.failed", { message: run.problem ?? "" })}
        </p>
      )}
      {run?.status === "COMPLETED" && (
        <div className="stack" data-testid="dry-run-result">
          <p>
            {t("studio.dryRun.summary", {
              rows: run.rowsChecked.toLocaleString(),
              errors: run.rowsWithErrors.toLocaleString(),
              warnings: run.rowsWithWarnings.toLocaleString(),
            })}
            {run.moreRows ? ` ${t("studio.dryRun.more")}` : ""}
          </p>
          {run.groups.length === 0 ? (
            <p className={styles.ok}>{t("studio.dryRun.clean")}</p>
          ) : (
            <table className={styles.table} data-testid="dry-run-groups">
              <caption className="visually-hidden">
                {t("studio.dryRun.title")}
              </caption>
              <thead>
                <tr>
                  <th scope="col">{t("studio.dryRun.reason")}</th>
                  <th scope="col">{t("studio.dryRun.rows")}</th>
                  <th scope="col">{t("studio.dryRun.example")}</th>
                  <th scope="col">{t("studio.dryRun.fix")}</th>
                </tr>
              </thead>
              <tbody>
                {run.groups.map((group) => {
                  const fix = fixLink(group.fix, group.code ?? "OTHER", draftId);
                  return (
                    <tr
                      key={`${group.code}|${group.severity}|${group.field ?? ""}`}
                      className={
                        group.severity === "error"
                          ? styles.errorRow
                          : styles.warningRow
                      }
                    >
                      <td>
                        <strong>{group.code}</strong>
                        {group.field ? ` · ${group.field}` : ""}
                        <div className="muted">{group.message}</div>
                      </td>
                      <td>{group.count.toLocaleString()}</td>
                      <td>
                        {t("studio.dryRun.sampleRows", {
                          rows: group.sampleRows.join(", "),
                        })}
                      </td>
                      <td>
                        <Link to={fix.to}>{fix.label}</Link>
                        {group.suggestion && (
                          <div className="muted">{group.suggestion}</div>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      )}
    </Panel>
  );
}
