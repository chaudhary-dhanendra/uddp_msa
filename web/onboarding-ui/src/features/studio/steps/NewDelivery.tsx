import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { csrfToken, ensureCsrfToken } from "../../../api/client";
import type { StudioDraft } from "../../../api/types";
import { useI18n } from "../../../i18n/i18n";
import { Button, Panel } from "../../../shared/components";
import { studioKeys } from "../hooks";

/**
 * Track C1: a new delivery of a published file source. The file replaces the landing file; the next onboarding run
 * reports new, changed, unchanged and removed rows in the run history.
 */
export function NewDeliveryPanel({ draft, onDelivered }: { draft: StudioDraft; onDelivered: () => void }) {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  if (draft.published == null || draft.source?.sourceType !== "file") {
    return null;
  }
  const upload = async () => {
    if (!file) {
      setError(t("studio.delivery.choose"));
      return;
    }
    setError(null); setBusy(true); setDone(false);
    try {
      const form = new FormData();
      form.append("file", file);
      // Multipart upload: openapi-fetch serialises JSON bodies only, so this call sends the CSRF token itself.
      await ensureCsrfToken();
      const response = await fetch(`/api/studio/drafts/${encodeURIComponent(draft.draftId ?? "")}/delivery`,
        { method: "POST", body: form, headers: { "X-XSRF-TOKEN": csrfToken() ?? "" } });
      if (!response.ok) {
        const body = await response.json().catch(() => null) as { message?: string } | null;
        throw new Error(body?.message ?? t("studio.delivery.failed"));
      }
      await queryClient.invalidateQueries({ queryKey: studioKeys.draft(draft.draftId ?? "") });
      setDone(true);
      onDelivered();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("studio.delivery.failed"));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Panel title={t("studio.delivery.title")}>
      <p className="muted">{t("studio.delivery.intro")}</p>
      <input aria-label={t("studio.delivery.file")} type="file" accept=".csv,.xlsx" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      <Button onClick={() => void upload()} disabled={busy} data-testid="upload-delivery">{t("studio.delivery.upload")}</Button>
      {error && <p role="alert">{error}</p>}
      {done && <p role="status">{t("studio.delivery.done")}</p>}
    </Panel>
  );
}
