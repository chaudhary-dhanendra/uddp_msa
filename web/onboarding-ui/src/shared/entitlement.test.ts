import { describe, expect, it } from "vitest";
import { formatEntitlement } from "./entitlement";

describe("formatEntitlement", () => {
  it("reads an amount with its unit and frequency", () => {
    expect(formatEntitlement({ amount: 1000, unit: "INR", frequency: "MONTHLY" })).toBe("1,000 INR · Monthly");
  });
  it("reads a quantity given as text", () => {
    expect(formatEntitlement({ quantity: "35", unit: "KG", frequency: "HALF_YEARLY" })).toBe("35 KG · Half-yearly");
  });
  it("shows a dash for no entitlement and JSON for an unknown shape", () => {
    expect(formatEntitlement(null)).toBe("—");
    expect(formatEntitlement({})).toBe("—");
    expect(formatEntitlement({ slab: "A" })).toBe('{"slab":"A"}');
  });
});
