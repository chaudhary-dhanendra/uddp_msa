import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../../../api/client";
import type { StudioDraft } from "../../../api/types";
import { useI18n } from "../../../i18n/i18n";
import { Button, Panel, StageTimeline } from "../../../shared/components";
import { studioKeys, useStudioStatus } from "../hooks";
import { ErrorNote, StepFooter } from "./StepFooter";
import { NewDeliveryPanel } from "./NewDelivery";
import { LandingFolderPanel, ProcessingProgressPanel } from "./ProcessingProgress";
import styles from "../studio.module.css";

/** Stages whose input and output counts have their own wording; the others read "in N · out M". */
const STAGE_COUNTS = new Set(["INGESTED", "MAPPED", "DQ_CHECKED", "MATCHED", "GOLDEN_BUILT"]);

function stageCountsKey(stage: string | null | undefined): string {
  return stage && STAGE_COUNTS.has(stage) ? `studio.onboard.counts.${stage}` : "studio.onboard.counts.other";
}

/** Step 8: start the run and follow its stages live (the existing onboarding orchestrator does the work). */
export function OnboardStep({
  draft,
  onBack,
  onDone,
}: {
  draft: StudioDraft;
  onBack: () => void;
  onDone: () => void;
}) {
  const { t, number, duration } = useI18n();
  const queryClient = useQueryClient();
  const draftId = draft.draftId ?? "";
  const status = useStudioStatus();
  const [runId, setRunId] = useState<string | null>(draft.lastRunId);
  const start = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/studio/drafts/{draftId}/onboard", {
          params: { path: { draftId } },
          body: { processingMode: null },
        }),
      ),
    onSuccess: (started) => {
      setRunId(started.runId);
      void queryClient.invalidateQueries({ queryKey: studioKeys.states });
    },
  });
  const run = useQuery({
    queryKey: ["studio", "run", runId],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/onboarding-runs/{runId}", {
          params: { path: { runId: runId ?? "" } },
        }),
      ),
    enabled: runId != null,
    refetchInterval: (query) => {
      const current = query.state.data?.run?.status;
      // A paused run waits for someone else's release, so keep looking, more slowly.
      return current === "RUNNING" ? 1000 : current === "PAUSED" ? 5000 : false;
    },
  });
  const runStatus = run.data?.run?.status;
  const finished = runStatus === "COMPLETED";
  if (finished) {
    void queryClient.invalidateQueries({ queryKey: studioKeys.draft(draftId) });
  }
  const mode = status.data?.goldenDataExists
    ? t("studio.onboard.modeIncremental")
    : t("studio.onboard.modeFull");
  const failedStage = run.data?.timeline.find((s) => s.status === "FAILED");
  return (
    <div className="stack">
      <Panel
        title={t("studio.steps.9")}
        actions={
          <Button
            onClick={() => start.mutate()}
            disabled={
              start.isPending ||
              runStatus === "RUNNING" ||
              runStatus === "PAUSED" ||
              finished ||
              draft.published == null
            }
            data-testid="start-onboarding"
          >
            {start.isPending
              ? t("studio.onboard.starting")
              : t("studio.onboard.start")}
          </Button>
        }
      >
        <p className="muted">{t("studio.onboard.intro")}</p>
        {!runId && <p>{t("studio.onboard.mode", { mode })}</p>}
        <ErrorNote error={start.error} />
        {finished && (
          <p
            className={styles.ok}
            role="status"
            data-testid="onboarding-completed"
          >
            {t("studio.onboard.completed")}
          </p>
        )}
        {runStatus === "PAUSED" && (
          <p role="status" data-testid="onboarding-paused">
            {t("studio.onboard.paused")}{" "}
            <Link to={`/onboarding-runs/${runId}`}>{t("studio.onboard.openRun")}</Link>
          </p>
        )}
        {runStatus === "FAILED" && (
          <p className={styles.error} role="alert">
            {t("studio.onboard.failed", {
              stage: failedStage?.stage ?? "",
              message: run.data?.run?.failureMessage ?? "",
            })}
          </p>
        )}
      </Panel>
      <NewDeliveryPanel draft={draft} onDelivered={() => setRunId(null)} />
      <LandingFolderPanel draft={draft} onStarted={setRunId} />
      {runId && <ProcessingProgressPanel runId={runId} />}
      {run.data && (
        <Panel title={t("studio.onboard.timeline")}>
          <StageTimeline
            label={t("studio.onboard.timeline")}
            testId="stage-timeline"
            items={run.data.timeline.map((stage) => ({
              key: stage.stage ?? "",
              title: stage.stage,
              status: stage.status ?? "PENDING",
              meta:
                stage.latest?.inputCount != null
                  ? `${t(stageCountsKey(stage.stage), {
                      in: number(stage.latest.inputCount),
                      out: number(stage.latest.outputCount ?? 0),
                    })} · ${duration(stage.latest.durationMs)}`
                  : undefined,
              error: stage.latest?.errorMessage,
            }))}
          />
        </Panel>
      )}
      <StepFooter onBack={onBack} onContinue={onDone} disabled={!finished} />
    </div>
  );
}

/** Step 9: the result, with links to the platform views and the portability evidence. */
export function DoneStep({ draft }: { draft: StudioDraft }) {
  const { t } = useI18n();
  return (
    <Panel title={t("studio.done.title", { state: draft.displayName ?? "" })}>
      <p>{t("studio.done.intro")}</p>
      <div className={styles.links}>
        <Link className="buttonLink" to="/">
          {t("studio.done.overview")}
        </Link>
        <Link
          className="buttonLink"
          to="/golden-residents"
          data-testid="done-golden"
        >
          {t("studio.done.golden")}
        </Link>
        <Link
          className="buttonLink"
          to="/onboarding-studio/portability"
          data-testid="done-portability"
        >
          {t("studio.done.portability")}
        </Link>
        <Link to="/onboarding-studio">{t("studio.done.states")}</Link>
      </div>
    </Panel>
  );
}
