import type { ShapeProposal, ShapeState, ShapingConfig, Step } from "./api";

export interface AliasRow {
  logical: string;
  variant: string;
}

/** What the Shape step lets the user change: which proposals are on, plus aliases added by hand. */
export interface ShapeChoices {
  /** Indexes into the proposals that are switched on. */
  on: number[];
  aliases: AliasRow[];
  /** Saved steps the step cannot show as a proposal or alias; kept as they are. */
  otherLayout: Step[];
  /** Legacy hand-added ditto steps remain executable, but have no manual editor. */
  legacyDittoLayout: Step[];
  otherParse: Step[];
}

const ALIASES = "column-aliases@1.0";

/**
 * A tidy column name for a parser output: "<prefix>_<key>" in snake case ("NAME_BHOTI", "latinGuess" ->
 * name_bhoti_latin_guess). The prefix is always kept, so an output never takes the name of the source column
 * ("MOBILE", "mobile" -> mobile_mobile).
 */
export function outputName(prefix: string, key: string): string {
  const snake = (text: string) =>
    text
      .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
      .replace(/[^A-Za-z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "")
      .toLowerCase();
  const head = snake(prefix);
  const tail = snake(key);
  if (!tail) {
    return head;
  }
  return head ? `${head}_${tail}` : tail;
}
const DITTO = "ditto@1.0";
const HEADER_DETECT = "header-detect@";

/** JSON with every object's keys sorted, so a step saved and read back with its keys in another order still matches. */
const canonical = (value: unknown): string =>
  JSON.stringify(value, (_key, v: unknown) =>
    v && typeof v === "object" && !Array.isArray(v)
      ? Object.fromEntries(Object.entries(v as Record<string, unknown>).sort(([x], [y]) => (x < y ? -1 : x > y ? 1 : 0)))
      : v,
  );
const same = (a: unknown, b: unknown) => canonical(a) === canonical(b);

function onlyKeys(step: Step, keys: string[]): boolean {
  const own = Object.keys(step).sort();
  return same(own, [...keys].sort());
}

/** The choices that reproduce the steps the draft already keeps. */
export function hydrate(state: ShapeState): ShapeChoices {
  const on = state.proposals
    .map((proposal, index) => (proposal.enabled ? index : -1))
    .filter((index) => index >= 0);
  const known = (steps: Step[], section: "layout" | "parse") =>
    steps.filter(
      (step) =>
        !state.proposals.some((p) => p.section === section && same(p.step, step)),
    );
  const aliases: AliasRow[] = [];
  const otherLayout: Step[] = [];
  const legacyDittoLayout: Step[] = [];
  for (const step of known(state.layout, "layout")) {
    if (step.op === ALIASES && onlyKeys(step, ["op", "aliases"])) {
      const map = step.aliases as Record<string, string[]>;
      for (const [logical, variants] of Object.entries(map)) {
        for (const variant of variants) {
          aliases.push({ logical, variant });
        }
      }
    } else if (step.op === DITTO && onlyKeys(step, ["op", "columns"])) {
      legacyDittoLayout.push(step);
    } else {
      otherLayout.push(step);
    }
  }
  return {
    on,
    aliases,
    otherLayout,
    legacyDittoLayout,
    otherParse: known(state.parse, "parse"),
  };
}

/** The layout: and parse: steps for these choices.
 *
 * Column aliases deliberately run after every source-column cleanup step.  Proposals retain
 * their relative order otherwise, so a rule discovered against (for example) `Village` still
 * sees that original header before it is normalized to `VILLAGE`.
 */
export function compose(
  proposals: ShapeProposal[],
  choices: ShapeChoices,
  sheets: string[],
): ShapingConfig {
  const enabled = (section: "layout" | "parse") =>
    proposals
      .filter((p, index) => p.section === section && choices.on.includes(index))
      .map((p) => p.step);
  const baseLayout: Step[] = [
    ...enabled("layout"),
    ...choices.otherLayout,
    ...choices.legacyDittoLayout,
  ];
  const layout: Step[] = [
    ...baseLayout.filter((step) => String(step.op).startsWith(HEADER_DETECT)),
    ...baseLayout.filter(
      (step) => !String(step.op).startsWith(HEADER_DETECT) && step.op !== ALIASES,
    ),
    ...baseLayout.filter((step) => step.op === ALIASES),
  ];
  const aliasMap: Record<string, string[]> = {};
  for (const { logical, variant } of choices.aliases) {
    if (logical.trim() && variant.trim()) {
      (aliasMap[logical.trim()] ??= []).push(variant.trim());
    }
  }
  if (Object.keys(aliasMap).length > 0) {
    layout.push({ op: ALIASES, aliases: aliasMap });
  }
  return {
    layout,
    parse: [...enabled("parse"), ...choices.otherParse],
    sheets,
  };
}
