import type {
  ColumnProfile,
  DraftConfiguration,
  MappingTarget,
  SourceProfile,
  StudioCrosswalk,
  StudioDraft,
  StudioMappingRule,
  StudioSuggestions,
} from "../../api/types";

type Entity = StudioMappingRule["entity"];
const ENTITIES: readonly string[] = ["Resident", "Family", "Benefit"];

/** A target's entity as the mapping rule's entity type (the API declares the same three values). */
export function asEntity(value: string | null): Entity {
  return value !== null && ENTITIES.includes(value) ? (value as Entity) : null;
}

/** `Entity:target`, the key a mapping target is chosen by in the Map step's dropdowns. */
export function targetKey(
  entity: string | null,
  target: string | null,
): string {
  return `${entity ?? ""}:${target ?? ""}`;
}

export function findTarget(
  targets: MappingTarget[],
  key: string,
): MappingTarget | undefined {
  return targets.find((t) => targetKey(t.entity, t.target) === key);
}

/** A rule for a column and target, with the conversion and crosswalk the target's type needs. */
export function ruleFor(
  column: ColumnProfile | undefined,
  target: MappingTarget,
): StudioMappingRule {
  const conversion =
    target.type === "date" && column?.dateFormat
      ? `date:${column.dateFormat}`
      : null;
  return {
    sourceField: column?.name ?? "",
    entity: asEntity(target.entity),
    target: target.target,
    conversion,
    crosswalk: target.crosswalkId ?? null,
    required:
      target.entity === "Resident" && target.target === "name" ? true : null,
    stateField: null,
  };
}

export type StateFieldType = "text" | "number" | "integer" | "boolean" | "date";
export const STATE_FIELD_TYPES: StateFieldType[] = [
  "text",
  "number",
  "integer",
  "boolean",
  "date",
];
const EXTENSIONS = "extensions.";

/** A state field key from a column name: "disability_pct" -> "disabilityPct" (a letter first, at most 63 chars). */
export function stateFieldKey(column: string): string {
  const words = column
    .normalize("NFKD")
    .replace(/[^A-Za-z0-9]+/g, " ")
    .trim()
    .split(/\s+/)
    .filter(Boolean);
  // An all-capitals word (SHEEP, DISABILITY_PCT) is a word, not an acronym inside camelCase.
  const camel = words
    .map((w) => (w === w.toUpperCase() ? w.toLowerCase() : w))
    .map((w, i) =>
      i === 0
        ? w.charAt(0).toLowerCase() + w.slice(1)
        : w.charAt(0).toUpperCase() + w.slice(1),
    )
    .join("");
  const key = /^[a-z]/.test(camel) ? camel : `f${camel}`;
  return (key.startsWith("uddp") ? `f${key}` : key).slice(0, 63);
}

/** Keeps a column as a typed state field of the pack (a Resident extensions.<key> rule), carried to Golden. */
export function stateFieldRule(
  column: ColumnProfile | undefined,
): StudioMappingRule {
  const name = column?.name ?? "";
  const numeric = (column?.topValues ?? []).length > 0 &&
    (column?.topValues ?? []).every((v) => /^-?\d+(\.\d+)?$/.test((v.value ?? "").trim()));
  return {
    sourceField: name,
    entity: "Resident",
    target: `${EXTENSIONS}${stateFieldKey(name)}`,
    conversion: null,
    crosswalk: null,
    required: null,
    stateField: { label: name, type: numeric ? "number" : "text", golden: true },
  };
}

/** The key of a state field rule (its target without "extensions."). */
export function keyOf(rule: StudioMappingRule): string {
  return (rule.target ?? "").startsWith(EXTENSIONS)
    ? (rule.target ?? "").slice(EXTENSIONS.length)
    : "";
}

export function withKey(rule: StudioMappingRule, key: string): StudioMappingRule {
  return { ...rule, target: `${EXTENSIONS}${key}` };
}

