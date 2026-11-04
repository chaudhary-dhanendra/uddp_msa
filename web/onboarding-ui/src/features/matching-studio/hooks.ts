import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/react-query";
import { api, unwrap, unwrapEmpty } from "../../api/client";
import type { IdentityPolicy } from "../../api/types";
import type { ProfileConfig } from "./types";
import { toRequestConfig } from "./types";

const KEY = ["matching-studio"] as const;

function refresh(client: QueryClient, ...parts: string[]) {
  return client.invalidateQueries({ queryKey: [...KEY, ...parts] });
}

export function useReadiness() {
  return useQuery({
    queryKey: [...KEY, "readiness"],
    queryFn: () => unwrap(api.GET("/api/v1/matching/readiness")),
  });
}

export function useProfiles() {
  return useQuery({
    queryKey: [...KEY, "profiles"],
    queryFn: () => unwrap(api.GET("/api/v1/matching/profiles")),
  });
}

export function useActiveModel() {
  return useQuery({
    queryKey: [...KEY, "active-model"],
    queryFn: () => unwrap(api.GET("/api/v1/matching/active-model")),
  });
}

/** Training jobs of a profile; polled every two seconds while one is still running. */
export function useTrainingJobs(profileId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, "jobs", profileId],
    enabled: profileId !== undefined,
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/matching/profiles/{id}/training-jobs", {
          params: { path: { id: profileId ?? "" } },
        }),
      ),
    refetchInterval: (query) =>
      query.state.data?.some(
        (j) => j.status === "QUEUED" || j.status === "RUNNING",
      )
        ? 2000
        : false,
  });
}

export function useCreateProfile() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { name: string; config: ProfileConfig }) =>
      unwrap(
        api.POST("/api/v1/matching/profiles", {
          body: { name: input.name, config: toRequestConfig(input.config) },
        }),
      ),
    onSuccess: () => refresh(client, "profiles"),
  });
}

export function useUpdateProfile() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { id: string; config: ProfileConfig }) =>
      unwrap(
        api.PUT("/api/v1/matching/profiles/{id}", {
          params: { path: { id: input.id } },
          body: { name: null, config: toRequestConfig(input.config) },
        }),
      ),
    onSuccess: () => refresh(client, "profiles"),
  });
}

export function useProfileAction(
  action: "train" | "submit" | "approve" | "reject",
) {
  const client = useQueryClient();
  const path = `/api/v1/matching/profiles/{id}/${action}` as const;
  return useMutation({
    mutationFn: (id: string) =>
      unwrap(api.POST(path, { params: { path: { id } } })),
    onSuccess: () => {
      void refresh(client, "jobs");
      void refresh(client, "active-model");
      return refresh(client, "profiles");
    },
  });
}

export function useBlockingEstimate() {
  return useMutation({
    mutationFn: (id: string): Promise<unknown> =>
      unwrap(
        api.POST("/api/v1/matching/profiles/{id}/blocking-estimate", {
          params: { path: { id } },
        }),
      ),
  });
}

export function useLabelSample() {
  return useMutation({
    mutationFn: (perBand: number) =>
      unwrap(
        api.GET("/api/v1/matching/labels/sample", {
          params: { query: { perBand } },
        }),
      ),
  });
}

export function useLabel() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: {
      recordA: string;
      recordB: string;
      label: "SAME" | "DIFFERENT" | "UNSURE";
    }) =>
      unwrap(
        api.POST("/api/v1/matching/labels", {
          body: { ...input, runId: null },
        }),
      ),
    onSuccess: () => refresh(client, "label-summary"),
  });
}

export function useLabelSummary() {
  return useQuery({
    queryKey: [...KEY, "label-summary"],
    queryFn: () => unwrap(api.GET("/api/v1/matching/labels/summary")),
  });
}

