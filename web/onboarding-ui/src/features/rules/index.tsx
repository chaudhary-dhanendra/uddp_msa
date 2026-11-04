import { useEffect, useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { sendJson } from "../../api/client";
import type {
  DecisionRow,
  RuleEvaluation,
  RuleField,
  RuleSetSummary,
  RuleSetVersion,
  RuleTableDraft,
  RuleTestCase,
  SchemePackVersion,
} from "../../api/types";
import { Button, PageHeader, StatusBadge } from "../../shared/components";
import { useSession } from "../auth";
import {
  type Condition,
  choicesOf,
  dataTypeOf,
  describe,
  newCondition,
  operatorsOf,
  problemOf,
  toPayload,
  withField,
  withOperator,
} from "./conditions";
import styles from "./rules.module.css";

export type DecisionTable = { rows: DecisionRow[] };
type RuleType = NonNullable<RuleTableDraft["type"]>;
type OwnerType = NonNullable<RuleTableDraft["ownerType"]>;

const OPERATORS = [">=", ">", "<=", "<", "=", "!="];
/** What a data-quality rule does with a row it fires on; the row's output carries the code. */
export const OUTCOMES = [
  {
    value: "ERROR",
    label: "Error",
    help: "Error: the row cannot be used. It is held for a steward to correct, or rejected when it cannot be corrected.",
  },
  {
    value: "WARNING",
    label: "Warning",
    help: "Warning: the row is loaded and the problem is stored against the record.",
  },
  {
    value: "SEND_TO_STEWARD",
    label: "Send to steward",
    help: "Send to steward: the row is held until a steward corrects it or releases it as it is with a justification.",
  },
];
/** The outcomes each rule type can return; survivorship rules name their own output. */
const OUTCOMES_BY_TYPE: Record<
  RuleType,
  { value: string; label: string; help: string }[]
> = {
  ELIGIBILITY: [
    {
      value: "ELIGIBLE",
      label: "Eligible",
      help: "The resident qualifies for the scheme when every condition holds.",
    },
    {
      value: "NOT_ELIGIBLE",
      label: "Not eligible",
      help: "The resident is excluded when every condition holds.",
    },
  ],
  DATA_QUALITY: OUTCOMES,
  SURVIVORSHIP: [],
};
const TYPE_LABELS: Record<RuleType, string> = {
  ELIGIBILITY: "Eligibility",
  DATA_QUALITY: "Data quality",
  SURVIVORSHIP: "Survivorship",
};
const OWNER_LABELS: Record<OwnerType, string> = {
  SCHEME: "Scheme",
  DEPARTMENT: "Department",
  SOURCE_PACK: "Source pack",
};
const SUBJECT: Record<RuleType, string> = {
  ELIGIBILITY: "the resident is",
  DATA_QUALITY: "the row is marked",
  SURVIVORSHIP: "the output is",
};

const outcomeLabel = (code: unknown) =>
  [...OUTCOMES, ...OUTCOMES_BY_TYPE.ELIGIBILITY].find((o) => o.value === code)
    ?.label ?? String(code);
const emptyRow = (): DecisionRow => ({
  condition: "",
  output: "",
  clauseReference: "",
});

async function readJson<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) throw new Error("Unable to load " + url);
  return response.json() as Promise<T>;
}

async function problem(response: Response, fallback: string): Promise<Error> {
  const body = (await response.json().catch(() => null)) as {
    message?: string;
  } | null;
  return new Error(body?.message ?? fallback);
}

/** A FEEL literal for the condition builder: numbers and true/false as they are, anything else quoted. */
function literal(value: string): string {
  const trimmed = value.trim();
  return /^-?\d+(\.\d+)?$/.test(trimmed) ||
    trimmed === "true" ||
    trimmed === "false"
    ? trimmed
    : JSON.stringify(trimmed);
}

