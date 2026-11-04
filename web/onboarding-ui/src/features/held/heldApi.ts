import { api, unwrap } from "../../api/client";
import type { components } from "../../api/schema";
import { fixLink, type FixTarget } from "./fixTargets";

type Schemas = components["schemas"];
export type HeldRecord = Schemas["HeldRecord"];
export type HeldReason = Schemas["HeldReason"];
export type HeldEvent = Schemas["HeldEvent"];
export type RectifyResult = Schemas["RectifyResult"];
export type GroupActionResult = Schemas["GroupActionResult"];
export type BulkAction = Schemas["BulkAction"];

/** The generated schema gives another type the name `Group`; the held-record group as the API returns it. */
export interface HeldGroup {
  batchId: string;
  reasonCode: string;
  stateCode: string | null;
  count: number;
  shareOfBatchPercent: number;
  samples: HeldRecord[];
}

export const REJECT_REASONS = [
  { value: "SOURCE_ERROR", label: "Error in the source" },
  { value: "DUPLICATE_OF_OTHER_SOURCE", label: "Duplicate of another source" },
  { value: "OUT_OF_SCOPE", label: "Out of scope" },
  { value: "UNREADABLE", label: "Unreadable" },
  { value: "OTHER", label: "Other (a note is required)" },
];

/** Where in the pack the cause of a reason code is fixed; the target the server stored wins over the code's default. */
export function packFix(reasonCode: string, target?: FixTarget | null, draftId?: string | null) {
  return fixLink(target, reasonCode, draftId);
}

/** The value of a (possibly dotted) field in the payload, as text. */
export function payloadValue(payload: unknown, path: string | null): string {
  if (!path || !payload || typeof payload !== "object") {
    return "";
  }
  const record = payload as Record<string, unknown>;
  let node: unknown = record[path];
  if (!(path in record)) {
    node = record;
    for (const part of path.split(".")) {
      node = node && typeof node === "object" ? (node as Record<string, unknown>)[part] : undefined;
    }
  }
  return node === undefined || node === null ? "" : typeof node === "object" ? JSON.stringify(node) : String(node);
}

export const heldKey = ["held-records"] as const;

export function loadGroups(): Promise<HeldGroup[]> {
  return unwrap(api.GET("/api/v1/held-records/groups")) as unknown as Promise<HeldGroup[]>;
}

export function loadRecord(id: string) {
  return unwrap(api.GET("/api/v1/held-records/{id}", { params: { path: { id } } }));
}

/** Whole days since a timestamp. */
export function ageDays(from: string | null | undefined, now: number = Date.now()): number {
  return from ? Math.max(0, Math.floor((now - new Date(from).getTime()) / 86_400_000)) : 0;
}

/** The oldest sample's age stands for the group's age. */
export function groupAgeDays(group: HeldGroup): number {
  return Math.max(0, ...group.samples.map((s) => ageDays(s.createdAt)));
}
