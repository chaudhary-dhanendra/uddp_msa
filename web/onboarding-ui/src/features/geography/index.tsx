import { useEffect, useState, type DragEvent } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, csrfToken, ensureCsrfToken, unwrap } from "../../api/client";
import type { GeographyChange, GeographyPreview, GeographySyncRun } from "../../api/types";
import { useI18n } from "../../i18n/i18n";
import { Button, PageHeader, Panel, QueryState } from "../../shared/components";
import { useSession } from "../auth";
import styles from "./geography.module.css";

const CHANGE_TYPES = ["ADDED", "RENAMED", "REPARENTED", "INACTIVATED", "REACTIVATED"] as const;
const POLL_MS = 1000;
const STATUS_KEY = ["geography-master", "status"];
const RUNS_KEY = ["geography-master", "runs"];

const sizeOf = (bytes: number) =>
  bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;

/** A multipart POST (openapi-fetch serialises JSON only); a refusal carries the server's message. */
async function post<T>(url: string, file: File, extra: Record<string, string> = {}): Promise<T> {
  const form = new FormData();
  form.append("file", file);
  Object.entries(extra).forEach(([key, value]) => form.append(key, value));
  await ensureCsrfToken();
  const response = await fetch(url, { method: "POST", body: form, headers: { "X-XSRF-TOKEN": csrfToken() ?? "" } });
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const body = (await response.json()) as { message?: string };
      message = body.message ?? message;
    } catch {
      // no JSON body: the status text above is the message
    }
    throw Object.assign(new Error(message), { status: response.status });
  }
  return (await response.json()) as T;
}

