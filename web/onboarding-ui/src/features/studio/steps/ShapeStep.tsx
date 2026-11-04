import { useMemo, useState } from "react";
import type { StudioDraft } from "../../../api/types";
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
  useSaveShaping,
  useShapePreview,
  useShapeState,
  useTestValue,
  type ShapeState,
  type Step,
} from "../../shaping/api";
import { useParserKeys } from "../../shaping/ParserTestPanel";
import {
  compose,
  hydrate,
  outputName,
  type AliasRow,
  type ShapeChoices,
} from "../../shaping/shape";
import { ErrorNote, StepFooter } from "./StepFooter";
import { isFocused, useFocusKey, useScrollToFocused } from "../focus";
import styles from "../studio.module.css";

function SampleTable({
  caption,
  columns,
  rows,
  sheets,
  testId,
}: {
  caption: string;
  columns: string[];
  rows: string[][];
  /** The sheet of each row (workbooks), shown as a first column. */
  sheets?: string[] | null;
  testId: string;
}) {
  const { t } = useI18n();
  return (
    <div style={{ overflowX: "auto" }}>
      <table className={styles.table} data-testid={testId}>
        <caption>{caption}</caption>
        <thead>
          <tr>
            {sheets && <th scope="col">{t("studio.shape.sheet")}</th>}
            {columns.map((name, i) => (
              <th scope="col" key={`${name}-${i}`}>
                {name}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, r) => (
            <tr key={r}>
              {sheets && <td>{sheets[r] ?? ""}</td>}
              {columns.map((_, c) => (
                <td key={c}>{row[c] ?? ""}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Step 3: clean-up of a messy file. The profiler's layout and parser proposals are listed with their evidence and an
 * on/off switch; a column alias can be added by hand. The steps are only kept in the draft's
 * source (layout: and parse:), and the 20-row before/after is computed by the same operations the pipeline runs.
 */
export function ShapeStep({
  draft,
  onBack,
  onDone,
}: {
  draft: StudioDraft;
  onBack: () => void;
  onDone: () => void;
}) {
  const draftId = draft.draftId ?? "";
  const state = useShapeState(draftId);
  const { t } = useI18n();
  return (
    <QueryState query={state} label={t("studio.shape.title")}>
      {(shape) => (
        <ShapeEditor
          key={JSON.stringify([shape.layout, shape.parse])}
          draftId={draftId}
          shape={shape}
          onBack={onBack}
          onDone={onDone}
        />
      )}
    </QueryState>
  );
}

function ShapeEditor({
  draftId,
  shape,
  onBack,
  onDone,
}: {
  draftId: string;
  shape: ShapeState;
  onBack: () => void;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const focus = useFocusKey();
  useScrollToFocused(focus, true);
  const [choices, setChoices] = useState<ShapeChoices>(() => hydrate(shape));
  const [alias, setAlias] = useState<AliasRow>({ logical: "", variant: "" });
  const parserKeys = useParserKeys();
  const [parserRow, setParserRow] = useState({ column: "", parser: "", prefix: "" });
  const [parserProblem, setParserProblem] = useState<string | null>(null);
  const discover = useTestValue();
  const config = useMemo(
    () => compose(shape.proposals, choices, shape.sheets),
    [shape, choices],
  );
  const preview = useShapePreview(draftId, config, true);
  const save = useSaveShaping(draftId);
  const previewError = preview.error instanceof Error ? preview.error : null;

  const toggle = (index: number) =>
    setChoices((c) => ({
      ...c,
      on: c.on.includes(index)
        ? c.on.filter((i) => i !== index)
        : [...c.on, index].sort((a, b) => a - b),
    }));
  const addAlias = () => {
    if (!alias.logical.trim() || !alias.variant.trim()) {
      return;
    }
    setChoices((c) => ({ ...c, aliases: [...c.aliases, alias] }));
    setAlias({ logical: "", variant: "" });
  };
  // A field parser added by hand: its output keys are learnt by running it on the first filled sample value of the
  // column, and each key becomes a tidy column <prefix>_<key> the Map step offers.
  const tidyColumns = preview.data?.afterColumns ?? [];
  const parserColumn = parserRow.column || tidyColumns[0] || "";
  const parserKey = parserRow.parser || parserKeys[0] || "";
  const prefix = parserRow.prefix.trim() || outputName(parserColumn, "");
  const addParser = () => {
    const index = tidyColumns.indexOf(parserColumn);
    const sample = (preview.data?.afterRows ?? [])
      .map((row) => (row[index] ?? "").trim())
      .find((v) => v !== "");
    if (index < 0 || !parserKey || sample === undefined) {
      setParserProblem(t("studio.shape.parserNoSample", { column: parserColumn }));
      return;
    }
    setParserProblem(null);
    discover.mutate(
      { parser: parserKey, value: sample },
      {
        onSuccess: (result) => {
          const keys = Object.keys(result.values);
          if (keys.length === 0) {
            setParserProblem(
              t("studio.shape.parserNoOutput", { parser: parserKey, column: parserColumn }),
            );
            return;
          }
          const outputs: Record<string, string> = {};
          for (const key of keys) {
            outputs[key] = outputName(prefix, key);
          }
          const step: Step = { sourceField: parserColumn, parser: parserKey, outputs };
          setChoices((c) => ({ ...c, otherParse: [...c.otherParse, step] }));
          setParserRow({ column: "", parser: parserRow.parser, prefix: "" });
        },
        onError: (error) => setParserProblem(error.message),
      },
    );
  };
  return (
    <>
      <Panel title={t("studio.shape.title")} id="shape-proposals">
        <p className="muted">{t("studio.shape.intro")}</p>
        {shape.proposals.length === 0 ? (
          <p data-testid="shape-none">{t("studio.shape.none")}</p>
        ) : (
          <ul style={{ listStyle: "none", padding: 0 }}>
            {shape.proposals.map((p, index) => (
              <li
                key={`${p.kind}-${p.sheet ?? ""}-${index}`}
                data-focused={isFocused(focus, String(p.step["sourceField"] ?? "")) || undefined}
                tabIndex={isFocused(focus, String(p.step["sourceField"] ?? "")) ? -1 : undefined}
                className={isFocused(focus, String(p.step["sourceField"] ?? "")) ? styles.focusedRow : undefined}
              >
                <label>
                  <input
                    type="checkbox"
                    checked={choices.on.includes(index)}
                    onChange={() => toggle(index)}
                    aria-label={p.description}
                  />{" "}
                  {p.description}{" "}
                  <span className="muted">
                    (
                    {t("studio.shape.evidence", {
                      count: p.evidenceCount,
                      total: p.evidenceTotal,
                    })}
                    {p.sheet ? `, ${p.sheet}` : ""})
                  </span>
                </label>
                <div>
                  <code>{p.yaml}</code>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Panel>

      <Panel title={t("studio.shape.addTitle")} id="shape-manual">
        <div className="row">
          <Field id="alias-from" label={t("studio.shape.aliasFrom")}>
            <TextInput
              id="alias-from"
              value={alias.variant}
              onChange={(e) => setAlias({ ...alias, variant: e.target.value })}
            />
          </Field>
          <Field id="alias-to" label={t("studio.shape.aliasTo")}>
            <TextInput
              id="alias-to"
              value={alias.logical}
              onChange={(e) => setAlias({ ...alias, logical: e.target.value })}
            />
          </Field>
          <Button variant="secondary" onClick={addAlias}>
            {t("studio.shape.addAlias")}
          </Button>
        </div>
        {choices.aliases.length > 0 && (
          <ul data-testid="shape-aliases">
            {choices.aliases.map((a, i) => (
              <li key={`${a.variant}-${i}`}>
                {a.variant} → {a.logical}{" "}
                <Button
                  variant="secondary"
                  aria-label={t("studio.shape.remove", { name: a.variant })}
                  onClick={() =>
                    setChoices((c) => ({
                      ...c,
                      aliases: c.aliases.filter((_, j) => j !== i),
                    }))
                  }
                >
                  {t("studio.shape.removeButton")}
                </Button>
              </li>
            ))}
          </ul>
        )}
      </Panel>

      <Panel title={t("studio.shape.parserTitle")} id="shape-parsers">
        <p className="muted">{t("studio.shape.parserIntro")}</p>
        <div className="row">
          <Field id="parser-column" label={t("studio.shape.parserColumn")}>
            <Select
              id="parser-column"
              value={parserColumn}
              onChange={(e) => setParserRow({ ...parserRow, column: e.target.value, prefix: "" })}
              options={tidyColumns.map((c) => ({ value: c, label: c }))}
            />
          </Field>
          <Field id="parser-key" label={t("studio.shape.parserKey")}>
            <Select
              id="parser-key"
              value={parserKey}
              onChange={(e) => setParserRow({ ...parserRow, parser: e.target.value })}
              options={parserKeys.map((k) => ({ value: k, label: k }))}
            />
          </Field>
          <Field id="parser-prefix" label={t("studio.shape.parserPrefix")} hint={t("studio.shape.parserPrefixHint")}>
            <TextInput
              id="parser-prefix"
              value={parserRow.prefix}
              placeholder={prefix}
              onChange={(e) => setParserRow({ ...parserRow, prefix: e.target.value })}
            />
          </Field>
          <Button
            variant="secondary"
            onClick={addParser}
            disabled={!parserColumn || !parserKey || discover.isPending}
          >
            {t("studio.shape.addParser")}
          </Button>
        </div>
        {parserProblem && (
          <p role="alert" className={styles.error} data-testid="shape-parser-error">
            {parserProblem}
          </p>
        )}
        {choices.otherParse.length > 0 && (
          <ul data-testid="shape-parsers">
            {choices.otherParse.map((step, i) => {
              const outputs = (step.outputs ?? {}) as Record<string, string>;
              const label = `${String(step.sourceField)} → ${String(step.parser)}`;
              return (
                <li key={`${label}-${i}`}>
                  <code>{label}</code>
                  {Object.keys(outputs).length > 0 && (
                    <span className="muted">
                      {" "}
                      ({Object.entries(outputs).map(([k, v]) => `${k} → ${v}`).join(", ")})
                    </span>
                  )}{" "}
                  <Button
                    variant="secondary"
                    aria-label={t("studio.shape.remove", { name: label })}
                    onClick={() =>
                      setChoices((c) => ({
                        ...c,
                        otherParse: c.otherParse.filter((_, j) => j !== i),
                      }))
                    }
                  >
                    {t("studio.shape.removeButton")}
                  </Button>
                </li>
              );
            })}
          </ul>
        )}
      </Panel>

      <Panel title={t("studio.shape.yamlTitle")} id="shape-yaml">
        <pre
          aria-label={t("studio.shape.yamlTitle")}
          data-testid="shape-yaml"
          style={{ whiteSpace: "pre-wrap" }}
        >
          {preview.data?.yaml || t("studio.shape.yamlEmpty")}
        </pre>
      </Panel>

      <Panel title={t("studio.shape.sample")} id="shape-sample">
        {preview.isPending && (
          <p role="status">{t("common.loading")}</p>
        )}
        {previewError && (
          <p role="alert" className={styles.error} data-testid="shape-preview-error">
            {previewError.message}
          </p>
        )}
        {preview.data && (
          <>
            <SampleTable
              caption={t("studio.shape.before")}
              columns={preview.data.beforeColumns}
              rows={preview.data.beforeRows}
              sheets={preview.data.beforeSheets}
              testId="shape-before"
            />
            <SampleTable
              caption={t("studio.shape.after")}
              columns={preview.data.afterColumns}
              rows={preview.data.afterRows}
              sheets={preview.data.afterSheets}
              testId="shape-after"
            />
            {preview.data.warnings.length > 0 && (
              <ul data-testid="shape-warnings">
                {preview.data.warnings.map((w) => (
                  <li key={w}>{w}</li>
                ))}
              </ul>
            )}
          </>
        )}
      </Panel>

      <ErrorNote error={save.error} />
      <StepFooter
        onBack={onBack}
        onContinue={() => save.mutate(config, { onSuccess: onDone })}
        busy={save.isPending}
      />
    </>
  );
}
