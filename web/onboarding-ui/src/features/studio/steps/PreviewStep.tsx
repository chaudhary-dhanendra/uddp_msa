import type { PreviewRow, StudioDraft } from "../../../api/types";
import { fixLink } from "../../held/fixTargets";
import { useI18n } from "../../../i18n/i18n";
import { Link } from "react-router-dom";
import { Button, Panel, QueryState } from "../../../shared/components";
import { usePreview } from "../hooks";
import { StepFooter } from "./StepFooter";
import styles from "../studio.module.css";

type PreviewIssue = PreviewRow["issues"][number];

function field(row: PreviewRow, path: string): string {
  let node: unknown = row.resident;
  for (const part of path.split(".")) {
    node =
      node && typeof node === "object"
        ? (node as Record<string, unknown>)[part]
        : undefined;
  }
  return node === undefined || node === null ? "" : String(node);
}

function family(row: PreviewRow): string {
  const ids = row.family?.["sourceFamilyIds"];
  return Array.isArray(ids) ? ids.join(", ") : "";
}

function benefit(row: PreviewRow): string {
  const scheme = row.benefit?.["scheme"];
  const id =
    scheme && typeof scheme === "object"
      ? (scheme as Record<string, unknown>)["schemeId"]
      : undefined;
  const status = row.benefit?.["status"];
  return id ? `${String(id)} (${String(status ?? "")})` : "";
}

/**
 * What the sample would hold or reject once the pack is live, grouped by reason code (the codes held records carry).
 * Each group opens the exact wizard step that fixes it and shows the suggested fix, when there is one.
 */
export function HeldPreviewCounts({ rows, draftId }: { rows: PreviewRow[]; draftId: string }) {
  const { t } = useI18n();
  const groups = new Map<string, { issue: PreviewIssue; count: number; rows: Set<number>; error: boolean }>();
  for (const row of rows) {
    for (const issue of row.issues) {
      const code = issue.code ?? "OTHER";
      const entry = groups.get(code) ?? { issue, count: 0, rows: new Set<number>(), error: false };
      entry.count += 1;
      entry.rows.add(row.rowNumber);
      entry.error ||= issue.severity === "error";
      groups.set(code, entry);
    }
  }
  const held = [...groups.values()].filter((g) => g.error);
  return (
    <Panel title={t("held.preview.title")}>
      {groups.size === 0 ? (
        <p data-testid="preview-held-none">{t("held.preview.none")}</p>
      ) : (
        <ul data-testid="preview-held-counts">
          {[...groups.entries()].map(([code, g]) => {
            const fix = fixLink(g.issue.fix, code, draftId);
            return (
              <li key={code} data-testid={`preview-code-${code}`}>
                {t("held.preview.group", { count: g.count, code, message: g.issue.message })}{" "}
                <Link to={fix.to}>{fix.label}</Link>
                {g.issue.suggestion && (
                  <div>{t("held.preview.suggestion", { text: g.issue.suggestion })}</div>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {held.length > 0 && (
        <p>{t("held.preview.total", { count: new Set(held.flatMap((g) => [...g.rows])).size })}</p>
      )}
    </Panel>
  );
}

/** Step 6: the dry run, with every row's mapped values and its errors and warnings highlighted. */
export function PreviewStep({
  draft,
  onBack,
  onDone,
}: {
  draft: StudioDraft;
  onBack: () => void;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const preview = usePreview(draft.draftId ?? "");
  return (
    <QueryState query={preview}>
      {(p) => (
        <div className="stack">
          <Panel
            title={t("studio.steps.7")}
            actions={
              <Button
                variant="secondary"
                onClick={() => void preview.refetch()}
              >
                {t("studio.preview.run")}
              </Button>
            }
          >
            <p className="muted">
              {t("studio.preview.intro", { rows: p.rowsPreviewed })}
            </p>
            <p data-testid="preview-summary">
              {t("studio.preview.summary", {
                rows: p.rowsPreviewed,
                errors: p.blockingErrors,
                warnings: p.warnings,
              })}
            </p>
            {p.packError && (
              <p className={styles.error} role="alert">
                {t("studio.preview.packError", { message: p.packError })}
              </p>
            )}
            {p.blockingErrors > 0 ? (
              <p
                className={styles.error}
                role="alert"
                data-testid="preview-blocked"
              >
                {t("studio.preview.blocked")}
              </p>
            ) : (
              !p.packError && (
                <p className={styles.ok} data-testid="preview-clean">
                  {t("studio.preview.clean")}
                </p>
              )
            )}
          </Panel>
          <HeldPreviewCounts rows={p.rows} draftId={draft.draftId ?? ""} />
          <Panel>
            <div style={{ overflowX: "auto" }}>
              <table className={styles.table} data-testid="preview-table">
                <caption className="visually-hidden">
                  {t("studio.steps.7")}
                </caption>
                <thead>
                  <tr>
                    <th scope="col">{t("studio.preview.row")}</th>
                    <th scope="col">{t("studio.preview.name")}</th>
                    <th scope="col">{t("studio.preview.dob")}</th>
                    <th scope="col">{t("studio.preview.gender")}</th>
                    <th scope="col">{t("studio.preview.district")}</th>
                    <th scope="col">{t("studio.preview.mobile")}</th>
                    <th scope="col">{t("studio.preview.family")}</th>
                    <th scope="col">{t("studio.preview.benefit")}</th>
                    <th scope="col">{t("studio.preview.issues")}</th>
                  </tr>
                </thead>
                <tbody>
                  {p.rows.map((row) => {
                    const errors = row.issues.filter(
                      (i) => i.severity === "error",
                    );
                    const className =
                      errors.length > 0
                        ? styles.errorRow
                        : row.issues.length > 0
                          ? styles.warningRow
                          : undefined;
                    return (
                      <tr key={row.rowNumber} className={className}>
                        <td>{row.rowNumber}</td>
                        <td>{field(row, "name")}</td>
                        <td>{field(row, "dateOfBirth")}</td>
                        <td>{field(row, "gender")}</td>
                        <td>{field(row, "address.districtLGD")}</td>
                        <td>{field(row, "mobile")}</td>
                        <td>{family(row)}</td>
                        <td>{benefit(row)}</td>
                        <td>
                          {row.issues.map((issue, i) => (
                            <div key={i}>
                              <strong>{issue.severity}</strong>{" "}
                              {issue.field ? `${issue.field}: ` : ""}
                              {issue.message}
                            </div>
                          ))}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </Panel>
          <StepFooter onBack={onBack} onContinue={onDone} />
        </div>
      )}
    </QueryState>
  );
}