function Counts({ title, counts }: { title: string; counts: Record<string, number> }) {
  return (
    <table>
      <caption>{title}</caption>
      <tbody>
        {Object.entries(counts).map(([type, count]) => (
          <tr key={type}>
            <th scope="row">{type}</th>
            <td>{count}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Diff({ report }: { report: GeographyPreview }) {
  const { t } = useI18n();
  const types = Object.keys(report.changesByType);
  if (types.length === 0) {
    return <p data-testid="geo-diff-none">{t("geography.diff.none")}</p>;
  }
  return (
    <div className={styles.tableScroll}>
      <table data-testid="geo-diff">
        <caption>{t("geography.diff.title")}</caption>
        <thead>
          <tr>
            <th scope="col">{t("geography.type")}</th>
            {CHANGE_TYPES.map((kind) => (
              <th scope="col" key={kind}>{t(`geography.change.${kind}`)}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {types.map((type) => (
            <tr key={type}>
              <th scope="row">{type}</th>
              {CHANGE_TYPES.map((kind) => (
                <td key={kind}>{report.changesByType[type]?.[kind] ?? 0}</td>
              ))}
            </tr>
          ))}
          <tr>
            <th scope="row">{t("geography.total")}</th>
            {CHANGE_TYPES.map((kind) => (
              <td key={kind}>{report.changeTotals[kind] ?? 0}</td>
            ))}
          </tr>
        </tbody>
      </table>
      {CHANGE_TYPES.filter((kind) => (report.samples[kind] ?? []).length > 0).map((kind) => (
        <details key={kind}>
          <summary>{t("geography.samples", { kind: t(`geography.change.${kind}`), count: report.samples[kind]?.length ?? 0 })}</summary>
          <ul>
            {(report.samples[kind] as GeographyChange[]).map((change) => (
              <li key={`${change.entityType}-${change.code}-${kind}`}>
                {change.entityType} {change.code}
                {change.oldValue ? ` · ${change.oldValue} → ${change.newValue ?? ""}` : change.newValue ? ` · ${change.newValue}` : ""}
              </li>
            ))}
          </ul>
        </details>
      ))}
    </div>
  );
}

function ErrorTable({ report }: { report: GeographyPreview }) {
  const { t } = useI18n();
  return (
    <div role="alert" data-testid="geo-errors">
      <p>
        {report.errorsTruncated
          ? t("geography.errors.many", { count: report.errors.length })
          : t("geography.errors.some", { count: report.errors.length })}
      </p>
      <table className={styles.errors}>
        <thead>
          <tr>
            <th scope="col">{t("geography.errors.line")}</th>
            <th scope="col">{t("geography.errors.message")}</th>
          </tr>
        </thead>
        <tbody>
          {report.errors.map((issue, index) => (
            <tr key={`${issue.line}-${index}`}>
              <td>{issue.line}</td>
              <td>{issue.message}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ConfirmDialog({ file, onConfirm, onCancel, busy }: { file: File; onConfirm: () => void; onCancel: () => void; busy: boolean }) {
  const { t } = useI18n();
  useEffect(() => {
    const close = (event: KeyboardEvent) => event.key === "Escape" && onCancel();
    document.addEventListener("keydown", close);
    return () => document.removeEventListener("keydown", close);
  }, [onCancel]);
  return (
    <div className={styles.dialogBackdrop}>
      <div role="dialog" aria-modal="true" aria-labelledby="geo-confirm-heading" className={styles.dialog} data-testid="geo-confirm">
        <h2 id="geo-confirm-heading">{t("geography.confirm.title", { name: file.name })}</h2>
        <p>{t("geography.confirm.body")}</p>
        <div className="row">
          <Button autoFocus onClick={onConfirm} disabled={busy}>{t("geography.confirm.yes")}</Button>
          <Button variant="secondary" onClick={onCancel}>{t("geography.confirm.no")}</Button>
        </div>
      </div>
    </div>
  );
}

function RunResult({ run }: { run: GeographySyncRun }) {
  const { t } = useI18n();
  const failed = run.status === "FAILED";
  return (
    <p role={failed ? "alert" : "status"} data-testid="geo-result">
      {run.status === "RUNNING"
        ? t("geography.run.running")
        : failed
          ? t("geography.run.failed", { error: run.error ?? "" })
          : run.status === "UNCHANGED"
            ? t("geography.run.unchanged")
            : t("geography.run.succeeded", { count: run.changeCount })}
    </p>
  );
}

function CurrentMaster() {
  const { t } = useI18n();
  const status = useQuery({ queryKey: STATUS_KEY, queryFn: () => unwrap(api.GET("/api/geography/sync/status")) });
  const types = useQuery({ queryKey: ["geography-master", "types"], queryFn: () => unwrap(api.GET("/api/geography/entity-types")) });
  const counts = useQueries({
    queries: (types.data ?? []).map((type) => ({
      queryKey: ["geography-master", "count", type.entityType, status.data?.activeSnapshotId],
      queryFn: () => unwrap(api.GET("/api/geography/entities", { params: { query: { type: type.entityType ?? "", page: 0, size: 1 } } })),
    })),
  });
  const runs = useQuery({ queryKey: RUNS_KEY, queryFn: () => unwrap(api.GET("/api/geography/sync/runs", { params: { query: { page: 0, size: 10 } } })) });
  return (
    <Panel title={t("geography.current.title")} id="geo-current">
      <QueryState query={status}>
        {(s) => (
          <p data-testid="geo-active">
            {s.activeSnapshotId
              ? t("geography.current.active", { id: s.activeSnapshotId, at: s.activatedAt ?? "—", total: s.entityCount })
              : t("geography.current.none")}
          </p>
        )}
      </QueryState>
      <table data-testid="geo-type-counts">
        <caption>{t("geography.current.byType")}</caption>
        <tbody>
          {(types.data ?? []).map((type, index) => (
            <tr key={type.entityType}>
              <th scope="row">{type.entityType}</th>
              <td>{counts[index]?.data?.totalItems ?? "…"}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <h3>{t("geography.current.runs")}</h3>
      <QueryState query={runs} isEmpty={(page) => page.items.length === 0} emptyText={t("geography.current.noRuns")}>
        {(page) => (
          <div className={styles.tableScroll}>
            <table data-testid="geo-runs">
              <thead>
                <tr>
                  <th scope="col">{t("geography.runs.started")}</th>
                  <th scope="col">{t("geography.runs.trigger")}</th>
                  <th scope="col">{t("geography.runs.source")}</th>
                  <th scope="col">{t("geography.runs.actor")}</th>
                  <th scope="col">{t("geography.runs.status")}</th>
                  <th scope="col">{t("geography.runs.changes")}</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((run) => (
                  <tr key={run.runId}>
                    <td>{run.startedAt}</td>
                    <td>{run.triggerKind}</td>
                    <td>{run.sourceLabel ?? "—"}</td>
                    <td>{run.actor}</td>
                    <td>{run.status}</td>
                    <td>{run.changeCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </QueryState>
    </Panel>
  );
}

/** Platform Admin: check an LGD snapshot file, then load it into the geography master (adds and updates only). */
export function GeographyMasterPage() {
  const { t } = useI18n();
  const session = useSession();
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [runId, setRunId] = useState<string | null>(null);

  const check = useMutation({ mutationFn: (chosen: File) => post<GeographyPreview>("/api/geography/snapshots/validate", chosen) });
  const load = useMutation({
    mutationFn: (chosen: File) => post<GeographySyncRun>("/api/geography/snapshots", chosen, { confirm: "true" }),
    onSuccess: (run) => {
      setRunId(run.runId);
      void queryClient.invalidateQueries({ queryKey: ["geography-master"] });
    },
  });
  const runs = useQuery({
    queryKey: RUNS_KEY,
    queryFn: () => unwrap(api.GET("/api/geography/sync/runs", { params: { query: { page: 0, size: 10 } } })),
    refetchInterval: (query) =>
      runId && query.state.data?.items.find((run) => run.runId === runId)?.status === "RUNNING" ? POLL_MS : false,
  });
  const run = runId ? runs.data?.items.find((item) => item.runId === runId) ?? load.data : undefined;
  const finished = run !== undefined && run.status !== "RUNNING";
  useEffect(() => {
    if (finished) {
      void queryClient.invalidateQueries({ queryKey: ["geography-master"] });
    }
  }, [finished, queryClient]);

  if (!session.can("GEOGRAPHY_UPLOAD")) {
    return (
      <>
        <PageHeader title={t("geography.title")} />
        <p role="alert">{t("geography.forbidden")}</p>
      </>
    );
  }

  const choose = (chosen: File | null | undefined) => {
    setFile(chosen ?? null);
    setRunId(null);
    check.reset();
    load.reset();
  };
  const drop = (event: DragEvent) => {
    event.preventDefault();
    setDragging(false);
    choose(event.dataTransfer.files[0]);
  };
  const report = check.data;
  const canLoad = file !== null && report !== undefined && report.errors.length === 0 && !load.isPending && run?.status !== "RUNNING";

  return (
    <>
      <PageHeader title={t("geography.title")} description={t("geography.description")} />
      <Panel title={t("geography.step1")} id="geo-step1">
        <div
          className={`${styles.dropzone} ${dragging ? styles.dropzoneActive : ""}`}
          onDragOver={(event) => { event.preventDefault(); setDragging(true); }}
          onDragLeave={() => setDragging(false)}
          onDrop={drop}
          data-testid="geo-dropzone"
        >
          <label htmlFor="geo-file">{t("geography.choose")}</label>
          <input id="geo-file" type="file" accept=".csv,.json,text/csv,application/json" className={styles.fileInput}
            aria-describedby="geo-format" onChange={(event) => choose(event.target.files?.[0])} />
          {file && <p data-testid="geo-file">{file.name} · {sizeOf(file.size)}</p>}
        </div>
        <p id="geo-format" className="muted">
          {t("geography.format")} <a href="/api/geography/snapshots/template" download>{t("geography.template")}</a>
        </p>
      </Panel>

      <Panel title={t("geography.step2")} id="geo-step2">
        <Button onClick={() => file && check.mutate(file)} disabled={!file || check.isPending}>{t("geography.check")}</Button>
        {check.isPending && <p role="status">{t("geography.checking")}</p>}
        {check.error && <p role="alert">{check.error.message}</p>}
        {report && (
          <div className="stack" data-testid="geo-report">
            <p>{t("geography.report.rows", { count: report.rows })}</p>
            <Counts title={t("geography.report.byType")} counts={report.rowsByType} />
            {report.errors.length > 0 ? <ErrorTable report={report} /> : <Diff report={report} />}
          </div>
        )}
      </Panel>

      <Panel title={t("geography.step3")} id="geo-step3">
        <Button onClick={() => setConfirming(true)} disabled={!canLoad}>{t("geography.load")}</Button>
        {report && report.errors.length > 0 && <p className="muted">{t("geography.loadBlocked")}</p>}
        {load.error && <p role="alert">{load.error.message}</p>}
        {run && <RunResult run={run} />}
      </Panel>
      {confirming && file && (
        <ConfirmDialog
          file={file}
          busy={load.isPending}
          onCancel={() => setConfirming(false)}
          onConfirm={() => { setConfirming(false); load.mutate(file); }}
        />
      )}
      <CurrentMaster />
    </>
  );
}
