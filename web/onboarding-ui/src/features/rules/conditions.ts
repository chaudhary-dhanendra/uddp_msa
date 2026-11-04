import type { RuleField } from "../../api/types";

/** Rules Studio condition model. Operators follow a field's data type, which the backend derives from metadata. */
export type DataType = "NUMBER" | "DATE" | "CODE" | "BOOLEAN" | "TEXT";
export type Arity = "NONE" | "ONE" | "TWO" | "MANY";
export type Operator = { code: string; label: string; arity: Arity };
export type Choice = { code: string; label: string };

export type Condition = {
  id: number;
  field: string;
  operator: string;
  value: string;
  valueTo: string;
  values: string[];
};

const PRESENT: Operator = {
  code: "present",
  label: "is provided",
  arity: "NONE",
};
const MISSING: Operator = {
  code: "missing",
  label: "is missing",
  arity: "NONE",
};

/** The same lists the backend serves; used only when a field arrives without its own operators. */
const FALLBACK: Record<DataType, Operator[]> = {
  NUMBER: [
    { code: "eq", label: "equals", arity: "ONE" },
    { code: "neq", label: "does not equal", arity: "ONE" },
    { code: "gt", label: "is greater than", arity: "ONE" },
    { code: "gte", label: "is at least", arity: "ONE" },
    { code: "lt", label: "is less than", arity: "ONE" },
    { code: "lte", label: "is at most", arity: "ONE" },
    { code: "between", label: "is between", arity: "TWO" },
    PRESENT,
    MISSING,
  ],
  DATE: [
    { code: "eq", label: "is on", arity: "ONE" },
    { code: "neq", label: "is not on", arity: "ONE" },
    { code: "lt", label: "is before", arity: "ONE" },
    { code: "lte", label: "is on or before", arity: "ONE" },
    { code: "gt", label: "is after", arity: "ONE" },
    { code: "gte", label: "is on or after", arity: "ONE" },
    { code: "between", label: "is between", arity: "TWO" },
    PRESENT,
    MISSING,
  ],
  CODE: [
    { code: "eq", label: "is", arity: "ONE" },
    { code: "neq", label: "is not", arity: "ONE" },
    { code: "in", label: "is one of", arity: "MANY" },
    { code: "not_in", label: "is not one of", arity: "MANY" },
    PRESENT,
    MISSING,
  ],
  BOOLEAN: [
    { code: "is_true", label: "is yes", arity: "NONE" },
    { code: "is_false", label: "is no", arity: "NONE" },
    PRESENT,
    MISSING,
  ],
  TEXT: [
    { code: "eq", label: "is", arity: "ONE" },
    { code: "neq", label: "is not", arity: "ONE" },
    { code: "contains", label: "contains", arity: "ONE" },
    { code: "starts_with", label: "starts with", arity: "ONE" },
    PRESENT,
    MISSING,
  ],
};

const lower = (v: string | null | undefined) => (v ?? "").trim().toLowerCase();

export function dataTypeOf(field: RuleField | undefined): DataType {
  if (!field) return "TEXT";
  const declared = field.dataType as DataType | null | undefined;
  if (declared && declared in FALLBACK) return declared;
  const type = lower(field.type),
    kind = lower(field.valueKind);
  if (type === "number" || kind === "number") return "NUMBER";
  if (type === "date" || kind === "date") return "DATE";
  if (type === "boolean") return "BOOLEAN";
  if (type === "code" || (field.codeSet ?? "") !== "") return "CODE";
  return "TEXT";
}

export function operatorsOf(field: RuleField | undefined): Operator[] {
  const served = field?.operators as Operator[] | null | undefined;
  return served && served.length > 0 ? served : FALLBACK[dataTypeOf(field)];
}

export function choicesOf(field: RuleField | undefined): Choice[] {
  const values = field?.values as
    | { code: string | null; label: string | null }[]
    | null
    | undefined;
  return (values ?? [])
    .filter((v) => v.code)
    .map((v) => ({ code: v.code ?? "", label: v.label || v.code || "" }));
}

export const newCondition = (id: number): Condition => ({
  id,
  field: "",
  operator: "",
  value: "",
  valueTo: "",
  values: [],
});

