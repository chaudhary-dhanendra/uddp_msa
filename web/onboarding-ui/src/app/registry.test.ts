import { describe, expect, it } from "vitest";
import { createAppRegistry } from "./features";
import { FeatureRegistry } from "./registry";

describe("application registry", () => {
  it("registers all operational views, the Onboarding Studio and Settings", () => {
    const registry = createAppRegistry();
    expect(registry.navigation("operate").map((entry) => entry.id)).toEqual([
      "overview",
      "inbox",
      "runs",
      "canonical",
      "monitoring",
      "matching",
      "golden",
      "families",
      "benefits",
      "reports",
      "stewardship",
      "dq",
      "audit",
    ]);
    expect(
      registry.navigation("configure").find((entry) => entry.id === "studio")
        ?.to,
    ).toBe("/onboarding-studio");
    expect(registry.navigation("configure").map((entry) => entry.id)).toEqual([
      "states",
      "studio",
      "approvals",
      "operations",
      "portability",
      "rules",
      "scheme-studio",
      "settings",
      "users",
      "roles",
      "matching-studio",
      "word-lists",
      "shaping-plugins",
      "geography-master",
    ]);
    expect(registry.routes()).toHaveLength(43);
  });
  it("rejects duplicate routes and navigation identities", () => {
    const registry = new FeatureRegistry();
    registry.register({
      id: "one",
      routes: [{ path: "x", component: () => null }],
      nav: [{ id: "x", labelKey: "x", to: "/x", section: "operate", order: 1 }],
    });
    expect(() =>
      registry.register({
        id: "two",
        routes: [{ path: "x", component: () => null }],
      }),
    ).toThrow("Route already registered");
  });
});
