import { useEffect, useState } from "react";
import { api, csrfToken, ensureCsrfToken } from "../../api/client";
import type { AiRuleDraft } from "../../api/types";

/**
 * "Draft from GO": shown only when AI drafting is enabled on the backend. The result is a DRAFT eligibility rule set
 * marked "AI draft - verify against GO"; it still needs submitting and approval by another user.
 */
export function GoDraftButton({ schemeId, versionId, onDrafted }: { schemeId: string; versionId: string; onDrafted?: (draft: AiRuleDraft) => void }) {
  const [enabled, setEnabled] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [draft, setDraft] = useState<AiRuleDraft | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void api.GET("/api/scheme-packs/ai-draft/status")
      .then(({ data }) => setEnabled(data?.enabled === true))
      .catch(() => setEnabled(false));
  }, []);

  if (!enabled) return null;

  const run = async () => {
    if (!file) { setError("Choose the GO PDF first."); return; }
    setError(""); setBusy(true);
    try {
      const form = new FormData();
      form.append("file", file);
      // Multipart upload: openapi-fetch serialises JSON bodies only, so this call sends the CSRF token itself.
      await ensureCsrfToken();
      const response = await fetch(`/api/scheme-packs/${encodeURIComponent(schemeId)}/versions/${encodeURIComponent(versionId)}/eligibility/ai-draft`,
        { method: "POST", body: form, headers: { "X-XSRF-TOKEN": csrfToken() ?? "" } });
      if (!response.ok) {
        const body = await response.json().catch(() => null) as { message?: string } | null;
        throw new Error(body?.message ?? "Unable to draft from the GO");
      }
      const created = await response.json() as AiRuleDraft;
      setDraft(created); onDrafted?.(created);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Unable to draft from the GO");
    } finally {
      setBusy(false);
    }
  };

  return <div>
    <input aria-label="GO PDF" type="file" accept="application/pdf" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
    <button disabled={busy} onClick={() => void run()}>Draft from GO</button>
    {error && <p role="alert">{error}</p>}
    {draft && <section>
      <p><strong>{draft.marker}</strong> · version {draft.version} · {draft.status}</p>
      <ul>{draft.rows?.map((row, index) => <li key={index}>{row.condition} → {row.output} ({row.clauseReference})</li>)}</ul>
    </section>}
  </div>;
}
