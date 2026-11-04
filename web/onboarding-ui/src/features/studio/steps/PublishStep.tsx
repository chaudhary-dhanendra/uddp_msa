import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../../../api/client";
import type { StudioDraft } from "../../../api/types";
import { useI18n } from "../../../i18n/i18n";
import { Button, Panel, QueryState } from "../../../shared/components";
import { refreshDraft, useGenerated, usePreview } from "../hooks";
import { ErrorNote, StepFooter } from "./StepFooter";
import { ApprovalActions } from "../approvals";
import styles from "../studio.module.css";

/** Step 7: the generated State Pack YAML (read-only) and the Publish action, blocked on blocking errors. */
export function PublishStep({
  draft,
  onBack,
  onDone,
}: {
  draft: StudioDraft;
  onBack: () => void;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const draftId = draft.draftId ?? "";
  const generated = useGenerated(draftId);
  const preview = usePreview(draftId);
  const blocked =
    (preview.data?.blockingErrors ?? 0) > 0 || preview.data?.packError != null;
  const publish = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/studio/drafts/{draftId}/publish", {
          params: { path: { draftId } },
        }),
      ),
    onSuccess: (saved) => refreshDraft(queryClient, draftId, saved),
  });
  const current = publish.data ?? draft;
  const published = current.published ? current : null;
  const pending = current.status === "PENDING_APPROVAL";
  return (
    <div className="stack">
      <Panel
        title={t("studio.steps.8")}
        actions={
          <Button
            onClick={() => publish.mutate()}
            disabled={
              blocked || pending || publish.isPending || preview.isPending
            }
            data-testid="publish"
          >
            {publish.isPending
              ? t("studio.publish.publishing")
              : t("studio.publish.submit")}
          </Button>
        }
      >
        <p className="muted">{t("studio.publish.intro")}</p>
        {blocked && (
          <p className={styles.error} role="alert">
            {t("studio.publish.blocked")}
          </p>
        )}
        {pending && (
          <p
            className={styles.notice}
            role="status"
            data-testid="pending-approval"
          >
            {t("studio.publish.pending")}
          </p>
        )}
        {current.approval && <ApprovalActions approval={current.approval} />}
        {published?.published && !pending && (
          <p className={styles.ok} role="status" data-testid="published">
            {t("studio.publish.published", {
              version: published.published.version,
            })}
          </p>
        )}
        <ErrorNote error={publish.error} />
      </Panel>
      <QueryState query={generated}>
        {(pack) => (
          <Panel
            title={t("studio.publish.files", { count: pack.files.length })}
          >
            {pack.files.map((file) => (
              <div key={file.path}>
                <h3>
                  <code>
                    {pack.packId}/{file.path}
                  </code>
                </h3>
                <pre
                  className={styles.yaml}
                  aria-label={file.path ?? ""}
                  tabIndex={0}
                >
                  {file.content}
                </pre>
              </div>
            ))}
          </Panel>
        )}
      </QueryState>
      <StepFooter
        onBack={onBack}
        onContinue={onDone}
        disabled={!published?.published}
      />
    </div>
  );
}
