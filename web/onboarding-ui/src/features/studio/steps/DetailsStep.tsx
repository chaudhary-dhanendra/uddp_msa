import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../../../api/client";
import type { DraftDetails, StudioDraft } from "../../../api/types";
import { useI18n } from "../../../i18n/i18n";
import { Field, Panel, TextInput } from "../../../shared/components";
import { refreshDraft, studioKeys } from "../hooks";
import { ErrorNote, StepFooter } from "./StepFooter";

/** Step 1: pack ID, state code and display name (creates the draft, or edits it). */
export function DetailsStep({
  draft,
  onDone,
}: {
  draft: StudioDraft | null;
  onDone: (draft: StudioDraft) => void;
}) {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const [packId, setPackId] = useState(draft?.packId ?? "");
  const [stateCode, setStateCode] = useState(draft?.stateCode ?? "");
  const [displayName, setDisplayName] = useState(draft?.displayName ?? "");
  const details: DraftDetails = { packId, stateCode, displayName };
  const save = useMutation({
    mutationFn: () =>
      draft?.draftId
        ? unwrap(
            api.PUT("/api/studio/drafts/{draftId}/details", {
              params: { path: { draftId: draft.draftId } },
              body: details,
            }),
          )
        : unwrap(api.POST("/api/studio/drafts", { body: details })),
    onSuccess: (saved) => {
      refreshDraft(queryClient, saved.draftId ?? "", saved);
      void queryClient.invalidateQueries({ queryKey: studioKeys.states });
      onDone(saved);
    },
  });
  return (
    <Panel title={t("studio.steps.1")}>
      <div className="grid-2">
        <Field
          id="pack-id"
          label={t("studio.details.packId")}
          hint={t("studio.details.packIdHint")}
        >
          <TextInput
            id="pack-id"
            value={packId}
            onChange={(e) => setPackId(e.target.value.toLowerCase())}
            disabled={draft?.published != null}
          />
        </Field>
        <Field
          id="state-code"
          label={t("studio.details.stateCode")}
          hint={t("studio.details.stateCodeHint")}
        >
          <TextInput
            id="state-code"
            value={stateCode}
            onChange={(e) => setStateCode(e.target.value.toUpperCase())}
          />
        </Field>
        <Field id="display-name" label={t("studio.details.displayName")}>
          <TextInput
            id="display-name"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
          />
        </Field>
      </div>
      <ErrorNote error={save.error} />
      <StepFooter
        onContinue={() => save.mutate()}
        continueLabel={draft ? undefined : t("studio.details.create")}
        disabled={!packId || !stateCode || !displayName}
        busy={save.isPending}
      />
    </Panel>
  );
}