/**
 * The configuration the Map step starts from when the draft has none yet: every pre-accepted suggestion (its confidence
 * reached the backend's threshold) applied, every coded column's crosswalk pre-filled. Suggestions that were not
 * pre-accepted are offered in the Map step for review; unsuggested columns get no rule (kept as Resident extensions).
 */
export function initialConfiguration(
  draft: StudioDraft,
  suggestions: StudioSuggestions,
): DraftConfiguration {
  const mappings: StudioMappingRule[] = [];
  for (const column of suggestions.columns) {
    for (const target of column.targets.filter((t) => t.accepted)) {
      mappings.push({
        sourceField: column.column,
        entity: asEntity(target.entity),
        target: target.target,
        conversion: target.conversion,
        crosswalk: target.crosswalk,
        required:
          target.entity === "Resident" && target.target === "name"
            ? true
            : null,
        stateField: null,
      });
    }
  }
  return {
    mappings,
    crosswalks: suggestions.crosswalks,
    rules: draft.rules,
    recordIdField: suggestions.recordIdField,
    recordKeyFields: suggestions.recordKeyFields,
  };
}

/**
 * Crosswalks for the rules' coded targets: kept when the column is unchanged, taken from the suggestions when one
 * exists, otherwise one blank row per distinct value of the column. Unused crosswalks are dropped.
 */
export function crosswalksFor(
  mappings: StudioMappingRule[],
  existing: StudioCrosswalk[],
  suggestions: StudioSuggestions | undefined,
  profile: SourceProfile | undefined,
  targets: MappingTarget[],
): StudioCrosswalk[] {
  const result: StudioCrosswalk[] = [];
  for (const rule of mappings) {
    if (!rule.crosswalk || result.some((c) => c.id === rule.crosswalk)) {
      continue;
    }
    const target = findTarget(targets, targetKey(rule.entity, rule.target));
    const values = (
      profile?.columns.find((c) => c.name === rule.sourceField)?.topValues ?? []
    )
      .map((v) => v.value ?? "")
      .filter((v) => v !== "");
    const sameValues = (c: StudioCrosswalk) =>
      c.entries.length === values.length &&
      c.entries.every((e) => values.includes(e.sourceValue ?? ""));
    const kept = existing.find((c) => c.id === rule.crosswalk && sameValues(c));
    const suggested = suggestions?.crosswalks.find(
      (c) => c.id === rule.crosswalk && sameValues(c),
    );
    if (kept) {
      result.push(kept);
    } else if (suggested) {
      result.push(suggested);
    } else {
      result.push({
        id: rule.crosswalk,
        codeSet: target?.codeSet ?? rule.crosswalk,
        entries: values.map((value) => ({
          sourceValue: value,
          targetValue: null,
          basis: null,
          confidence: null,
          accepted: null,
        })),
      });
    }
  }
  return result;
}

/** Crosswalk rows that still have no platform code. */
export function missingValues(crosswalks: StudioCrosswalk[]): number {
  return crosswalks.reduce(
    (count, c) => count + c.entries.filter((e) => !e.targetValue).length,
    0,
  );
}

/**
 * The record ID and record key columns kept from a saved draft or suggestion, limited to the source's current
 * columns. A name that is no longer a column (the header was renamed or a new file was uploaded) is dropped and
 * returned in {@code dropped}, so the page never sends a column the person cannot see.
 */
export function currentRecordKey(
  recordId: string | null | undefined,
  keys: (string | null)[] | null | undefined,
  columns: string[],
): { recordIdField: string; recordKeyFields: string[]; dropped: string[] } {
  const dropped: string[] = [];
  let recordIdField = recordId ?? "";
  if (recordIdField && !columns.includes(recordIdField)) {
    dropped.push(recordIdField);
    recordIdField = "";
  }
  const recordKeyFields: string[] = [];
  for (const key of keys ?? []) {
    if (key === null || recordKeyFields.includes(key)) {
      continue;
    }
    if (columns.includes(key)) {
      recordKeyFields.push(key);
    } else if (!dropped.includes(key)) {
      dropped.push(key);
    }
  }
  return { recordIdField, recordKeyFields, dropped };
}
