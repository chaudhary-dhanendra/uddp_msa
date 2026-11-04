import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type { GoldenResident360 } from "../../api/types";
import { useI18n } from "../../i18n/i18n";
import { Button, Field, Select, TextInput } from "../../shared/components";
import { useSession } from "../auth";

/** The header that asks the backend for one unmasked, audited response (RESIDENT_VIEW_FULL only). */
export const VIEW_PURPOSE_HEADER = "X-UDDP-View-Purpose";

/**
 * Pages show masked personal data; a user with RESIDENT_VIEW_FULL can reveal it for one view by choosing a purpose
 * (and, where the reveal records one, a note). `reveal` loads the unmasked data; the reveal is audited and reloading
 * the page masks the data again.
 */
export function PurposeRevealButton<T>({
  reveal,
  onRevealed,
  withNote = false,
}: {
  reveal: (purpose: string, note: string | null) => Promise<T>;
  onRevealed: (data: T) => void;
  withNote?: boolean;
}) {
  const { t } = useI18n();
  const session = useSession();
  const [open, setOpen] = useState(false);
  const [purpose, setPurpose] = useState("");
  const [note, setNote] = useState("");
  const allowed = session.can("RESIDENT_VIEW_FULL");
  const purposes = useQuery({
    queryKey: ["privacy", "reveal-purposes"],
    queryFn: () => unwrap(api.GET("/api/v1/privacy/reveal-purposes")),
    enabled: allowed && open,
  });
  const mutation = useMutation({
    mutationFn: () => reveal(purpose, note.trim() || null),
    onSuccess: (data) => {
      setOpen(false);
      setNote("");
      onRevealed(data);
    },
  });
  if (!allowed) {
    return null;
  }
  if (!open) {
    return (
      <Button
        variant="secondary"
        onClick={() => setOpen(true)}
        data-testid="reveal"
      >
        {t("reveal.button")}
      </Button>
    );
  }
  return (
    <div
      role="dialog"
      aria-label={t("reveal.title")}
      className="row"
      data-testid="reveal-dialog"
    >
      <Field id="reveal-purpose" label={t("reveal.purpose")}>
        <Select
          id="reveal-purpose"
          value={purpose}
          onChange={(e) => setPurpose(e.target.value)}
          options={[
            { value: "", label: t("reveal.choose") },
            ...(purposes.data ?? []).map((value) => ({ value, label: value })),
          ]}
        />
      </Field>
      {withNote && (
        <Field id="reveal-note" label={t("reveal.note")}>
          <TextInput
            id="reveal-note"
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </Field>
      )}
      <Button
        onClick={() => mutation.mutate()}
        disabled={purpose === "" || mutation.isPending}
        data-testid="reveal-confirm"
      >
        {t("reveal.confirm")}
      </Button>
      <Button variant="secondary" onClick={() => setOpen(false)}>
        {t("reveal.cancel")}
      </Button>
      {mutation.isError && <p role="alert">{mutation.error.message}</p>}
    </div>
  );
}

/** Resident 360: reveals one Golden Resident through its audited reveal endpoint (purpose and note). */
export function RevealButton({
  id,
  onRevealed,
}: {
  id: string;
  onRevealed: (resident: GoldenResident360) => void;
}) {
  return (
    <PurposeRevealButton<GoldenResident360>
      withNote
      onRevealed={onRevealed}
      reveal={(purpose, note) =>
        unwrap(
          api.POST("/api/v1/golden-residents/{id}/reveal", {
            params: { path: { id } },
            body: { purpose, note },
          }),
        )
      }
    />
  );
}
