import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type { DraftConfiguration, StudioDraft } from "../../api/types";

export const studioKeys = {
  status: ["studio", "status"] as const,
  states: ["studio", "states"] as const,
  targets: ["studio", "targets"] as const,
  draft: (id: string) => ["studio", "draft", id] as const,
  profile: (id: string) => ["studio", "profile", id] as const,
  suggestions: (id: string) => ["studio", "suggestions", id] as const,
  preview: (id: string) => ["studio", "preview", id] as const,
  generated: (id: string) => ["studio", "generated", id] as const,
  portability: ["studio", "portability"] as const,
};

export function useStudioStatus() {
  return useQuery({
    queryKey: studioKeys.status,
    queryFn: () => unwrap(api.GET("/api/studio/status")),
  });
}

export function useStudioStates() {
  return useQuery({
    queryKey: studioKeys.states,
    queryFn: () => unwrap(api.GET("/api/studio/states")),
  });
}

export function useMappingTargets() {
  return useQuery({
    queryKey: studioKeys.targets,
    queryFn: () => unwrap(api.GET("/api/studio/mapping-targets")),
    staleTime: Infinity,
  });
}

export function useDraft(draftId: string) {
  return useQuery({
    queryKey: studioKeys.draft(draftId),
    queryFn: () =>
      unwrap(
        api.GET("/api/studio/drafts/{draftId}", {
          params: { path: { draftId } },
        }),
      ),
  });
}

export function useProfile(draftId: string, enabled: boolean) {
  return useQuery({
    queryKey: studioKeys.profile(draftId),
    queryFn: () =>
      unwrap(
        api.GET("/api/studio/drafts/{draftId}/profile", {
          params: { path: { draftId } },
        }),
      ),
    enabled,
  });
}

export function useSuggestions(draftId: string, enabled: boolean) {
  return useQuery({
    queryKey: studioKeys.suggestions(draftId),
    queryFn: () =>
      unwrap(
        api.GET("/api/studio/drafts/{draftId}/suggestions", {
          params: { path: { draftId } },
        }),
      ),
    enabled,
  });
}

export function usePreview(draftId: string) {
  return useQuery({
    queryKey: studioKeys.preview(draftId),
    queryFn: () =>
      unwrap(
        api.GET("/api/studio/drafts/{draftId}/preview", {
          params: { path: { draftId }, query: { rows: 20 } },
        }),
      ),
    staleTime: 0,
  });
}

export function useGenerated(draftId: string) {
  return useQuery({
    queryKey: studioKeys.generated(draftId),
    queryFn: () =>
      unwrap(
        api.GET("/api/studio/drafts/{draftId}/generated", {
          params: { path: { draftId } },
        }),
      ),
    staleTime: 0,
  });
}

/** Saves the Map, Crosswalks or Rules step and refreshes everything derived from the draft. */
export function useSaveConfiguration(draftId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (configuration: DraftConfiguration) =>
      unwrap(
        api.PUT("/api/studio/drafts/{draftId}/configuration", {
          params: { path: { draftId } },
          body: configuration,
        }),
      ),
    onSuccess: (draft) => refreshDraft(queryClient, draftId, draft),
  });
}

export function refreshDraft(
  queryClient: ReturnType<typeof useQueryClient>,
  draftId: string,
  draft: StudioDraft,
) {
  queryClient.setQueryData(studioKeys.draft(draftId), draft);
  void queryClient.invalidateQueries({ queryKey: studioKeys.preview(draftId) });
  void queryClient.invalidateQueries({
    queryKey: studioKeys.generated(draftId),
  });
  void queryClient.invalidateQueries({ queryKey: studioKeys.states });
}
