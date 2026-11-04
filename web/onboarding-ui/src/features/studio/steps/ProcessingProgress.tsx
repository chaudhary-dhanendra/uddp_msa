import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ensureCsrfToken, csrfToken } from "../../../api/client";
import type { StudioDraft } from "../../../api/types";
import { useI18n } from "../../../i18n/i18n";
import { Button, Panel } from "../../../shared/components";

interface Chunk {
  chunk: number;
  rows: number;
  mapped: number;
  skipped: number;
  rejected: number;
  rowsPerSecond: number;
}

interface Processing {
  totalRows: number;
  rowsProcessed: number;
  rowsPerSecond: number;
  rejectedRows: number;
  chunks: Chunk[];
}

interface DeliveryChanges {
  new: number;
  changed: number;
  unchanged: number;
  removed: number;
}

interface RunDetail {
  status: string;
  processing: Processing | null;
  /** Compared with the previous delivery of the same source, per source record (null for the first delivery). */
  deliveryChanges?: DeliveryChanges | null;
}

/**
 * Per-chunk progress of the run's mapping: rows done of the delivery, speed, rejected rows and their CSV. Reads the
 * onboarding control-plane run, which carries the chunk table.
 */
export function ProcessingProgressPanel({ runId }: { runId: string }) {
  const { t, number } = useI18n();
  const run = useQuery({
    queryKey: ["onboarding", "processing", runId],
    queryFn: async (): Promise<RunDetail> => {
      const response = await globalThis.fetch(`/api/onboarding/runs/${runId}`, { credentials: "same-origin" });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      return (await response.json()) as RunDetail;
    },
    refetchInterval: (query) => (query.state.data?.status === "RUNNING" ? 1000 : false),
  });
  const processing = run.data?.processing;
  if (!processing || processing.chunks.length === 0) {
    return null;
  }
  const percent = processing.totalRows > 0 ? Math.min(100, (processing.rowsProcessed / processing.totalRows) * 100) : 0;
  return (
    <Panel title={t("studio.onboard.progress.title")}>
      <progress max={100} value={percent} aria-label={t("studio.onboard.progress.title")} data-testid="processing-bar" />
      <p data-testid="processing-summary">
        {t("studio.onboard.progress.rows", {
          done: number(processing.rowsProcessed),
          total: number(processing.totalRows),
        })}
        {" · "}
        {t("studio.onboard.progress.speed", { speed: number(processing.rowsPerSecond) })}
        {" · "}
        {t(processing.rejectedRows === 1 ? "studio.onboard.progress.rejectedOne" : "studio.onboard.progress.rejected", {
          count: number(processing.rejectedRows),
        })}
      </p>
      {run.data?.deliveryChanges && (
        <p data-testid="delivery-changes">
          {t("studio.onboard.progress.changes", {
            new: number(run.data.deliveryChanges.new),
            changed: number(run.data.deliveryChanges.changed),
            unchanged: number(run.data.deliveryChanges.unchanged),
            removed: number(run.data.deliveryChanges.removed),
          })}
        </p>
      )}
      {processing.rejectedRows > 0 && (
        <p>
          <a href={`/api/onboarding/runs/${runId}/rejected-rows.csv`} download data-testid="rejected-download">
            {t("studio.onboard.progress.download")}
          </a>
        </p>
      )}
      <table data-testid="chunk-table">
        <thead>
          <tr>
            <th>{t("studio.onboard.progress.chunk")}</th>
            <th>{t("studio.onboard.progress.chunkRows")}</th>
            <th>{t("studio.onboard.progress.chunkMapped")}</th>
            <th>{t("studio.onboard.progress.chunkSkipped")}</th>
            <th>{t("studio.onboard.progress.chunkRejected")}</th>
            <th>{t("studio.onboard.progress.chunkSpeed")}</th>
          </tr>
        </thead>
        <tbody>
          {processing.chunks.map((c) => (
            <tr key={c.chunk}>
              <td>{c.chunk}</td>
              <td>{number(c.rows)}</td>
              <td>{number(c.mapped)}</td>
              <td>{number(c.skipped)}</td>
              <td>{number(c.rejected)}</td>
              <td>{number(c.rowsPerSecond)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">{t("studio.onboard.progress.legend")}</p>
    </Panel>
  );
}

interface Landing {
  location: string;
  waiting: string[];
}

/** Starts a run over the oldest file waiting in a landing-folder source's folder; hidden for other sources. */
export function LandingFolderPanel({ draft, onStarted }: { draft: StudioDraft; onStarted: (runId: string) => void }) {
  const { t } = useI18n();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const packId = draft.packId;
  const sourceId = draft.source?.sourceId;
  const base = `/api/onboarding/state-packs/${packId}/sources/${sourceId}/landing`;
  const landing = useQuery({
    queryKey: ["onboarding", "landing", packId, sourceId],
    queryFn: async (): Promise<Landing | null> => {
      const response = await globalThis.fetch(base, { credentials: "same-origin" });
      return response.ok ? ((await response.json()) as Landing) : null;
    },
    // A source that reads one file (every Studio upload) has no landing folder: the endpoint answers 400 for it.
    enabled: draft.published != null && !!packId && !!sourceId && !/\.[a-z0-9]+$/i.test(draft.source?.location ?? ""),
    refetchInterval: (query) => (query.state.data === null ? false : 5000),
  });
  if (!landing.data) {
    return null;
  }
  const start = async () => {
    setError(null);
    setBusy(true);
    try {
      await ensureCsrfToken();
      const response = await globalThis.fetch(`${base}/start`, {
        method: "POST",
        credentials: "same-origin",
        headers: { "X-XSRF-TOKEN": csrfToken() ?? "" },
      });
      const body = (await response.json()) as { runId?: string; message?: string };
      if (!response.ok || !body.runId) {
        throw new Error(body.message ?? `HTTP ${response.status}`);
      }
      onStarted(body.runId);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  const waiting = landing.data.waiting;
  return (
    <Panel
      title={t("studio.onboard.landing.title")}
      actions={
        <Button onClick={() => void start()} disabled={busy || waiting.length === 0} data-testid="start-landing">
          {t("studio.onboard.landing.start")}
        </Button>
      }
    >
      <p className="muted">{t("studio.onboard.landing.intro", { location: landing.data.location })}</p>
      <p data-testid="landing-waiting">
        {waiting.length === 0
          ? t("studio.onboard.landing.none")
          : t("studio.onboard.landing.waiting", { count: waiting.length, files: waiting.join(", ") })}
      </p>
      {error && <p role="alert">{error}</p>}
    </Panel>
  );
}
