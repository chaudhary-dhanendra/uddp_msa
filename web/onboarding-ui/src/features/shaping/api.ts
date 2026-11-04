import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiFailure, sendJson } from "../../api/client";

/*
 * The shaping endpoints (/api/shaping/* and /api/studio/drafts/{id}/shaping*). They are called with fetch and typed
 * here until openapi.json is regenerated (mvn test -Dtest=OpenApiContractTest -Duddp.openapi.write=true, then
 * npm run gen:api), after which these can move to the generated client.
 */

export type Step = Record<string, unknown>;

export interface WordGroup {
  section: string;
  key: string;
  attrs: Record<string, string>;
  values: string[];
}

export interface WordList {
  id: string;
  description: string;
  groups: WordGroup[];
  pendingChangeId: string | null;
}

export interface ParserTest {
  parser: string;
  value: string;
  values: Record<string, unknown>;
  confidence: number;
  warnings: string[];
}

export interface ShapingChange {
  changeId: string;
  kind: "WORD_LIST" | "PLUGIN";
  action: "UPDATE" | "APPROVE" | "REVOKE";
  target: string;
  payload: Record<string, unknown>;
  status: "DRAFT" | "PENDING_APPROVAL" | "APPROVED" | "REJECTED";
  createdBy: string;
  decidedBy: string | null;
  decisionReason: string | null;
  createdAt: string;
}

export interface BuiltIn {
  kind: "operation" | "parser";
  name: string;
  version: string;
  key: string;
}

export interface PluginInfo {
  fileName: string | null;
  sha256: string;
  name: string | null;
  version: string | null;
  apiVersion: string | null;
  status: "LOADED" | "NOT_APPROVED" | "REJECTED" | "NOT_FOUND";
  reason: string | null;
  approved: boolean;
  provides: string[];
  packs: string[];
  pendingChangeId: string | null;
  pendingAction: "APPROVE" | "REVOKE" | null;
}

export interface ShapeProposal {
  section: "layout" | "parse";
  kind: string;
  sheet: string | null;
  description: string;
  evidenceCount: number;
  evidenceTotal: number;
  step: Step;
  yaml: string;
  enabled: boolean;
}

export interface ShapeState {
  proposals: ShapeProposal[];
  layout: Step[];
  parse: Step[];
  sheets: string[];
}

export interface ShapingConfig {
  layout: Step[];
  parse: Step[];
  sheets?: string[] | null;
}

export interface ShapePreview {
  yaml: string;
  beforeColumns: string[];
  beforeRows: string[][];
  afterColumns: string[];
  afterRows: string[][];
  warnings: string[];
  /** The sheet of each row, for workbooks (null for CSV). */
  beforeSheets?: string[] | null;
  afterSheets?: string[] | null;
}

async function fail(response: Response): Promise<never> {
  const body = (await response.json().catch(() => null)) as {
    message?: string;
  } | null;
  throw new ApiFailure(
    response.status,
    body && body.message ? (body as never) : null,
    `Request failed (${response.status})`,
  );
}

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { credentials: "same-origin" });
  if (!response.ok) {
    return fail(response);
  }
  return (await response.json()) as T;
}

async function writeJson<T>(
  url: string,
  method: "POST" | "PUT",
  body: unknown,
): Promise<T> {
  const response = await sendJson(url, method, body);
  if (!response.ok) {
    return fail(response);
  }
  return (await response.json()) as T;
}

export const shapingKeys = {
  wordLists: ["shaping", "word-lists"] as const,
  changes: (kind: string) => ["shaping", "changes", kind] as const,
  operations: ["shaping", "operations"] as const,
  plugins: ["shaping", "plugins"] as const,
  draft: (id: string) => ["shaping", "draft", id] as const,
  preview: (id: string, config: ShapingConfig) =>
    ["shaping", "preview", id, config] as const,
};

export function useWordLists() {
  return useQuery({
    queryKey: shapingKeys.wordLists,
    queryFn: () => getJson<WordList[]>("/api/shaping/word-lists"),
  });
}

export function useBuiltIns() {
  return useQuery({
    queryKey: shapingKeys.operations,
    queryFn: () =>
      getJson<{ builtIn: BuiltIn[] }>("/api/shaping/operations").then(
        (r) => r.builtIn,
      ),
  });
}

export function usePlugins() {
  return useQuery({
    queryKey: shapingKeys.plugins,
    queryFn: () => getJson<PluginInfo[]>("/api/shaping/plugins"),
  });
}

export function usePendingChanges(kind: "WORD_LIST" | "PLUGIN") {
  return useQuery({
    queryKey: shapingKeys.changes(kind),
    queryFn: () =>
      getJson<ShapingChange[]>(
        `/api/shaping/changes?status=PENDING_APPROVAL&kind=${kind}`,
      ),
  });
}

function useRefreshing() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: ["shaping"] });
}

export function useProposeWordList() {
  const refresh = useRefreshing();
  return useMutation({
    mutationFn: ({ id, groups }: { id: string; groups: WordGroup[] }) =>
      writeJson<ShapingChange>(`/api/shaping/word-lists/${id}/proposals`, "POST", {
        groups,
      }),
    onSuccess: refresh,
  });
}

export function useTestValue() {
  return useMutation({
    mutationFn: (request: { parser: string; value: string; config?: Record<string, unknown> }) =>
      writeJson<ParserTest>("/api/shaping/word-lists/test", "POST", request),
  });
}

export function useProposePlugin() {
  const refresh = useRefreshing();
  return useMutation({
    mutationFn: ({
      sha256,
      action,
    }: {
      sha256: string;
      action: "APPROVE" | "REVOKE";
    }) =>
      writeJson<ShapingChange>(`/api/shaping/plugins/${sha256}/proposals`, "POST", {
        action,
      }),
    onSuccess: refresh,
  });
}

export function useDecideChange() {
  const refresh = useRefreshing();
  return useMutation({
    mutationFn: ({
      changeId,
      decision,
      reason,
    }: {
      changeId: string;
      decision: "approve" | "reject";
      reason?: string;
    }) =>
      writeJson<ShapingChange>(
        `/api/shaping/changes/${changeId}/${decision}`,
        "POST",
        { reason: reason ?? null },
      ),
    onSuccess: refresh,
  });
}

// ---- the Shape step of the Onboarding Studio ---------------------------------------------------------------------

export function useShapeState(draftId: string) {
  return useQuery({
    queryKey: shapingKeys.draft(draftId),
    queryFn: () =>
      getJson<ShapeState>(`/api/studio/drafts/${draftId}/shaping`),
  });
}

export function useShapePreview(draftId: string, config: ShapingConfig, enabled: boolean) {
  return useQuery({
    queryKey: shapingKeys.preview(draftId, config),
    queryFn: () =>
      writeJson<ShapePreview>(
        `/api/studio/drafts/${draftId}/shaping/preview`,
        "POST",
        config,
      ),
    enabled,
    staleTime: 0,
    retry: false,
  });
}

export function useSaveShaping(draftId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (config: ShapingConfig) =>
      writeJson<unknown>(`/api/studio/drafts/${draftId}/shaping`, "PUT", config),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["studio"] });
      void queryClient.invalidateQueries({ queryKey: ["shaping"] });
    },
  });
}
