import type {
  MatchingAlert,
  MatchingBound,
  MatchingChangedPair,
  MatchingExplanation,
  MatchingExplanationStep,
  MatchingImpact,
  MatchingLabelSummary,
  MatchingProfile,
  MatchingProfileRequest,
  MatchingProposal,
  MatchingRunMetrics,
  MatchingSamplePair,
  MatchingSourceReadiness,
  MatchingTrainingJob,
} from "../../api/types";

export type Profile = MatchingProfile;
export type ProfileRequest = MatchingProfileRequest;
export type TrainingJob = MatchingTrainingJob;
export type SourceReadiness = MatchingSourceReadiness;
export type SamplePair = MatchingSamplePair;
export type LabelSummary = MatchingLabelSummary;
export type Impact = MatchingImpact;
export type ChangedPair = MatchingChangedPair;
export type Explanation = MatchingExplanation;
export type ExplanationStep = MatchingExplanationStep;
export type RunMetrics = MatchingRunMetrics;
export type Alert = MatchingAlert;
export type Bound = MatchingBound;
export type Proposal = MatchingProposal;

/** One trained comparison level: how much it moves a pair towards "same person". */
export interface LevelWeight {
  field: string;
  level: string;
  m: number;
  u: number;
  matchWeight: number;
  plainLanguage: string;
}

export type Comparison = "NAME" | "DOB" | "EXACT";

export interface FieldConfig {
  field: string;
  comparison: Comparison;
  tfAdjust?: boolean;
}

/** The profile configuration the Python module reads (matching/profile.py DEFAULT_CONFIG). */
export interface ProfileConfig {
  fields: FieldConfig[];
  blocking: string[][];
  thresholds: { autoMatch: number; possibleMatch: number };
}

export const DEFAULT_CONFIG: ProfileConfig = {
  fields: [
    { field: "name", comparison: "NAME", tfAdjust: true },
    { field: "date_of_birth", comparison: "DOB" },
    { field: "mobile", comparison: "EXACT" },
    { field: "district_lgd", comparison: "EXACT" },
    { field: "gender", comparison: "EXACT" },
    { field: "relation_name", comparison: "NAME" },
    { field: "national_id", comparison: "EXACT" },
  ],
  blocking: [
    ["name_phonetic", "district_lgd"],
    ["date_of_birth", "gender"],
    ["mobile"],
  ],
  thresholds: { autoMatch: 0.95, possibleMatch: 0.7 },
};

