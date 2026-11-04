import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { StudioDraft } from "../../../api/types";
import { ApiFailure, csrfToken, ensureCsrfToken } from "../../../api/client";
import { useI18n } from "../../../i18n/i18n";
import {
  Button,
  DataTable,
  Field,
  Panel,
  QueryState,
} from "../../../shared/components";
import {
  refreshDraft,
  studioKeys,
  useProfile,
  useStudioStatus,
} from "../hooks";
import { useSaveShaping, type Step } from "../../shaping/api";
import { ErrorNote, StepFooter } from "./StepFooter";
import { DatabaseSourceForm } from "./DatabaseSourceForm";
import styles from "../studio.module.css";

/** Above this size an .xlsx gets a hint to export CSV instead: the shaper reads a workbook whole (docs/perf.md). */
export const LARGE_WORKBOOK_BYTES = 20 * 1024 * 1024;

type SheetSummary = { name: string; rows: number; selected: boolean };

/** The profile's workbook sheets (none for a CSV or a database table). */
function workbookSheets(
  sheets: ({ name: string | null; rows: number; selected: boolean } | null)[] | null | undefined,
): SheetSummary[] {
  return (sheets ?? []).flatMap((s) =>
    s && s.name ? [{ name: s.name, rows: s.rows, selected: s.selected }] : [],
  );
}

/** A workbook's sheets with their row counts; the ticked ones are stored as the source's sheets. */
export function SheetPicker({
  draft,
  sheets,
}: {
  draft: StudioDraft;
  sheets: SheetSummary[];
}) {
  const { t } = useI18n();
  const draftId = draft.draftId ?? "";
  const [ticked, setTicked] = useState<string[]>(() =>
    sheets.filter((s) => s.selected).map((s) => s.name),
  );
  const save = useSaveShaping(draftId);
  const toggle = (name: string) =>
    setTicked((current) =>
      current.includes(name)
        ? current.filter((n) => n !== name)
        : sheets.map((s) => s.name).filter((n) => n === name || current.includes(n)),
    );
  return (
    <Panel title={t("studio.upload.sheets")}>
      <fieldset data-testid="sheet-picker">
        <legend className="muted">{t("studio.upload.sheetsHint")}</legend>
        {sheets.map((sheet) => (
          <label key={sheet.name} className="row">
            <input
              type="checkbox"
              checked={ticked.includes(sheet.name)}
              onChange={() => toggle(sheet.name)}
            />
            {t("studio.upload.sheetRows", { name: sheet.name, count: sheet.rows })}
          </label>
        ))}
      </fieldset>
      {ticked.length === 0 && (
        <p role="alert" className={styles.error}>
          {t("studio.upload.sheetsNone")}
        </p>
      )}
      <Button
        variant="secondary"
        disabled={ticked.length === 0 || save.isPending}
        data-testid="save-sheets"
        onClick={() =>
          save.mutate({
            layout: (draft.source?.layout ?? []) as Step[],
            parse: (draft.source?.parse ?? []) as Step[],
            sheets: ticked,
          })
        }
      >
        {t("studio.upload.saveSheets")}
      </Button>
      <ErrorNote error={save.error} />
    </Panel>
  );
}

