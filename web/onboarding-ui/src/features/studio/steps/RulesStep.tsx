import { useState } from "react";
import type {
  StudioDraft,
  StudioRules,
  StudioValidationRule,
} from "../../../api/types";
import { useI18n } from "../../../i18n/i18n";
import {
  Button,
  Field,
  Panel,
  Select,
  TextInput,
} from "../../../shared/components";
import { useSaveConfiguration } from "../hooks";
import { ErrorNote, StepFooter } from "./StepFooter";
import { isFocused, useFocusKey, useScrollToFocused } from "../focus";
import styles from "../studio.module.css";

function describe(rule: StudioValidationRule): string {
  if (rule.type === "PATTERN") {
    return `PATTERN ${rule.pattern ?? ""}`;
  }
  if (rule.type === "REFERENCE_LIST") {
    return `REFERENCE_LIST ${rule.referenceSet ?? ""}`;
  }
  if (rule.required) {
    return "REQUIRED";
  }
  if (rule.allowedValues && rule.allowedValues.length > 0) {
    return `ALLOWED_VALUES ${rule.allowedValues.join(", ")}`;
  }
  return rule.type ?? "";
}

/** Step 5: validation, matching and survivorship rules, starting from the platform defaults. */
export function RulesStep({
  draft,
  onBack,
  onDone,
}: {
  draft: StudioDraft;
  onBack: () => void;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const focus = useFocusKey();
  useScrollToFocused(focus, true);
  const initial: StudioRules = draft.rules ?? {
    validation: [],
    matching: { fields: [], autoMatch: 0.95, possibleMatch: 0.75 },
    survivorship: {},
  };
  const [validation, setValidation] = useState<StudioValidationRule[]>(
    initial.validation,
  );
  const [autoMatch, setAutoMatch] = useState(
    String(initial.matching?.autoMatch ?? 0.95),
  );
  const [possibleMatch, setPossibleMatch] = useState(
    String(initial.matching?.possibleMatch ?? 0.75),
  );
  const [survivorship, setSurvivorship] = useState<Record<string, string>>(
    Object.fromEntries(
      Object.entries(initial.survivorship ?? {}).map(([field, sources]) => [
        field,
        (sources ?? []).join(", "),
      ]),
    ),
  );
  const save = useSaveConfiguration(draft.draftId ?? "");

  const submit = () =>
    save.mutate(
      {
        mappings: draft.mappings,
        crosswalks: draft.crosswalks,
        recordIdField: draft.source?.recordIdField ?? null,
        recordKeyFields: draft.source?.recordKeyFields ?? [],
        rules: {
          validation,
          matching: {
            fields: initial.matching?.fields ?? [],
            autoMatch: Number.parseFloat(autoMatch),
            possibleMatch: Number.parseFloat(possibleMatch),
          },
          survivorship: Object.fromEntries(
            Object.entries(survivorship)
              .map(
                ([field, text]) =>
                  [
                    field,
                    text
                      .split(",")
                      .map((s) => s.trim())
                      .filter(Boolean),
                  ] as const,
              )
              .filter(([, sources]) => sources.length > 0),
          ),
        },
      },
      { onSuccess: onDone },
    );

  return (
    <div className="stack">
      <Panel title={t("studio.rules.validation")}>
        <p className="muted">{t("studio.rules.intro")}</p>
        <table className={styles.table} data-testid="validation-rules">
          <thead>
            <tr>
              <th scope="col">{t("studio.rules.field")}</th>
              <th scope="col">{t("studio.rules.rule")}</th>
              <th scope="col">{t("studio.rules.severity")}</th>
              <th scope="col" />
            </tr>
          </thead>
          <tbody>
            {validation.map((rule, index) => (
              <tr
                key={`${rule.field}-${index}`}
                data-focused={isFocused(focus, rule.id, rule.field) || undefined}
                tabIndex={isFocused(focus, rule.id, rule.field) ? -1 : undefined}
                className={isFocused(focus, rule.id, rule.field) ? styles.focusedRow : undefined}
              >
                <td>
                  <code>{rule.field}</code>
                </td>
                <td className={styles.values}>{describe(rule)}</td>
                <td>
                  <Select
                    aria-label={`${t("studio.rules.severity")} ${rule.field}`}
                    value={rule.severity ?? "error"}
                    onChange={(e) =>
                      setValidation((rules) =>
                        rules.map((r, i) =>
                          i === index
                            ? {
                                ...r,
                                severity:
                                  e.target.value === "warning"
                                    ? "warning"
                                    : "error",
                              }
                            : r,
                        ),
                      )
                    }
                    options={[
                      { value: "error", label: "error" },
                      { value: "warning", label: "warning" },
                    ]}
                  />
                </td>
                <td>
                  <Button
                    variant="secondary"
                    onClick={() =>
                      setValidation((rules) =>
                        rules.filter((_, i) => i !== index),
                      )
                    }
                  >
                    {t("studio.rules.remove")}
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>
      <Panel title={t("studio.rules.matching")}>
        <div className="row">
          <Field id="auto-match" label={t("studio.rules.autoMatch")}>
            <TextInput
              id="auto-match"
              className={styles.small}
              inputMode="decimal"
              value={autoMatch}
              onChange={(e) => setAutoMatch(e.target.value)}
            />
          </Field>
          <Field id="possible-match" label={t("studio.rules.possibleMatch")}>
            <TextInput
              id="possible-match"
              className={styles.small}
              inputMode="decimal"
              value={possibleMatch}
              onChange={(e) => setPossibleMatch(e.target.value)}
            />
          </Field>
        </div>
        <p className="muted">
          {t("studio.rules.fields")}:{" "}
          {(initial.matching?.fields ?? []).join(", ")}
        </p>
      </Panel>
      <Panel title={t("studio.rules.survivorship")}>
        <div className="grid-2">
          {Object.keys(survivorship).map((field) => (
            <Field key={field} id={`survive-${field}`} label={field}>
              <TextInput
                id={`survive-${field}`}
                value={survivorship[field] ?? ""}
                onChange={(e) =>
                  setSurvivorship((s) => ({ ...s, [field]: e.target.value }))
                }
              />
            </Field>
          ))}
        </div>
      </Panel>
      <ErrorNote error={save.error} />
      <StepFooter onBack={onBack} onContinue={submit} busy={save.isPending} />
    </div>
  );
}