/** Fields a profile can compare and the columns a blocking rule can use. */
export const COMPARABLE_FIELDS = DEFAULT_CONFIG.fields.map((f) => f.field);
export const BLOCKING_COLUMNS = [
  "name_phonetic",
  "name_key",
  "date_of_birth",
  "gender",
  "district_lgd",
  "mobile",
  "national_id",
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Reads a stored configuration, falling back to the defaults for anything missing or malformed. */
export function readConfig(raw: unknown): ProfileConfig {
  if (!isRecord(raw)) {
    return DEFAULT_CONFIG;
  }
  const fields = Array.isArray(raw.fields)
    ? raw.fields.flatMap((entry): FieldConfig[] =>
        isRecord(entry) &&
        typeof entry.field === "string" &&
        (entry.comparison === "NAME" ||
          entry.comparison === "DOB" ||
          entry.comparison === "EXACT")
          ? [
              {
                field: entry.field,
                comparison: entry.comparison,
                tfAdjust: entry.tfAdjust === true,
              },
            ]
          : [],
      )
    : DEFAULT_CONFIG.fields;
  const blocking = Array.isArray(raw.blocking)
    ? raw.blocking.flatMap((rule): string[][] =>
        Array.isArray(rule) && rule.every((c) => typeof c === "string")
          ? [rule as string[]]
          : [],
      )
    : DEFAULT_CONFIG.blocking;
  const t = isRecord(raw.thresholds) ? raw.thresholds : {};
  return {
    fields,
    blocking,
    thresholds: {
      autoMatch:
        typeof t.autoMatch === "number"
          ? t.autoMatch
          : DEFAULT_CONFIG.thresholds.autoMatch,
      possibleMatch:
        typeof t.possibleMatch === "number"
          ? t.possibleMatch
          : DEFAULT_CONFIG.thresholds.possibleMatch,
    },
  };
}

/** The generated request type describes the free-form config as an opaque object map. */
export function toRequestConfig(config: ProfileConfig): ProfileRequest["config"] {
  return config as unknown as ProfileRequest["config"];
}

/** Trained per-level weights stored on a profile. */
export function readWeights(profile: Profile): LevelWeight[] {
  const weights: unknown = profile.weights;
  if (!Array.isArray(weights)) {
    return [];
  }
  return weights.flatMap((w): LevelWeight[] =>
    isRecord(w) &&
    typeof w.field === "string" &&
    typeof w.level === "string" &&
    typeof w.matchWeight === "number" &&
    typeof w.plainLanguage === "string"
      ? [
          {
            field: w.field,
            level: w.level,
            m: Number(w.m),
            u: Number(w.u),
            matchWeight: w.matchWeight,
            plainLanguage: w.plainLanguage,
          },
        ]
      : [],
  );
}

export interface CurvePoint {
  threshold: number;
  pairs: number;
  precision: number;
  recall: number;
}

/** The threshold suggestion from matching/thresholds.py (the endpoint returns it as free-form JSON). */
export interface Suggestion {
  targetPrecision: number;
  autoMatch: number;
  possibleMatch: number;
  expectedAutoMatchPairs: number;
  expectedReviewQueue: number;
  estimatedMissedMatches: number;
  precisionAtAutoMatch: number;
  labelsUsed: number;
  curve: CurvePoint[];
}

export function readSuggestion(raw: unknown): Suggestion | null {
  if (!isRecord(raw) || !Array.isArray(raw.curve)) {
    return null;
  }
  const num = (v: unknown): number => (typeof v === "number" ? v : 0);
  return {
    targetPrecision: num(raw.targetPrecision),
    autoMatch: num(raw.autoMatch),
    possibleMatch: num(raw.possibleMatch),
    expectedAutoMatchPairs: num(raw.expectedAutoMatchPairs),
    expectedReviewQueue: num(raw.expectedReviewQueue),
    estimatedMissedMatches: num(raw.estimatedMissedMatches),
    precisionAtAutoMatch: num(raw.precisionAtAutoMatch),
    labelsUsed: num(raw.labelsUsed),
    curve: raw.curve.flatMap((p): CurvePoint[] =>
      isRecord(p)
        ? [
            {
              threshold: num(p.threshold),
              pairs: num(p.pairs),
              precision: num(p.precision),
              recall: num(p.recall),
            },
          ]
        : [],
    ),
  };
}

export interface BlockingEstimate {
  rules: { rule: string[]; pairs: number }[];
  totalPairs: number;
  allPairs: number;
}

export function readEstimate(raw: unknown): BlockingEstimate | null {
  if (!isRecord(raw) || !Array.isArray(raw.rules)) {
    return null;
  }
  return {
    rules: raw.rules.flatMap((r): BlockingEstimate["rules"] =>
      isRecord(r) && Array.isArray(r.rule) && typeof r.pairs === "number"
        ? [{ rule: r.rule.map(String), pairs: r.pairs }]
        : [],
    ),
    totalPairs: typeof raw.totalPairs === "number" ? raw.totalPairs : 0,
    allPairs: typeof raw.allPairs === "number" ? raw.allPairs : 0,
  };
}

/** Evidence of a sampled pair is a free-form map of [valueA, valueB] pairs. */
export function evidenceValue(
  evidence: SamplePair["evidence"],
  key: string,
): string {
  const entry: unknown = evidence[key];
  return Array.isArray(entry)
    ? entry.map((v) => (v === null || v === undefined ? "—" : String(v))).join("  ·  ")
    : "—";
}