/** Step 2: upload the CSV or .xlsx workbook into the landing folder, then show its profile and sample rows. */
export function UploadStep({
  draft,
  onBack,
  onDone,
}: {
  draft: StudioDraft;
  onBack: () => void;
  onDone: () => void;
}) {
  const { t, number } = useI18n();
  const queryClient = useQueryClient();
  const draftId = draft.draftId ?? "";
  const [file, setFile] = useState<File | null>(null);
  const [kind, setKind] = useState<"file" | "database">(
    draft.source?.sourceType === "database" ? "database" : "file",
  );
  const status = useStudioStatus();
  const profile = useProfile(draftId, draft.source != null);
  const upload = useMutation({
    mutationFn: async () => {
      if (!file) {
        throw new Error(t("studio.upload.file"));
      }
      const form = new FormData();
      form.append("file", file);
      // Multipart upload: openapi-fetch serialises JSON bodies only, so this one call uses fetch directly and
      // sends the CSRF token itself.
      await ensureCsrfToken();
      const response = await fetch(
        `/api/studio/drafts/${encodeURIComponent(draftId)}/upload`,
        {
          method: "POST",
          body: form,
          headers: { "X-XSRF-TOKEN": csrfToken() ?? "" },
        },
      );
      const body: unknown = await response.json();
      if (!response.ok) {
        const message =
          body && typeof body === "object" && "message" in body
            ? String(body.message)
            : response.statusText;
        throw new ApiFailure(response.status, null, message);
      }
      return body as StudioDraft;
    },
    onSuccess: async (saved) => {
      refreshDraft(queryClient, draftId, saved);
      await queryClient.invalidateQueries({
        queryKey: studioKeys.profile(draftId),
      });
      await queryClient.invalidateQueries({
        queryKey: studioKeys.suggestions(draftId),
      });
    },
  });
  const limitMb = status.data
    ? Math.round(status.data.uploadLimitBytes / (1024 * 1024))
    : 10;
  const largeWorkbook =
    file != null &&
    file.name.toLowerCase().endsWith(".xlsx") &&
    file.size > LARGE_WORKBOOK_BYTES;
  return (
    <div className="stack">
      <Panel title={t("studio.steps.2")}>
        <fieldset className="row">
          <legend>{t("studio.upload.sourceType")}</legend>
          <label className="row">
            <input
              type="radio"
              name="source-type"
              checked={kind === "file"}
              onChange={() => setKind("file")}
            />
            {t("studio.upload.fileSource")}
          </label>
          <label className="row">
            <input
              type="radio"
              name="source-type"
              checked={kind === "database"}
              onChange={() => setKind("database")}
              data-testid="source-type-database"
            />
            {t("studio.upload.databaseSource")}
          </label>
        </fieldset>
        {kind === "database" && <DatabaseSourceForm draft={draft} />}
        {draft.source?.sourceType === "database" && (
          <p className="muted">
            {t("studio.upload.currentDatabase", {
              table: draft.source.table ?? draft.source.query ?? "",
              connection: draft.source.connectionName ?? "",
            })}
          </p>
        )}
        {kind === "file" && (
          <form
            className="row"
            onSubmit={(event) => {
              event.preventDefault();
              upload.mutate();
            }}
          >
            <Field
              id="source-file"
              label={t("studio.upload.file")}
              hint={t("studio.upload.limit", { limit: limitMb })}
            >
              <input
                id="source-file"
                type="file"
                accept=".csv,.xlsx"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                data-testid="source-file"
              />
            </Field>
            {largeWorkbook && (
              <p role="status" className="muted" data-testid="large-workbook">
                {t("studio.upload.largeWorkbook", {
                  size: Math.round(file.size / (1024 * 1024)),
                })}
              </p>
            )}
            <Button
              type="submit"
              disabled={!file || upload.isPending}
              data-testid="upload"
            >
              {upload.isPending
                ? t("studio.upload.uploading")
                : t("studio.upload.button")}
            </Button>
          </form>
        )}
        {draft.source?.fileName && (
          <p className="muted" data-testid="current-file">
            {t("studio.upload.current", {
              file: draft.source.fileName,
              size: number(draft.source.sizeBytes ?? 0),
            })}
          </p>
        )}
        <ErrorNote error={upload.error} />
      </Panel>
      {draft.source && (
        <QueryState query={profile}>
          {(p) => (
            <>
              {workbookSheets(p.sheets).length > 0 && (
                <SheetPicker
                  key={workbookSheets(p.sheets)
                    .map((s) => `${s.name}:${s.selected}`)
                    .join("|")}
                  draft={draft}
                  sheets={workbookSheets(p.sheets)}
                />
              )}
              <Panel title={t("studio.upload.profile")}>
                <p className="muted">
                  {t("studio.upload.rows", { count: p.rowCount })}
                </p>
                {p.rowsSampled != null && p.rowsTotal != null && p.rowsSampled < p.rowsTotal && (
                  <p className="muted" data-testid="profile-sampled">
                    {t("studio.upload.sampled", {
                      sampled: number(p.rowsSampled),
                      total: number(p.rowsTotal),
                    })}
                  </p>
                )}
                <DataTable
                  caption={t("studio.upload.profile")}
                  testId="profile-table"
                  rows={p.columns}
                  rowKey={(c) => c.name ?? ""}
                  columns={[
                    {
                      key: "column",
                      header: t("studio.upload.col.column"),
                      render: (c) => <code>{c.name}</code>,
                    },
                    {
                      key: "type",
                      header: t("studio.upload.col.type"),
                      render: (c) => c.inferredType,
                    },
                    {
                      key: "date",
                      header: t("studio.upload.col.dateFormat"),
                      render: (c) => c.dateFormat ?? "—",
                    },
                    {
                      key: "blanks",
                      header: t("studio.upload.col.blanks"),
                      render: (c) => c.blanks,
                      align: "end",
                    },
                    {
                      key: "distinct",
                      header: t("studio.upload.col.distinct"),
                      render: (c) => c.distinct,
                      align: "end",
                    },
                    {
                      key: "top",
                      header: t("studio.upload.col.top"),
                      render: (c) => (
                        <span className={styles.values}>
                          {c.topValues
                            .slice(0, 5)
                            .map((v) => `${v.value} (${v.count})`)
                            .join(", ")}
                        </span>
                      ),
                    },
                  ]}
                />
              </Panel>
              <Panel title={t("studio.upload.samples")}>
                <div style={{ overflowX: "auto" }}>
                  <table className={styles.table}>
                    <thead>
                      <tr>
                        {p.columns.map((c) => (
                          <th key={c.name} scope="col">
                            {c.name}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {p.sampleRows.map((row, index) => (
                        <tr key={index}>
                          {p.columns.map((c) => (
                            <td key={c.name}>{row[c.name ?? ""]}</td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Panel>
            </>
          )}
        </QueryState>
      )}
      <StepFooter
        onBack={onBack}
        onContinue={onDone}
        disabled={!draft.source}
      />
    </div>
  );
}
