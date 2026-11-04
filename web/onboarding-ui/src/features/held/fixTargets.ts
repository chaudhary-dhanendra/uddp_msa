import { translate, catalogs } from "../../i18n/i18n";

/** Where in the pack a reason is fixed, as the API reports it (held records, rejected rows, Studio preview). */
export interface FixTarget {
  kind?: string | null;
  key?: string | null;
}

/** The Onboarding Studio wizard step that edits each kind of pack part. */
export const FIX_STEP: Record<string, number> = {
  PACK_SOURCE: 2,
  SHAPING: 3,
  MAPPING: 4,
  CROSSWALK: 5,
  RULE: 6,
};

/** The fix target a reason code implies, for records stored before targets were recorded. */
export function impliedTarget(reasonCode: string): FixTarget {
  if (reasonCode.startsWith("RULE_FAILED:")) {
    return { kind: "RULE", key: reasonCode.slice("RULE_FAILED:".length) };
  }
  switch (reasonCode) {
    case "UNMAPPED_CODE":
      return { kind: "CROSSWALK" };
    case "LOW_CONFIDENCE":
      return { kind: "SHAPING" };
    case "UNREADABLE_DATE":
    case "MISSING_NAME":
    case "ID_FORMAT":
      return { kind: "MAPPING" };
    default:
      return { kind: "PACK_SOURCE" };
  }
}

/**
 * The link that opens the exact place to fix a reason: the draft's wizard step (with the rule, column or crosswalk
 * named in the URL), or, without a Studio draft, Rules Studio for a rule and the Studio otherwise.
 */
export function fixLink(
  target: FixTarget | null | undefined,
  reasonCode: string,
  draftId?: string | null,
): { label: string; to: string } {
  const resolved = target?.kind ? target : impliedTarget(reasonCode);
  const kind = resolved.kind ?? "PACK_SOURCE";
  const key = resolved.key ?? "";
  const label = translate(catalogs.en ?? {}, `held.fix.${kind}`, { key });
  const query = key ? `?focus=${encodeURIComponent(key)}` : "";
  if (draftId) {
    return { label, to: `/onboarding-studio/drafts/${draftId}/${FIX_STEP[kind] ?? 2}${query}` };
  }
  if (kind === "RULE") {
    return { label, to: `/rules${query}` };
  }
  return { label, to: "/onboarding-studio" };
}
