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

  it("shows the checker the state fields a pending version declares", async () => {
    mockBackend(testUser(["STATE_PACK_VIEW", "STATE_PACK_APPROVE"]), {
      "/api/studio/approvals": () => [{ approvalId: "a1", draftId: "d1", packId: "la-animal-husbandry",
        version: "1.0.1", status: "PENDING_APPROVAL", submittedBy: "data.engineer" }],
      "/api/studio/drafts/d1": () => ({ draftId: "d1", mappings: [
        { sourceField: "OWNER_NAME", entity: "Resident", target: "name", stateField: null },
        { sourceField: "SHEEP", entity: "Resident", target: "extensions.sheep",
          stateField: { label: "Sheep owned", type: "number", golden: true } }] }),
    });
    renderAt("/onboarding-studio/approvals");
    const item = await screen.findByText(/Sheep owned: key sheep from column SHEEP/);
    expect(item.textContent).toContain("carried to the Golden Resident");
  });

  it("lists pack approvals for a user with State Pack access", async () => {
    const received = mockBackend(testUser(["STATE_PACK_VIEW", "STATE_PACK_APPROVE"]), {});
    renderAt("/onboarding-studio/approvals");
    await vi.waitFor(() => expect(received.some((r) => r.path === "/api/studio/approvals")).toBe(true));
  });
});
