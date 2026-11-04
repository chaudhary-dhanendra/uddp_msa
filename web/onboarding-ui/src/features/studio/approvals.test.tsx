import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, screen } from "@testing-library/react";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("Pending approvals", () => {
  it("does not ask a steward without State Pack access for pack approvals", async () => {
    const received = mockBackend(testUser(["STEWARDSHIP_VIEW", "STEWARDSHIP_DECIDE", "STEWARDSHIP_APPROVE"]), {});
    renderAt("/onboarding-studio/approvals");
    expect(await screen.findByRole("heading", { name: "Pending approvals" })).toBeTruthy();
    await vi.waitFor(() => expect(received.some((r) => r.path === "/api/stewardship/proposals")).toBe(true));
    expect(received.some((r) => r.path === "/api/studio/approvals")).toBe(false);
    expect(screen.queryByText("Unable to load this view.")).toBeNull();
  });

  it("lists pack approvals for a user with State Pack access", async () => {
    const received = mockBackend(testUser(["STATE_PACK_VIEW", "STATE_PACK_APPROVE"]), {});
    renderAt("/onboarding-studio/approvals");
    await vi.waitFor(() => expect(received.some((r) => r.path === "/api/studio/approvals")).toBe(true));
  });
});
