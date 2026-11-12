import { useState } from "react";
import type {
  MappingTarget,
  SourceProfile,
  StudioDraft,
  StudioMappingRule,
  StudioSuggestions,
} from "../../../api/types";
import { useI18n } from "../../../i18n/i18n";
import {
  Button,
  Field,
  Panel,
  QueryState,
  Select,
  TextInput,
} from "../../../shared/components";
import {
  crosswalksFor,
  currentRecordKey,
  findTarget,
  initialConfiguration,
  keyOf,
  ruleFor,
  STATE_FIELD_TYPES,
  stateFieldRule,
  targetKey,
  withKey,
} from "../configuration";
import {
  useMappingTargets,
  useProfile,
  useSaveConfiguration,
  useSuggestions,
} from "../hooks";
import { ErrorNote, StepFooter } from "./StepFooter";
import { isFocused, useFocusKey, useScrollToFocused } from "../focus";
import styles from "../studio.module.css";
import { GeographyPanel } from "../GeographyPanel";

/** Step 3: one row per source column with its targets, pre-filled with the suggestions and their confidence. */
export function MapStep({
  draft,
  onBack,
  onDone,
}: {
  draft: StudioDraft;
  onBack: () => void;
  onDone: () => void;
}) {
  const draftId = draft.draftId ?? "";
  const profile = useProfile(draftId, true);
  const suggestions = useSuggestions(draftId, true);
  const targets = useMappingTargets();
  return (
    <QueryState query={profile}>
      {(p) => (
        <QueryState query={suggestions}>
          {(s) => (
            <QueryState query={targets}>
              {(tg) => (
                <MapEditor
                  draft={draft}
                  profile={p}
                  suggestions={s}
                  targets={tg.targets}
                  onBack={onBack}
                  onDone={onDone}
                />
              )}
            </QueryState>
          )}
        </QueryState>
      )}
    </QueryState>
  );
}