export function useThresholdSuggestion(targetPrecision: number, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, "suggestion", targetPrecision],
    enabled,
    retry: false,
    queryFn: (): Promise<unknown> =>
      unwrap(
        api.GET("/api/v1/matching/thresholds/suggestion", {
          params: { query: { targetPrecision } },
        }),
      ),
  });
}

export function useImpactPreview() {
  return useMutation({
    mutationFn: (input: {
      id: string;
      autoMatch: number | null;
      possibleMatch: number | null;
    }) =>
      unwrap(
        api.POST("/api/v1/matching/profiles/{id}/impact-preview", {
          params: { path: { id: input.id } },
          body: {
            baselineRunId: null,
            autoMatch: input.autoMatch,
            possibleMatch: input.possibleMatch,
          },
        }),
      ),
  });
}

export function useExplanation(runId: string, a: string, b: string) {
  return useQuery({
    queryKey: [...KEY, "explanation", runId, a, b],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/matching/pairs/explanation", {
          params: { query: { runId, recordA: a, recordB: b } },
        }),
      ),
  });
}

export function useMetrics() {
  return useQuery({
    queryKey: [...KEY, "metrics"],
    queryFn: () => unwrap(api.GET("/api/v1/matching/monitoring/metrics")),
  });
}

export function useAlerts() {
  return useQuery({
    queryKey: [...KEY, "alerts"],
    queryFn: () => unwrap(api.GET("/api/v1/matching/monitoring/alerts")),
  });
}

export function useAcknowledge() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (alertId: string) =>
      unwrapEmpty(
        api.POST("/api/v1/matching/monitoring/alerts/{alertId}/acknowledge", {
          params: { path: { alertId } },
        }),
      ),
    onSuccess: () => refresh(client, "alerts"),
  });
}

export function useBounds() {
  return useQuery({
    queryKey: [...KEY, "bounds"],
    queryFn: () => unwrap(api.GET("/api/v1/matching/monitoring/bounds")),
  });
}

export function useProposals() {
  return useQuery({
    queryKey: [...KEY, "proposals"],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/matching/monitoring/proposals", {
          params: { query: { pendingOnly: true } },
        }),
      ),
  });
}

export function useRefreshProposals() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/matching/monitoring/proposals/refresh", {
          body: { minDecisions: null, minRate: null },
        }),
      ),
    onSuccess: () => refresh(client, "proposals"),
  });
}

export function useDecideProposal(decision: "approve" | "reject") {
  const client = useQueryClient();
  const path =
    `/api/v1/matching/monitoring/proposals/{proposalId}/${decision}` as const;
  return useMutation({
    mutationFn: (proposalId: string) =>
      unwrap(api.POST(path, { params: { path: { proposalId } } })),
    onSuccess: () => {
      void refresh(client, "profiles");
      void refresh(client, "active-model");
      return refresh(client, "proposals");
    },
  });
}

export function useIdentityCoverage() {
  return useQuery({
    queryKey: [...KEY, "identity-coverage"],
    queryFn: () => unwrap(api.GET("/api/identity/coverage")),
  });
}

export function useIdentityRecommendation() {
  return useQuery({
    queryKey: [...KEY, "identity-recommendation"],
    queryFn: () => unwrap(api.GET("/api/identity/recommendation")),
  });
}

/** Dry run of an Identity Policy through the evaluator the Golden build uses. Nothing is written. */
export function useIdentityPreview() {
  return useMutation({
    mutationFn: (policy: IdentityPolicy) =>
      unwrap(api.POST("/api/identity/preview", { body: policy })),
  });
}

/** Saves the policy as a draft version and submits it for a second person's approval. */
export function useSubmitIdentityPolicy() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (policy: IdentityPolicy) => {
      const draft = await unwrap(api.POST("/api/identity/policies", { body: policy }));
      return unwrap(
        api.POST("/api/identity/policies/{id}/submit", {
          params: { path: { id: draft.policyId ?? "" } },
        }),
      );
    },
    onSuccess: () => refresh(client, "identity-policies"),
  });
}