/** A field change resets the operator to the field's first one and clears the values. */
export function withField(
  condition: Condition,
  field: RuleField | undefined,
): Condition {
  return {
    ...condition,
    field: field?.name ?? "",
    operator: operatorsOf(field)[0]?.code ?? "",
    value: "",
    valueTo: "",
    values: [],
  };
}

export function withOperator(
  condition: Condition,
  code: string,
  field: RuleField | undefined,
): Condition {
  const before = operatorsOf(field).find(
    (o) => o.code === condition.operator,
  )?.arity;
  const after = operatorsOf(field).find((o) => o.code === code)?.arity;
  return before === after
    ? { ...condition, operator: code }
    : { ...condition, operator: code, value: "", valueTo: "", values: [] };
}

/** What is wrong with one condition, or null when it is complete and valid. */
export function problemOf(
  condition: Condition,
  fields: RuleField[],
): string | null {
  const field = fields.find((f) => f.name === condition.field);
  if (!field) return "Choose a field.";
  const operator = operatorsOf(field).find(
    (o) => o.code === condition.operator,
  );
  if (!operator) return "Choose an operator.";
  const type = dataTypeOf(field);
  const check = (raw: string): string | null => {
    const v = raw.trim();
    if (!v)
      return type === "CODE" && choicesOf(field).length
        ? "Choose a value."
        : "Enter a value.";
    if (type === "NUMBER" && !/^-?\d+(\.\d+)?$/.test(v))
      return "Enter a number.";
    if (type === "DATE" && !/^\d{4}-\d{2}-\d{2}$/.test(v))
      return "Enter a date.";
    return null;
  };
  if (operator.arity === "ONE") return check(condition.value);
  if (operator.arity === "TWO") {
    const problem = check(condition.value) ?? check(condition.valueTo);
    if (problem) return problem;
    const a = condition.value.trim(),
      b = condition.valueTo.trim();
    const after = type === "NUMBER" ? Number(a) > Number(b) : a > b;
    return after ? "The first value must not be after the second." : null;
  }
  if (operator.arity === "MANY" && condition.values.length === 0)
    return "Choose at least one value.";
  return null;
}

const labelOf = (field: RuleField | undefined) => {
  const label = field?.label ?? field?.name ?? "";
  const dot = label.indexOf(" · ");
  return dot >= 0 ? label.slice(dot + 3) : label;
};

const formatDate = (v: string) => {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(v);
  if (!m) return v;
  const date = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return date.toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
    timeZone: "UTC",
  });
};

/** One condition in plain language; parts still missing read as a bracketed placeholder. */
export function describe(condition: Condition, fields: RuleField[]): string {
  const field = fields.find((f) => f.name === condition.field);
  if (!field) return "[choose a field]";
  const operator = operatorsOf(field).find(
    (o) => o.code === condition.operator,
  );
  if (!operator) return `${labelOf(field)} [choose an operator]`;
  const type = dataTypeOf(field),
    choices = choicesOf(field);
  const show = (raw: string) => {
    const v = raw.trim();
    if (!v) return "[value]";
    if (type === "DATE") return formatDate(v);
    if (type === "CODE") return choices.find((c) => c.code === v)?.label ?? v;
    if (type === "TEXT") return `"${v}"`;
    return v;
  };
  const head = `${labelOf(field)} ${operator.label}`;
  switch (operator.arity) {
    case "NONE":
      return head;
    case "ONE":
      return `${head} ${show(condition.value)}`;
    case "TWO":
      return `${head} ${show(condition.value)} and ${show(condition.valueTo)}`;
    case "MANY": {
      const shown = condition.values.map(show);
      if (shown.length === 0) return `${head} [values]`;
      return `${head} ${shown.length === 1 ? shown[0] : `${shown.slice(0, -1).join(", ")} or ${shown.at(-1)}`}`;
    }
  }
}

/** The condition as the backend's RuleTableRow condition (it validates and compiles it to FEEL). */
export function toPayload(condition: Condition, fields: RuleField[]) {
  const arity = operatorsOf(
    fields.find((f) => f.name === condition.field),
  ).find((o) => o.code === condition.operator)?.arity;
  return {
    field: condition.field,
    operator: condition.operator,
    value: arity === "ONE" || arity === "TWO" ? condition.value.trim() : null,
    valueTo: arity === "TWO" ? condition.valueTo.trim() : null,
    values: arity === "MANY" ? condition.values : [],
  };
}