function MapEditor({
  draft,
  profile,
  suggestions,
  targets,
  onBack,
  onDone,
}: {
  draft: StudioDraft;
  profile: SourceProfile;
  suggestions: StudioSuggestions;
  targets: MappingTarget[];
  onBack: () => void;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const focus = useFocusKey();
  useScrollToFocused(focus, true);
  const fresh = draft.mappings.length === 0;
  const start = fresh ? initialConfiguration(draft, suggestions) : null;
  const [rules, setRules] = useState<StudioMappingRule[]>(
    start?.mappings ?? draft.mappings,
  );
  const columnNames = profile.columns.map((c) => c.name ?? "");
  // A saved choice that is no longer a column (renamed header, new file) is dropped, not sent back hidden.
  const [saved] = useState(() =>
    currentRecordKey(
      start?.recordIdField ??
        draft.source?.recordIdField ??
        suggestions.recordIdField,
      start?.recordKeyFields ?? draft.source?.recordKeyFields,
      columnNames,
    ),
  );
  const [recordId, setRecordId] = useState(saved.recordIdField);
  const [recordKeys, setRecordKeys] = useState<string[]>(
    saved.recordKeyFields,
  );
  const save = useSaveConfiguration(draft.draftId ?? "");

  const options = [
    { value: "", label: t("studio.map.keep") },
    ...targets.map((target) => ({
      value: targetKey(target.entity, target.target),
      label: `${t(`studio.map.entity.${target.entity}`)} · ${target.label}`,
    })),
  ];

  const replace = (index: number, key: string) => {
    const column = profile.columns.find(
      (c) => c.name === rules[index]?.sourceField,
    );
    const target = findTarget(targets, key);
    setRules((current) =>
      target
        ? current.map((rule, i) =>
            i === index ? ruleFor(column, target) : rule,
          )
        : current.filter((_, i) => i !== index),
    );
  };
  const add = (columnName: string, key: string) => {
    const column = profile.columns.find((c) => c.name === columnName);
    const target = findTarget(targets, key);
    if (target) {
      setRules((current) => [...current, ruleFor(column, target)]);
    }
  };
  const makeStateField = (columnName: string) => {
    const column = profile.columns.find((c) => c.name === columnName);
    setRules((current) => [...current, stateFieldRule(column)]);
  };
  const updateRule = (index: number, rule: StudioMappingRule) =>
    setRules((current) => current.map((r, i) => (i === index ? rule : r)));
  const removeRule = (index: number) =>
    setRules((current) => current.filter((_, i) => i !== index));
  const suggestionFor = (rule: StudioMappingRule) =>
    suggestions.columns
      .find((c) => c.column === rule.sourceField)
      ?.targets.find(
        (s) => s.entity === rule.entity && s.target === rule.target,
      );

  const submit = () => {
    const existing = fresh ? suggestions.crosswalks : draft.crosswalks;
    save.mutate(
      {
        mappings: rules,
        crosswalks: crosswalksFor(
          rules,
          existing,
          suggestions,
          profile,
          targets,
        ),
        rules: draft.rules,
        recordIdField: recordId,
        recordKeyFields: recordKeys,
      },
      { onSuccess: onDone },
    );
  };

  return (
    <div className="stack">
      <Panel
        title={t("studio.steps.4")}
        actions={
          <Button
            variant="secondary"
            onClick={() =>
              setRules(initialConfiguration(draft, suggestions).mappings)
            }
          >
            {t("studio.map.resetSuggestions")}
          </Button>
        }
      >
        <p className="muted">{t("studio.map.intro")}</p>
        {suggestions.layoutProposals.length > 0 && (
          <section data-testid="layout-proposals">
            <h3>{t("studio.map.layoutProposals")}</h3>
            <p className="muted">{t("studio.map.layoutProposalsIntro")}</p>
            <ul>
              {suggestions.layoutProposals.map((p, i) => (
                <li key={`${p.kind}-${p.sheet ?? ""}-${i}`}>
                  {p.description} (
                  {t("studio.map.layoutProposalEvidence", {
                    count: p.evidenceCount,
                    total: p.evidenceTotal,
                  })}
                  {p.sheet ? `, ${p.sheet}` : ""}): <code>{p.yaml}</code>
                </li>
              ))}
            </ul>
          </section>
        )}
        <div style={{ overflowX: "auto" }}>
          <table className={styles.table} data-testid="mapping-table">
            <thead>
              <tr>
                <th scope="col">{t("studio.map.column")}</th>
                <th scope="col">{t("studio.map.samples")}</th>
                <th scope="col">{t("studio.map.targets")}</th>
              </tr>
            </thead>
            <tbody>
              {profile.columns.map((column) => {
                const name = column.name ?? "";
                return (
                  <tr
                    key={name}
                    data-testid={`map-row-${name}`}
                    data-focused={isFocused(focus, name) || undefined}
                    tabIndex={isFocused(focus, name) ? -1 : undefined}
                    className={isFocused(focus, name) ? styles.focusedRow : undefined}
                  >
                    <td>
                      <code>{name}</code>
                    </td>
                    <td className={styles.values}>
                      {column.topValues
                        .slice(0, 3)
                        .map((v) => v.value)
                        .join(", ")}
                    </td>
                    <td>
                      {rules.map((rule, index) => {
                        if (rule.sourceField !== name) {
                          return null;
                        }
                        if (rule.stateField) {
                          const spec = rule.stateField;
                          return (
                            <div
                              key={index}
                              className={styles.targetRow}
                              data-testid={`state-field-${name}`}
                            >
                              <span className={styles.basis}>
                                {t("studio.map.stateField.title")}
                              </span>
                              <TextInput
                                aria-label={`${t("studio.map.stateField.label")} ${name}`}
                                value={spec.label ?? ""}
                                onChange={(e) =>
                                  updateRule(index, {
                                    ...rule,
                                    stateField: { ...spec, label: e.target.value },
                                  })
                                }
                                data-testid={`state-field-label-${name}`}
                              />
                              <TextInput
                                aria-label={`${t("studio.map.stateField.key")} ${name}`}
                                value={keyOf(rule)}
                                onChange={(e) =>
                                  updateRule(index, withKey(rule, e.target.value))
                                }
                                data-testid={`state-field-key-${name}`}
                              />
                              <Select
                                aria-label={`${t("studio.map.stateField.type")} ${name}`}
                                value={spec.type ?? "text"}
                                onChange={(e) =>
                                  updateRule(index, {
                                    ...rule,
                                    stateField: {
                                      ...spec,
                                      type: e.target.value as (typeof STATE_FIELD_TYPES)[number],
                                    },
                                  })
                                }
                                options={STATE_FIELD_TYPES.map((type) => ({
                                  value: type,
                                  label: t(`studio.map.stateField.types.${type}`),
                                }))}
                                data-testid={`state-field-type-${name}`}
                              />
                              <label>
                                <input
                                  type="checkbox"
                                  checked={spec.golden === true}
                                  onChange={(e) =>
                                    updateRule(index, {
                                      ...rule,
                                      stateField: { ...spec, golden: e.target.checked },
                                    })
                                  }
                                  data-testid={`state-field-golden-${name}`}
                                />{" "}
                                {t("studio.map.stateField.golden")}
                              </label>
                              <Button
                                variant="secondary"
                                onClick={() => removeRule(index)}
                              >
                                {t("studio.map.remove")}
                              </Button>
                            </div>
                          );
                        }
                        const suggestion = suggestionFor(rule);
                        return (
                          <div key={index} className={styles.targetRow}>
                            <Select
                              aria-label={`${t("studio.map.targets")} ${name}`}
                              value={targetKey(rule.entity, rule.target)}
                              onChange={(e) => replace(index, e.target.value)}
                              options={options}
                              data-testid={`target-${name}`}
                            />
                            {suggestion && (
                              <span
                                className={styles.confidence}
                                title={suggestion.basis ?? ""}
                              >
                                {t("studio.map.confidence", {
                                  value: Math.round(
                                    (suggestion.confidence ?? 0) * 100,
                                  ),
                                })}
                              </span>
                            )}
                            {rule.conversion && (
                              <code className={styles.basis}>
                                {rule.conversion}
                              </code>
                            )}
                          </div>
                        );
                      })}
                      {suggestions.columns
                        .find((c) => c.column === name)
                        ?.targets.filter(
                          (s) =>
                            !s.accepted &&
                            !rules.some(
                              (r) =>
                                r.sourceField === name &&
                                r.entity === s.entity &&
                                r.target === s.target,
                            ),
                        )
                        .map((s) => (
                          <div
                            key={`${s.entity}:${s.target}`}
                            className={styles.targetRow}
                            data-testid={`review-${name}`}
                          >
                            <span className={styles.basis} title={s.basis ?? undefined}>
                              {t("studio.map.review", {
                                target: `${s.entity}:${s.target}`,
                                value: Math.round(s.confidence * 100),
                                basis: s.basis,
                              })}
                            </span>
                            <Button
                              variant="secondary"
                              onClick={() =>
                                add(name, targetKey(s.entity, s.target))
                              }
                            >
                              {t("studio.map.useSuggestion")}
                            </Button>
                          </div>
                        ))}
                      {!rules.some((r) => r.sourceField === name) && (
                        <div className={styles.targetRow}>
                          <span className={styles.basis}>{t("studio.map.keep")}</span>
                          <Button
                            variant="secondary"
                            onClick={() => makeStateField(name)}
                            data-testid={`make-state-field-${name}`}
                          >
                            {t("studio.map.stateField.make")}
                          </Button>
                        </div>
                      )}
                      <Select
                        aria-label={`${t("studio.map.add")} ${name}`}
                        value=""
                        onChange={(e) => add(name, e.target.value)}
                        options={[
                          { value: "", label: `+ ${t("studio.map.add")}` },
                          ...options.slice(1),
                        ]}
                        data-testid={`add-target-${name}`}
                      />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Panel>
      <Panel title={t("studio.map.recordId")}>
        <div className="grid-2">
          <Field id="record-id" label={t("studio.map.recordId")}>
            <Select
              id="record-id"
              value={recordId}
              onChange={(e) => setRecordId(e.target.value)}
              options={[
                ...(recordId
                  ? []
                  : [{ value: "", label: t("studio.map.chooseRecordId") }]),
                ...columnNames.map((n) => ({ value: n, label: n })),
              ]}
              data-testid="record-id"
            />
          </Field>
          {saved.dropped.length > 0 && (
            <p className="muted" role="status" data-testid="stale-record-key">
              {t("studio.map.staleRecordKey", {
                columns: saved.dropped.join(", "),
              })}
            </p>
          )}
          <fieldset>
            <legend>{t("studio.map.recordKey")}</legend>
            <p className="muted">{t("studio.map.recordKeyHint")}</p>
            {columnNames.map((n) => (
              <label key={n} className="row">
                <input
                  type="checkbox"
                  checked={recordKeys.includes(n)}
                  onChange={(e) =>
                    setRecordKeys((keys) =>
                      e.target.checked
                        ? [...keys, n]
                        : keys.filter((k) => k !== n),
                    )
                  }
                />
                {n}
              </label>
            ))}
          </fieldset>
        </div>
      </Panel>
      <ErrorNote error={save.error} />
      <GeographyPanel stateCode={draft.stateCode} />
      <StepFooter
        onBack={onBack}
        onContinue={submit}
        busy={save.isPending}
        disabled={!recordId}
      />
    </div>
  );
}
