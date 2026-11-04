import { useQuery } from "@tanstack/react-query";
import { sendJson } from "../../../api/client";
import type { SchemePackDetails, SchemePackDraft, SchemePackVersion } from "../../../api/types";

/** The eight Scheme Studio steps (A7), in order; the route carries the 1-based step number. */
export const STEPS = [
  "Profile",
  "Sources and codes",
  "Eligibility",
  "Data availability",
  "Test",
  "Impact preview",
  "Submit for approval",
  "Published versions",
] as const;

export async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) throw new Error("Unable to load " + url);
  return response.json() as Promise<T>;
}

export async function failure(response: Response, fallback: string): Promise<Error> {
  const body = await response.json().catch(() => null) as { message?: string } | null;
  return new Error(body?.message ?? fallback);
}

export const emptyDetails = (): SchemePackDetails => ({
  classification: null, goReference: null, benefitType: null, benefitForm: null, unit: null, frequency: null,
  amount: null, quantity: null, codeCrosswalks: [],
});

/** The editable draft of an existing version (the API answers a version; it accepts a draft). */
export function toDraft(version: SchemePackVersion): SchemePackDraft {
  const details = version.details ?? emptyDetails();
  return {
    schemeId: version.schemeId, name: version.name, owner: version.owner, beneficiaryLevel: version.beneficiaryLevel,
    benefitType: details.benefitType ?? "ENTITLEMENT", benefitForm: details.benefitForm ?? "CASH",
    effectiveFrom: version.effectiveFrom, effectiveTo: version.effectiveTo,
    eligibilityRuleSetId: version.eligibilityRuleSetId, implementingDepartments: version.implementingDepartments,
    sourcePacks: version.sourcePacks, exclusivityGroups: version.exclusivityGroups,
    configuration: version.configuration ?? "{}", details,
  };
}

/** Creates a new scheme draft (no version yet) or saves an existing DRAFT version. */
export async function saveDraft(draft: SchemePackDraft, version?: SchemePackVersion | null): Promise<SchemePackVersion> {
  const url = version ? `/api/scheme-packs/${encodeURIComponent(version.schemeId ?? "")}/versions/${version.id ?? ""}` : "/api/scheme-packs";
  const response = await sendJson(url, version ? "PUT" : "POST", draft);
  if (!response.ok) throw await failure(response, "Unable to save the Scheme Pack draft");
  return response.json() as Promise<SchemePackVersion>;
}

export function useSchemeVersions(schemeId: string) {
  return useQuery({
    queryKey: ["scheme-pack", schemeId],
    queryFn: () => getJson<SchemePackVersion[]>(`/api/scheme-packs/${encodeURIComponent(schemeId)}/versions`),
    enabled: schemeId !== "",
  });
}