/** One condition per row typed as FEEL, or built from a metadata field, an operator and a value (kept for wizards). */
export function DecisionTableEditor({
  value,
  onChange,
  fields = [],
  outcomes = false,
}: {
  value: DecisionTable;
  onChange: (value: DecisionTable) => void;
  fields?: RuleField[];
  outcomes?: boolean;
}) {
  const [builder, setBuilder] = useState<
    Record<number, { field: string; operator: string; value: string }>
  >({});
  const update = (index: number, change: Partial<DecisionRow>) =>
    onChange({
      rows: value.rows.map((row, i) =>
        i === index ? { ...row, ...change } : row,
      ),
    });
  const part = (index: number) =>
    builder[index] ?? { field: "", operator: ">=", value: "" };
  const setPart = (
    index: number,
    change: Partial<{ field: string; operator: string; value: string }>,
  ) => setBuilder((b) => ({ ...b, [index]: { ...part(index), ...change } }));
  const build = (index: number) => {
    const p = part(index);
    if (!p.field || p.value.trim() === "") return;
    const clause = `${p.field} ${p.operator} ${literal(p.value)}`;
    const current = value.rows[index]?.condition?.trim();
    update(index, { condition: current ? `${current} and ${clause}` : clause });
  };
  return (
    <section>
      <h2>Decision table</h2>
      {outcomes && (
        <ul aria-label="What each outcome does">
          {OUTCOMES.map((o) => (
            <li key={o.value}>{o.help}</li>
          ))}
        </ul>
      )}
      <button onClick={() => onChange({ rows: [...value.rows, emptyRow()] })}>
        Add row
      </button>
      {value.rows.map((row, index) => (
        <div key={index}>
          <input
            aria-label={`Condition ${index + 1}`}
            value={row.condition ?? ""}
            onChange={(e) => update(index, { condition: e.target.value })}
          />
          {fields.length > 0 && (
            <>
              <select
                aria-label={`Field ${index + 1}`}
                value={part(index).field}
                onChange={(e) => setPart(index, { field: e.target.value })}
              >
                <option value="">Field</option>
                {fields.map((f) => (
                  <option key={f.name ?? ""} value={f.name ?? ""}>
                    {f.label ?? f.name}
                  </option>
                ))}
              </select>
              <select
                aria-label={`Operator ${index + 1}`}
                value={part(index).operator}
                onChange={(e) => setPart(index, { operator: e.target.value })}
              >
                {OPERATORS.map((o) => (
                  <option key={o} value={o}>
                    {o}
                  </option>
                ))}
              </select>
              <input
                aria-label={`Value ${index + 1}`}
                value={part(index).value}
                onChange={(e) => setPart(index, { value: e.target.value })}
              />
              <button onClick={() => build(index)}>Add to condition</button>
            </>
          )}
          {outcomes && (
            <select
              aria-label={`Outcome ${index + 1}`}
              value={row.output ?? ""}
              onChange={(e) => update(index, { output: e.target.value })}
            >
              <option value="">Outcome</option>
              {OUTCOMES.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          )}
          <input
            aria-label={`Output ${index + 1}`}
            value={row.output ?? ""}
            onChange={(e) => update(index, { output: e.target.value })}
          />
          <input
            aria-label={`Clause reference ${index + 1}`}
            value={row.clauseReference ?? ""}
            onChange={(e) => update(index, { clauseReference: e.target.value })}
          />
          <button
            onClick={() =>
              onChange({ rows: value.rows.filter((_, i) => i !== index) })
            }
          >
            Remove row
          </button>
        </div>
      ))}
    </section>
  );
}

/** A generated case as the backend sends it: its record, the clauses that fired and the outcomes they returned. */
type GeneratedCase = RuleTestCase & {
  firedClauses: string[];
  outcomes: unknown[];
};
type ConditionCheck = {
  clauseReference: string | null;
  condition: string | null;
  matched: boolean;
};

/** Evaluates a sample: a chosen version (drafts too) when `ruleSetId` is given, else the effective approved one. */
export function RuleTestPanel({
  type,
  ownerType,
  ownerId,
  ruleSetId,
}: {
  type: string;
  ownerType: string;
  ownerId: string;
  ruleSetId?: string;
}) {
  const [sample, setSample] = useState("{}");
  const [picked, setPicked] = useState("");
  const [result, setResult] = useState<RuleEvaluation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cases, setCases] = useState<GeneratedCase[]>([]);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setCases([]);
    setPicked("");
    setResult(null);
    if (!ruleSetId) return;
    void readJson<GeneratedCase[]>(`/api/rule-sets/${ruleSetId}/test-cases`)
      .then(setCases)
      .catch(() => setCases([]));
  }, [ruleSetId]);
  const pick = (index: string) => {
    setPicked(index);
    setResult(null);
    setError(null);
    const chosen = cases[Number(index)];
    if (index !== "" && chosen)
      setSample(JSON.stringify(chosen.record, null, 2));
  };
  const test = async () => {
    setError(null);
    setResult(null);
    let record: unknown;
    try {
      record = JSON.parse(sample);
    } catch {
      setError("The sample record is not valid JSON.");
      return;
    }
    setBusy(true);
    try {
      const response = ruleSetId
        ? await sendJson(`/api/rule-sets/${ruleSetId}/evaluate`, "POST", record)
        : await sendJson("/api/rule-sets/evaluate", "POST", {
            type,
            ownerType,
            ownerId,
            record,
          });
      if (!response.ok) throw await problem(response, "Evaluation failed");
      setResult((await response.json()) as RuleEvaluation);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Evaluation failed");
    } finally {
      setBusy(false);
    }
  };
  const checks = (
    (result as { checks?: ConditionCheck[] } | null)?.checks ?? []
  ).filter((c) => c.condition);
  const failed = checks.filter((c) => !c.matched);
  return (
    <section className={styles.card}>
      <h2>Test rule</h2>
      <p className={styles.muted}>
        Try the rule on a sample record before it is approved. Dotted fields
        such as age.years are nested objects.
      </p>
      {cases.length > 0 && (
        <label className={styles.label}>
          Generated case
          <select
            aria-label="Sample record"
            className={styles.control}
            value={picked}
            onChange={(e) => pick(e.target.value)}
          >
            <option value="">Pick a generated case</option>
            {cases.map((c, i) => (
              <option key={i} value={i}>
                {c.name}
                {c.error
                  ? " (error)"
                  : c.firedClauses.length
                    ? ` → fires ${c.firedClauses.join(", ")}`
                    : " → no match"}
              </option>
            ))}
          </select>
        </label>
      )}
      <textarea
        aria-label="Sample record JSON"
        className={`${styles.control} ${styles.code}`}
        value={sample}
        onChange={(e) => {
          setSample(e.target.value);
          setPicked("");
        }}
      />
      <div className={styles.actionsStart}>
        <Button variant="secondary" disabled={busy} onClick={() => void test()}>
          {busy ? "Evaluating…" : "Evaluate"}
        </Button>
      </div>
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
      {result && result.firedRules.length === 0 && (
        <div className={`${styles.result} ${styles.resultMiss}`} role="status">
          <strong>No rule fired.</strong>
          <p className={styles.muted}>
            {failed.length > 0
              ? "These conditions are not true for this record:"
              : "At least one condition is not true for this record, or a field it reads is missing."}
          </p>
          {failed.length > 0 && (
            <ul className={styles.checks}>
              {failed.map((c, i) => (
                <li key={i}>
                  <code>{c.condition}</code>
                  {c.clauseReference && (
                    <span className={styles.muted}> · {c.clauseReference}</span>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {result?.firedRules.map((rule, index) => (
        <article key={index} className={`${styles.result} ${styles.resultHit}`}>
          <span className={styles.matched}>Rule matched</span>{" "}
          <strong>Clause: {rule.clauseReference}</strong>
          {rule.outputs?.outcome !== undefined && (
            <p>Outcome: {outcomeLabel(rule.outputs.outcome)}</p>
          )}
          {checks.filter((c) => c.clauseReference === rule.clauseReference)
            .length > 0 && (
            <ul className={styles.checks}>
              {checks
                .filter((c) => c.clauseReference === rule.clauseReference)
                .map((c, i) => (
                  <li key={i}>
                    ✓ <code>{c.condition}</code>
                  </li>
                ))}
            </ul>
          )}
        </article>
      ))}
    </section>
  );
}

type DiffVersion = Pick<
  RuleSetVersion,
  "id" | "version" | "status" | "dmnXml"
> &
  Partial<RuleSetVersion>;
type ReadableRow = { condition: string; outcome: string; clause: string };

/** The decision-table rows of a Studio DMN definition, read back as condition, outcome and clause. */
export function readableRows(dmnXml: string | null | undefined): ReadableRow[] {
  if (!dmnXml || typeof DOMParser === "undefined") return [];
  const doc = new DOMParser().parseFromString(dmnXml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length > 0) return [];
  const inputs = [...doc.getElementsByTagName("input")].map(
    (input) => input.getElementsByTagName("text")[0]?.textContent?.trim() ?? "",
  );
  const unquote = (v: string) => v.replace(/^"(.*)"$/, "$1");
  return [...doc.getElementsByTagName("rule")]
    .map((rule) => {
      const entries = [...rule.getElementsByTagName("inputEntry")].map(
        (e) => e.textContent?.trim() ?? "",
      );
      const outputs = [...rule.getElementsByTagName("outputEntry")].map((e) =>
        unquote(e.textContent?.trim() ?? ""),
      );
      const condition = entries
        .map((entry, i) =>
          entry === "true"
            ? inputs[i]
            : entry !== "-"
              ? `${inputs[i]} ${entry}`
              : "",
        )
        .filter(Boolean)
        .join(" and ");
      return {
        condition,
        outcome: outputs[0] ?? "",
        clause: outputs[outputs.length - 1] ?? "",
      };
    })
    .filter((r) => r.condition);
}

const rowText = (r: ReadableRow) =>
  `IF ${r.condition} THEN ${r.outcome} (${r.clause})`;

export function VersionDiff({ versions }: { versions: DiffVersion[] }) {
  const sorted = [...versions].sort(
    (x, y) => (x.version ?? 0) - (y.version ?? 0),
  );
  const [left, setLeft] = useState("");
  const [right, setRight] = useState("");
  useEffect(() => {
    if (sorted.length >= 2) {
      setLeft((l) => l || (sorted[sorted.length - 2]?.id ?? ""));
      setRight((r) => r || (sorted[sorted.length - 1]?.id ?? ""));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [versions]);
  const a = versions.find((v) => v.id === left);
  const b = versions.find((v) => v.id === right);
  const before = readableRows(a?.dmnXml).map(rowText);
  const after = readableRows(b?.dmnXml).map(rowText);
  const readable = before.length > 0 || after.length > 0;
  const added = readable
    ? after.filter((r) => !before.includes(r))
    : (b?.dmnXml ?? "")
        .split("\n")
        .filter((line) => !(a?.dmnXml ?? "").includes(line));
  const removed = readable ? before.filter((r) => !after.includes(r)) : [];
  const only = sorted.length === 1 ? sorted[0] : undefined;
  return (
    <section className={styles.card}>
      <h2>Version history</h2>
      {versions.length === 0 && (
        <p className={styles.muted}>No versions yet.</p>
      )}
      {versions.length > 0 && (
        <ol className={styles.versions}>
          {[...sorted].reverse().map((v) => (
            <li key={v.id}>
              <span>
                <strong>v{v.version}</strong> · effective{" "}
                {v.effectiveFrom ?? "—"}
                {v.effectiveTo ? ` to ${v.effectiveTo}` : ""}
              </span>
              <StatusBadge status={v.status} />
            </li>
          ))}
        </ol>
      )}
      {only && (
        <>
          <p className={styles.muted}>
            This is the first version. Save another version to compare changes
            here.
          </p>
          {readableRows(only.dmnXml).map((r, i) => (
            <p key={i} className={styles.code}>
              {rowText(r)}
            </p>
          ))}
        </>
      )}
      {sorted.length >= 2 && (
        <>
          <div className={styles.grid2}>
            <label className={styles.label}>
              Compare
              <select
                aria-label="Earlier version"
                className={styles.control}
                value={left}
                onChange={(e) => setLeft(e.target.value)}
              >
                <option value="">Select version</option>
                {sorted.map((v) => (
                  <option key={v.id} value={v.id ?? ""}>
                    v{v.version} {v.status}
                  </option>
                ))}
              </select>
            </label>
            <label className={styles.label}>
              with
              <select
                aria-label="Later version"
                className={styles.control}
                value={right}
                onChange={(e) => setRight(e.target.value)}
              >
                <option value="">Select version</option>
                {sorted.map((v) => (
                  <option key={v.id} value={v.id ?? ""}>
                    v{v.version} {v.status}
                  </option>
                ))}
              </select>
            </label>
          </div>
          {a && b && (
            <div className={styles.diff}>
              {added.length === 0 && removed.length === 0 ? (
                <p>No decision-table changes</p>
              ) : (
                <>
                  {removed.map((r, i) => (
                    <p key={`r${i}`} className={styles.removed}>
                      − {r}
                    </p>
                  ))}
                  {readable ? (
                    added.map((r, i) => (
                      <p key={`a${i}`} className={styles.added}>
                        + {r}
                      </p>
                    ))
                  ) : (
                    <pre>{added.join("\n")}</pre>
                  )}
                </>
              )}
            </div>
          )}
        </>
      )}
    </section>
  );
}

/** Submit, approve and reject one rule set version (maker-checker: the approver is never its author or submitter). */
export function RuleApprovalActions({
  rule,
}: {
  rule: Pick<RuleSetSummary, "id" | "status">;
}) {
  const session = useSession();
  const queries = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const act = async (
    action: "submit" | "approve" | "reject",
    body?: unknown,
  ) => {
    setBusy(true);
    setError(null);
    try {
      const response = await sendJson(
        `/api/rule-sets/${rule.id ?? ""}/${action}`,
        "POST",
        body,
      );
      if (!response.ok)
        throw await problem(response, `Unable to ${action} the rule set`);
      await queries.invalidateQueries({ queryKey: ["rule-sets"] });
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : `Unable to ${action} the rule set`,
      );
    } finally {
      setBusy(false);
    }
  };
  const reject = () => {
    const reason = window.prompt("Reason for rejecting this rule set");
    if (reason) void act("reject", { reason });
  };
  return (
    <>
      {rule.status === "DRAFT" && session.can("PLATFORM_SETTINGS") && (
        <Button
          variant="secondary"
          disabled={busy}
          onClick={() => void act("submit")}
        >
          Submit for approval
        </Button>
      )}
      {rule.status === "PENDING_APPROVAL" && session.can("RULES_APPROVE") && (
        <>
          <Button disabled={busy} onClick={() => void act("approve", {})}>
            Approve
          </Button>
          <Button variant="secondary" disabled={busy} onClick={reject}>
            Reject
          </Button>
        </>
      )}
      {error && (
        <span role="alert" className={styles.error}>
          {error}
        </span>
      )}
    </>
  );
}

/** Field options grouped by entity ("Resident · Gender" is listed as "Gender" under Resident). */
function FieldOptions({ fields }: { fields: RuleField[] }) {
  const groups = new Map<string, RuleField[]>();
  for (const field of fields) {
    const label = field.label ?? field.name ?? "";
    const entity = label.includes(" · ")
      ? (label.split(" · ")[0] ?? "Other")
      : "Other";
    groups.set(entity, [...(groups.get(entity) ?? []), field]);
  }
  return (
    <>
      {[...groups.entries()].map(([entity, list]) => (
        <optgroup key={entity} label={entity}>
          {list.map((f) => {
            const label = f.label ?? f.name ?? "";
            return (
              <option key={f.name ?? ""} value={f.name ?? ""}>
                {label.includes(" · ")
                  ? label.slice(label.indexOf(" · ") + 3)
                  : label}
              </option>
            );
          })}
        </optgroup>
      ))}
    </>
  );
}

/** The value control for one condition, from the operator's arity and the field's data type. */
function ValueControl({
  condition,
  field,
  index,
  invalid,
  onChange,
}: {
  condition: Condition;
  field: RuleField | undefined;
  index: number;
  invalid: boolean;
  onChange: (next: Condition) => void;
}) {
  const operator = operatorsOf(field).find(
    (o) => o.code === condition.operator,
  );
  const type = dataTypeOf(field);
  const choices = choicesOf(field);
  const n = index + 1;
  if (!field || !operator)
    return <span className={styles.placeholder}>Choose a field first</span>;
  if (operator.arity === "NONE")
    return <span className={styles.placeholder}>No value needed</span>;
  const single = (value: string, label: string, set: (v: string) => void) =>
    type === "CODE" && choices.length > 0 ? (
      <select
        aria-label={label}
        aria-invalid={invalid && !value}
        className={styles.control}
        value={value}
        onChange={(e) => set(e.target.value)}
      >
        <option value="">Choose a value</option>
        {choices.map((c) => (
          <option key={c.code} value={c.code}>
            {c.label}
          </option>
        ))}
      </select>
    ) : (
      <input
        aria-label={label}
        aria-invalid={invalid}
        className={styles.control}
        type={type === "NUMBER" ? "number" : type === "DATE" ? "date" : "text"}
        inputMode={type === "NUMBER" ? "decimal" : undefined}
        placeholder={
          type === "NUMBER"
            ? "e.g. 60"
            : type === "TEXT" || type === "CODE"
              ? "Value"
              : undefined
        }
        value={value}
        onChange={(e) => set(e.target.value)}
      />
    );
  if (operator.arity === "ONE")
    return single(condition.value, `Value ${n}`, (v) =>
      onChange({ ...condition, value: v }),
    );
  if (operator.arity === "TWO")
    return (
      <div className={styles.range}>
        {single(condition.value, `Value ${n} from`, (v) =>
          onChange({ ...condition, value: v }),
        )}
        <span aria-hidden="true">and</span>
        {single(condition.valueTo, `Value ${n} to`, (v) =>
          onChange({ ...condition, valueTo: v }),
        )}
      </div>
    );
  if (choices.length === 0)
    return (
      <input
        aria-label={`Values ${n}`}
        aria-invalid={invalid}
        className={styles.control}
        placeholder="Values, separated by commas"
        value={condition.values.join(", ")}
        onChange={(e) =>
          onChange({
            ...condition,
            values: e.target.value
              .split(",")
              .map((v) => v.trim())
              .filter(Boolean),
          })
        }
      />
    );
  return (
    <fieldset className={styles.choices} aria-label={`Values ${n}`}>
      {choices.map((c) => (
        <label key={c.code} className={styles.chip}>
          <input
            type="checkbox"
            checked={condition.values.includes(c.code)}
            onChange={(e) =>
              onChange({
                ...condition,
                values: e.target.checked
                  ? [...condition.values, c.code]
                  : condition.values.filter((v) => v !== c.code),
              })
            }
          />
          {c.label}
        </label>
      ))}
    </fieldset>
  );
}

function ConditionBuilder({
  fields,
  value,
  onChange,
  showErrors,
}: {
  fields: RuleField[];
  value: Condition[];
  onChange: (rows: Condition[]) => void;
  showErrors: boolean;
}) {
  const set = (i: number, next: Condition) =>
    onChange(value.map((row, n) => (n === i ? next : row)));
  return (
    <>
      <div className={styles.conditionHeader} aria-hidden="true">
        <span />
        <span>Field</span>
        <span>Operator</span>
        <span>Value</span>
        <span />
      </div>
      <ol className={styles.conditions}>
        {value.map((row, i) => {
          const field = fields.find((f) => f.name === row.field);
          const error = showErrors ? problemOf(row, fields) : null;
          return (
            <li key={row.id} className={styles.conditionRow}>
              <span className={styles.joiner}>{i === 0 ? "If" : "and"}</span>
              <select
                aria-label={`Field ${i + 1}`}
                aria-invalid={!!error && !field}
                className={styles.control}
                value={row.field}
                onChange={(e) =>
                  set(
                    i,
                    withField(
                      row,
                      fields.find((f) => f.name === e.target.value),
                    ),
                  )
                }
              >
                <option value="">Choose a field</option>
                <FieldOptions fields={fields} />
              </select>
              <select
                aria-label={`Operator ${i + 1}`}
                className={styles.control}
                value={row.operator}
                disabled={!field}
                onChange={(e) =>
                  set(i, withOperator(row, e.target.value, field))
                }
              >
                {!field && <option value="">Operator</option>}
                {field &&
                  operatorsOf(field).map((o) => (
                    <option key={o.code} value={o.code}>
                      {o.label}
                    </option>
                  ))}
              </select>
              <ValueControl
                condition={row}
                field={field}
                index={i}
                invalid={!!error}
                onChange={(next) => set(i, next)}
              />
              <button
                type="button"
                className={styles.remove}
                aria-label={`Remove condition ${i + 1}`}
                title="Remove condition"
                disabled={value.length === 1}
                onClick={() => onChange(value.filter((_, n) => n !== i))}
              >
                ×
              </button>
              {error && (
                <p className={styles.rowError} role="alert">
                  Condition {i + 1}: {error}
                </p>
              )}
            </li>
          );
        })}
      </ol>
      <Button
        variant="secondary"
        onClick={() =>
          onChange([
            ...value,
            newCondition(Math.max(0, ...value.map((v) => v.id)) + 1),
          ])
        }
      >
        + Add condition
      </Button>
    </>
  );
}

/** A new DRAFT version of a rule set; it still needs submitting and another user's approval. */
export function RuleSetDraftForm({
  fields,
  initial,
  onSaved,
  onCancel,
}: {
  fields: RuleField[];
  initial?: Partial<Pick<RuleTableDraft, "type" | "ownerType" | "ownerId">>;
  onSaved?: (rule: RuleSetVersion) => void;
  onCancel?: () => void;
}) {
  const queries = useQueryClient();
  const id = useId();
  const schemes = useQuery({
    queryKey: ["scheme-packs"],
    queryFn: () => readJson<SchemePackVersion[]>("/api/scheme-packs"),
  });
  const [type, setType] = useState<RuleType>(initial?.type ?? "ELIGIBILITY");
  const [ownerType, setOwnerType] = useState<OwnerType>(
    initial?.ownerType ?? "SCHEME",
  );
  const [ownerId, setOwnerId] = useState(initial?.ownerId ?? "");
  const [effectiveFrom, setEffectiveFrom] = useState("");
  const [effectiveTo, setEffectiveTo] = useState("");
  const [mode, setMode] = useState<"builder" | "feel">(
    fields.length > 0 ? "builder" : "feel",
  );
  const [conditions, setConditions] = useState<Condition[]>([newCondition(1)]);
  const [feel, setFeel] = useState("");
  const [output, setOutput] = useState("");
  const [clause, setClause] = useState("");
  const [showErrors, setShowErrors] = useState(false);
  const [saving, setSaving] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  const outcomes = OUTCOMES_BY_TYPE[type];
  const schemeList = (schemes.data ?? []).filter((s) => s.schemeId);
  const ownerPicker = ownerType === "SCHEME" && schemeList.length > 0;

  const problems: string[] = [];
  if (!ownerId.trim())
    problems.push(
      `Choose the ${OWNER_LABELS[ownerType].toLowerCase()} this rule applies to.`,
    );
  if (!effectiveFrom) problems.push("Enter the date the rule takes effect.");
  if (effectiveFrom && effectiveTo && effectiveTo < effectiveFrom)
    problems.push("The end date must be on or after the start date.");
  if (mode === "builder")
    conditions.forEach((c, i) => {
      const p = problemOf(c, fields);
      if (p) problems.push(`Condition ${i + 1}: ${p}`);
    });
  else if (!feel.trim()) problems.push("Enter a condition.");
  if (!output.trim()) problems.push("Choose an outcome.");
  if (!clause.trim()) problems.push("Enter the policy or clause reference.");

  const save = async () => {
    setServerError(null);
    setShowErrors(true);
    if (problems.length > 0) return;
    setSaving(true);
    try {
      const row =
        mode === "builder"
          ? {
              condition: null,
              conditions: conditions.map((c) => toPayload(c, fields)),
              output: output.trim(),
              clauseReference: clause.trim(),
            }
          : {
              condition: feel.trim(),
              conditions: [],
              output: output.trim(),
              clauseReference: clause.trim(),
            };
      const draft = {
        type,
        ownerType,
        ownerId: ownerId.trim(),
        effectiveFrom,
        effectiveTo: effectiveTo || null,
        rows: [row],
      } as unknown as RuleTableDraft;
      const response = await sendJson("/api/rule-sets/table", "POST", draft);
      if (!response.ok)
        throw await problem(response, "Unable to save the draft.");
      const saved = (await response.json()) as RuleSetVersion;
      await queries.invalidateQueries({ queryKey: ["rule-sets"] });
      onSaved?.(saved);
    } catch (cause) {
      setServerError(
        cause instanceof Error ? cause.message : "Unable to save the draft.",
      );
    } finally {
      setSaving(false);
    }
  };

  const outcomeText =
    outcomes.find((o) => o.value === output)?.label ??
    (output.trim() || "[choose an outcome]");
  const preview =
    mode === "builder"
      ? conditions.map((c) => describe(c, fields))
      : [feel.trim() || "[enter a condition]"];

  return (
    <div className={styles.editor}>
      <section className={styles.card} aria-labelledby={`${id}-details`}>
        <h2 id={`${id}-details`}>Rule details</h2>
        <div className={styles.grid2}>
          <label className={styles.label}>
            Rule type
            <select
              aria-label="Rule type"
              className={styles.control}
              value={type}
              onChange={(e) => {
                setType(e.target.value as RuleType);
                setOutput("");
              }}
            >
              {(Object.keys(TYPE_LABELS) as RuleType[]).map((t) => (
                <option key={t} value={t}>
                  {TYPE_LABELS[t]}
                </option>
              ))}
            </select>
          </label>
          <label className={styles.label}>
            Applies to
            <select
              aria-label="Owner type"
              className={styles.control}
              value={ownerType}
              onChange={(e) => {
                setOwnerType(e.target.value as OwnerType);
                setOwnerId("");
              }}
            >
              {(Object.keys(OWNER_LABELS) as OwnerType[]).map((t) => (
                <option key={t} value={t}>
                  {OWNER_LABELS[t]}
                </option>
              ))}
            </select>
          </label>
          <label className={styles.label}>
            {OWNER_LABELS[ownerType]}
            {ownerPicker ? (
              <select
                aria-label="Owner"
                aria-invalid={showErrors && !ownerId}
                className={styles.control}
                value={ownerId}
                onChange={(e) => setOwnerId(e.target.value)}
              >
                <option value="">Select a scheme</option>
                {schemeList.map((s) => (
                  <option key={s.schemeId} value={s.schemeId ?? ""}>
                    {s.name ? `${s.name} (${s.schemeId})` : s.schemeId}
                  </option>
                ))}
              </select>
            ) : (
              <input
                aria-label="Owner"
                aria-invalid={showErrors && !ownerId.trim()}
                className={styles.control}
                placeholder={
                  ownerType === "SCHEME"
                    ? "Scheme ID"
                    : ownerType === "DEPARTMENT"
                      ? "Department code"
                      : "Source pack ID"
                }
                value={ownerId}
                onChange={(e) => setOwnerId(e.target.value)}
              />
            )}
          </label>
          <div className={styles.dates}>
            <label className={styles.label}>
              Effective from
              <input
                aria-label="Draft effective from"
                aria-invalid={showErrors && !effectiveFrom}
                className={styles.control}
                type="date"
                value={effectiveFrom}
                onChange={(e) => setEffectiveFrom(e.target.value)}
              />
            </label>
            <label className={styles.label}>
              <span>
                Effective to <span className={styles.optional}>(optional)</span>
              </span>
              <input
                aria-label="Draft effective to"
                className={styles.control}
                type="date"
                min={effectiveFrom || undefined}
                value={effectiveTo}
                onChange={(e) => setEffectiveTo(e.target.value)}
              />
            </label>
          </div>
        </div>
      </section>

      <section className={styles.card} aria-labelledby={`${id}-conditions`}>
        <div className={styles.cardHeader}>
          <div>
            <h2 id={`${id}-conditions`}>Conditions</h2>
            <p className={styles.muted}>
              Apply this rule when all conditions are true.
            </p>
          </div>
          {fields.length > 0 && (
            <button
              type="button"
              className={styles.textButton}
              onClick={() => setMode(mode === "builder" ? "feel" : "builder")}
            >
              {mode === "builder"
                ? "Write a FEEL expression instead"
                : "Use the condition builder"}
            </button>
          )}
        </div>
        {mode === "builder" ? (
          <ConditionBuilder
            fields={fields}
            value={conditions}
            onChange={setConditions}
            showErrors={showErrors}
          />
        ) : (
          <label className={styles.label}>
            Condition (FEEL)
            <textarea
              aria-label="Condition"
              aria-invalid={showErrors && !feel.trim()}
              className={`${styles.control} ${styles.code}`}
              placeholder='age.years >= 60 and gender = "FEMALE"'
              value={feel}
              onChange={(e) => setFeel(e.target.value)}
            />
            {fields.length === 0 && (
              <span className={styles.hint}>
                No rule fields are available, so the condition is written as a
                FEEL expression.
              </span>
            )}
          </label>
        )}
      </section>

      <section className={styles.card} aria-labelledby={`${id}-outcome`}>
        <h2 id={`${id}-outcome`}>Outcome</h2>
        <div className={styles.grid2}>
          {outcomes.length > 0 ? (
            <fieldset className={styles.outcomes}>
              <legend className={styles.legend}>
                When the conditions are true
              </legend>
              {outcomes.map((o) => (
                <label
                  key={o.value}
                  className={`${styles.outcome} ${output === o.value ? styles.outcomeSelected : ""}`}
                >
                  <input
                    type="radio"
                    name={`${id}-outcome`}
                    value={o.value}
                    checked={output === o.value}
                    onChange={() => setOutput(o.value)}
                  />
                  <span>
                    <strong>{o.label}</strong>
                    <small>{o.help}</small>
                  </span>
                </label>
              ))}
            </fieldset>
          ) : (
            <label className={styles.label}>
              Output value
              <input
                aria-label="Outcome"
                aria-invalid={showErrors && !output.trim()}
                className={styles.control}
                value={output}
                onChange={(e) => setOutput(e.target.value)}
              />
            </label>
          )}
          <label className={styles.label}>
            Policy / clause reference
            <input
              aria-label="Clause reference"
              aria-invalid={showErrors && !clause.trim()}
              className={styles.control}
              placeholder="e.g. GO 12/2024, para 3(b)"
              value={clause}
              onChange={(e) => setClause(e.target.value)}
            />
            <span className={styles.hint}>
              Shown with every decision this rule makes.
            </span>
          </label>
        </div>
      </section>

      <section
        className={`${styles.card} ${styles.preview}`}
        aria-labelledby={`${id}-preview`}
        aria-live="polite"
      >
        <h2 id={`${id}-preview`}>Rule preview</h2>
        <p className={styles.previewLine}>
          <span className={styles.keyword}>If</span> {preview[0]}
        </p>
        {preview.slice(1).map((line, i) => (
          <p key={i} className={styles.previewLine}>
            <span className={styles.keyword}>and</span> {line}
          </p>
        ))}
        <p className={styles.previewLine}>
          <span className={styles.keyword}>then</span> {SUBJECT[type]}{" "}
          <strong>{outcomeText}</strong>
        </p>
        {clause.trim() && (
          <p className={styles.muted}>Source: {clause.trim()}</p>
        )}
      </section>

      {showErrors && problems.length > 0 && (
        <div className={styles.errorSummary} role="alert">
          <strong>Fix the following before saving:</strong>
          <ul>
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </div>
      )}
      {serverError && (
        <p role="alert" className={styles.errorSummary}>
          {serverError}
        </p>
      )}

      <div className={styles.footer}>
        <span className={styles.muted}>
          Saved as a draft. It takes effect only after another user approves it.
        </span>
        <div className={styles.footerButtons}>
          <Button variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
          <Button onClick={() => void save()} disabled={saving}>
            {saving ? "Saving…" : "Save draft"}
          </Button>
        </div>
      </div>
    </div>
  );
}

/** History, diff and testing for one logical rule set (type + owner). */
function RuleSetDetail({
  rule,
  onClose,
}: {
  rule: RuleSetSummary;
  onClose: () => void;
}) {
  const history = useQuery({
    queryKey: ["rule-sets", "history", rule.type, rule.ownerType, rule.ownerId],
    queryFn: () =>
      readJson<RuleSetVersion[]>(
        `/api/rule-sets/history?type=${rule.type ?? ""}&ownerType=${rule.ownerType ?? ""}&ownerId=${encodeURIComponent(rule.ownerId ?? "")}`,
      ),
  });
  return (
    <section className={styles.detail} aria-label="Rule set detail">
      <div className={styles.cardHeader}>
        <h2>
          {rule.ownerId} · {rule.type ? TYPE_LABELS[rule.type] : ""} · v
          {rule.version}
        </h2>
        <button type="button" className={styles.textButton} onClick={onClose}>
          Close
        </button>
      </div>
      {rule.requiredAttributes.length > 0 && (
        <p className={styles.muted}>
          Reads: {rule.requiredAttributes.join(", ")}
        </p>
      )}
      <div className={styles.grid2}>
        <RuleTestPanel
          type={rule.type ?? ""}
          ownerType={rule.ownerType ?? ""}
          ownerId={rule.ownerId ?? ""}
          ruleSetId={rule.id ?? undefined}
        />
        <VersionDiff versions={history.data ?? []} />
      </div>
    </section>
  );
}

export function RulesStudioPage() {
  const rules = useQuery({
    queryKey: ["rule-sets"],
    queryFn: () => readJson<RuleSetSummary[]>("/api/rule-sets"),
  });
  const fields = useQuery({
    queryKey: ["rule-sets", "fields"],
    queryFn: () => readJson<RuleField[]>("/api/rule-sets/fields"),
  });
  const session = useSession();
  const [selected, setSelected] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const current = rules.data?.find((r) => r.id === selected);

  if (creating) {
    return (
      <main className={styles.page}>
        <PageHeader
          title="Create business rule"
          description="Build the rule from platform fields. Operators follow each field's type."
        />
        {fields.isLoading ? (
          <p>Loading rule fields…</p>
        ) : fields.isError ? (
          <div className={styles.errorSummary} role="alert">
            Rule fields could not be loaded.{" "}
            <button
              type="button"
              className={styles.textButton}
              onClick={() => void fields.refetch()}
            >
              Retry
            </button>
          </div>
        ) : (
          <RuleSetDraftForm
            fields={fields.data ?? []}
            onCancel={() => setCreating(false)}
            onSaved={(saved) => {
              setCreating(false);
              setSelected(saved.id);
            }}
          />
        )}
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <PageHeader
        title="Rules Studio"
        description="A draft takes effect only after it is submitted and another user with approval rights approves it."
        actions={
          session.can("PLATFORM_SETTINGS") ? (
            <Button onClick={() => setCreating(true)}>+ Create rule</Button>
          ) : undefined
        }
      />
      <section className={styles.card}>
        <h2>Rule sets</h2>
        {rules.isLoading && <p>Loading rule sets…</p>}
        {rules.isError && (
          <p role="alert" className={styles.error}>
            Rule sets could not be loaded.
          </p>
        )}
        {rules.data && rules.data.length === 0 && (
          <p className={styles.empty}>
            No rules yet. Create one to get started.
          </p>
        )}
        {rules.data && rules.data.length > 0 && (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th scope="col">Owner</th>
                  <th scope="col">Type</th>
                  <th scope="col">Version</th>
                  <th scope="col">Status</th>
                  <th scope="col">Effective from</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rules.data.map((rule) => (
                  <tr
                    key={rule.id}
                    className={
                      rule.id === selected ? styles.selectedRow : undefined
                    }
                  >
                    <td>
                      {rule.ownerId}
                      <small className={styles.muted}>
                        {rule.ownerType ? OWNER_LABELS[rule.ownerType] : ""}
                      </small>
                    </td>
                    <td>{rule.type ? TYPE_LABELS[rule.type] : ""}</td>
                    <td>v{rule.version}</td>
                    <td>
                      <StatusBadge status={rule.status} />
                    </td>
                    <td>{rule.effectiveFrom}</td>
                    <td className={styles.rowActions}>
                      <Button
                        variant="secondary"
                        onClick={() => setSelected(rule.id)}
                      >
                        Open
                      </Button>
                      <RuleApprovalActions rule={rule} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {current && (
        <RuleSetDetail rule={current} onClose={() => setSelected(null)} />
      )}
    </main>
  );
}
