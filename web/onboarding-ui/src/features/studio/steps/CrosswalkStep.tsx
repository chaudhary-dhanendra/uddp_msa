import { useState } from "react";
import type {
  CodeValue,
  StudioCrosswalk,
  StudioDraft,
} from "../../../api/types";
import { useI18n } from "../../../i18n/i18n";
import { Panel, QueryState, Select } from "../../../shared/components";
import { missingValues } from "../configuration";
import { LgdCodePicker } from "../GeographyPanel";
import { useMappingTargets, useSaveConfiguration } from "../hooks";
import { ErrorNote, StepFooter } from "./StepFooter";
import { isFocused, useFocusKey, useScrollToFocused } from "../focus";
import styles from "../studio.module.css";

/** Step 4: every coded column's distinct values against platform codes, pre-filled with suggestions. */
export function CrosswalkStep({
  draft,
  onBack,
  onDone,
}: {
  draft: StudioDraft;
  onBack: () => void;
  onDone: () => void;
}) {
  const targets = useMappingTargets();
  return (
    <QueryState query={targets}>
      {(tg) => (
        <CrosswalkEditor
          draft={draft}
          codeSets={tg.codeSets}
          onBack={onBack}
          onDone={onDone}
        />
      )}
    </QueryState>
  );
}

function CrosswalkEditor({
  draft,
  codeSets,
  onBack,
  onDone,
}: {
  draft: StudioDraft;
  codeSets: Record<string, CodeValue[]>;
  onBack: () => void;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const focus = useFocusKey();
  useScrollToFocused(focus, true);
  const [crosswalks, setCrosswalks] = useState<StudioCrosswalk[]>(
    draft.crosswalks,
  );
  const save = useSaveConfiguration(draft.draftId ?? "");
  const missing = missingValues(crosswalks);

  const choose = (id: string, sourceValue: string, value: string) =>
    setCrosswalks((current) =>
      current.map((c) =>
        c.id !== id
          ? c
          : {
              ...c,
              entries: c.entries.map((e) =>
                e.sourceValue === sourceValue
                  ? {
                      ...e,
                      targetValue: value === "" ? null : value,
                      basis: "chosen by the user",
                      confidence: 1,
                    }
                  : e,
              ),
            },
      ),
    );

  const submit = () =>
    save.mutate(
      {
        mappings: draft.mappings,
        crosswalks,
        rules: draft.rules,
        recordIdField: draft.source?.recordIdField ?? null,
        recordKeyFields: draft.source?.recordKeyFields ?? [],
      },
      { onSuccess: onDone },
    );

  return (
    <div className="stack">
      <Panel title={t("studio.steps.5")}>
        <p className="muted">{t("studio.crosswalk.intro")}</p>
        {crosswalks.length === 0 && <p>{t("studio.crosswalk.none")}</p>}
        {missing > 0 && (
          <p
            className={styles.notice}
            role="status"
            data-testid="crosswalk-missing"
          >
            {t("studio.crosswalk.missing", { count: missing })}
          </p>
        )}
      </Panel>
      {crosswalks.map((crosswalk) => {
        const codes = codeSets[crosswalk.codeSet ?? ""] ?? [];
        const columns = draft.mappings
          .filter((m) => m.crosswalk === crosswalk.id)
          .map((m) => m.sourceField)
          .join(", ");
        return (
          <Panel
            key={crosswalk.id}
            title={`${crosswalk.id} — ${columns}`}
            headingLevel={3}
          >
            <table
              className={isFocused(focus, crosswalk.id) ? `${styles.table} ${styles.focusedRow}` : styles.table}
              data-testid={`crosswalk-${crosswalk.id}`}
              data-focused={isFocused(focus, crosswalk.id) || undefined}
              tabIndex={isFocused(focus, crosswalk.id) ? -1 : undefined}
            >
              <thead>
                <tr>
                  <th scope="col">{t("studio.crosswalk.source")}</th>
                  <th scope="col">{t("studio.crosswalk.target")}</th>
                  <th scope="col">{t("studio.crosswalk.basis")}</th>
                </tr>
              </thead>
              <tbody>
                {crosswalk.entries.map((entry) => (
                  <tr
                    key={entry.sourceValue}
                    className={
                      entry.targetValue ? undefined : styles.warningRow
                    }
                  >
                    <td>
                      <code>{entry.sourceValue}</code>
                    </td>
                    <td>
                      {crosswalk.codeSet?.startsWith("lgd-") ? (
                        <LgdCodePicker
                          referenceSet={crosswalk.codeSet}
                          label={`${crosswalk.id} ${entry.sourceValue}`}
                          value={entry.targetValue ?? ""}
                          onChange={(code) =>
                            choose(crosswalk.id ?? "", entry.sourceValue ?? "", code)
                          }
                        />
                      ) : (
                      <Select
                        aria-label={`${crosswalk.id} ${entry.sourceValue}`}
                        value={entry.targetValue ?? ""}
                        onChange={(e) =>
                          choose(
                            crosswalk.id ?? "",
                            entry.sourceValue ?? "",
                            e.target.value,
                          )
                        }
                        options={[
                          { value: "", label: t("studio.crosswalk.choose") },
                          ...codes.map((c) => ({
                            value: c.code ?? "",
                            label:
                              c.label && c.label !== c.code
                                ? `${c.code} — ${c.label}`
                                : (c.code ?? ""),
                          })),
                        ]}
                        data-testid={`xw-${crosswalk.id}-${entry.sourceValue}`}
                      />
                      )}
                    </td>
                    <td className={styles.basis}>
                      {entry.basis}
                      {entry.confidence
                        ? ` (${Math.round(entry.confidence * 100)}%)`
                        : ""}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Panel>
        );
      })}
      <ErrorNote error={save.error} />
      <StepFooter onBack={onBack} onContinue={submit} busy={save.isPending} />
    </div>
  );
}
