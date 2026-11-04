/**
 * A benefit entitlement ({ amount | quantity, unit, frequency }, docs/benefit-domain.md) as one readable line, e.g.
 * "1,000 INR · Monthly" or "35 KG · Monthly". Values the line does not know are left out; an entitlement with none of
 * them falls back to its JSON so nothing is hidden.
 */
const FREQUENCIES: Record<string, string> = {
  MONTHLY: "Monthly",
  QUARTERLY: "Quarterly",
  HALF_YEARLY: "Half-yearly",
  ANNUAL: "Annual",
  ONE_TIME: "One time",
};

export function formatEntitlement(entitlement: unknown): string {
  if (entitlement === null || entitlement === undefined) return "—";
  if (typeof entitlement !== "object" || Array.isArray(entitlement)) return String(entitlement);
  const e = entitlement as Record<string, unknown>;
  const value = e.amount ?? e.quantity;
  const parts: string[] = [];
  if (value !== null && value !== undefined && value !== "") {
    const n = typeof value === "number" ? value : Number(value);
    const shown = Number.isFinite(n) ? new Intl.NumberFormat("en-IN", { maximumFractionDigits: 2 }).format(n) : String(value);
    parts.push(e.unit ? `${shown} ${String(e.unit)}` : shown);
  } else if (e.unit) {
    parts.push(String(e.unit));
  }
  if (e.frequency) parts.push(FREQUENCIES[String(e.frequency)] ?? String(e.frequency));
  if (parts.length) return parts.join(" · ");
  return Object.keys(e).length ? JSON.stringify(e) : "—";
}
