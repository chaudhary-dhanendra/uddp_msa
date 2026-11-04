import { afterEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";
import { RUN_ID } from "./testData";

afterEach(() => vi.unstubAllGlobals());

const ROUTES = {
  "/api/v1/matching/monitoring/alerts": () => [
    { alertId: "a1", runId: RUN_ID, metric: "match_rate", kind: "DRIFT",
      message: "match_rate moved from 0.3 to 0.62 (107% change) since the previous run",
      observed: 0.62, expected: "within 50% of the previous run", status: "OPEN", createdAt: "2026-10-28T00:00:00Z",
      acknowledgedBy: null },
  ],
  "/api/v1/matching/monitoring/metrics": () => [
    { runId: RUN_ID, startedAt: "2026-10-28T00:00:00Z", modelVersion: "resident-v2", recordsConsidered: 100, autoPairs: 70,
      possiblePairs: 25, noMatchPairs: 900, matchedRecords: 62, matchRate: 0.62, reviewRate: 0.25, scoreHistogram: [],
      acceptanceByBand: { "0.90": { accepted: 33, rejected: 1 } } },
  ],
  "/api/v1/matching/monitoring/bounds": () => [
    { metric: "match_rate", minValue: null, maxValue: 0.9, maxRelativeChange: 0.5, updatedBy: null },
  ],
  "/api/v1/matching/monitoring/proposals": () => [
    { proposalId: "p1", kind: "AUTO_MATCH", currentValue: 0.95, proposedValue: 0.9,
      rationale: "97% of 0.90–0.95 accepted by stewards (34 decisions): lower AUTO_MATCH from 0.95 to 0.9?",
      status: "PENDING_APPROVAL", proposedBy: "maker", createdAt: "2026-10-28T00:00:00Z", decidedBy: null,
      appliedProfileId: null },
  ],
};

describe("monitoring", () => {
  it("shows alerts, run metrics with steward acceptance per band, and suggestions awaiting approval", async () => {
    mockBackend(testUser(["MATCHING_VIEW"]), ROUTES);
    renderAt("/matching-studio?tab=monitoring");

    expect(await screen.findByTestId("alert")).toHaveTextContent("107% change");
    expect(await screen.findByText("62.0%")).toBeInTheDocument();
    expect(screen.getByText("0.90: 97%")).toBeInTheDocument();
    expect(await screen.findByTestId("proposal")).toHaveTextContent("lower AUTO_MATCH from 0.95 to 0.9?");
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    expect(screen.getByText(/needs approval by someone other than maker/)).toBeInTheDocument();
  });

  it("lets a different approver apply a suggestion and a manager acknowledge an alert", async () => {
    const user = userEvent.setup();
    const received = mockBackend(testUser(["MATCHING_VIEW", "MATCHING_MANAGE", "MATCHING_APPROVE"]), ROUTES);
    renderAt("/matching-studio?tab=monitoring");

    await user.click(await screen.findByRole("button", { name: "Acknowledge" }));
    await user.click(await screen.findByRole("button", { name: "Approve" }));

    await vi.waitFor(() => {
      expect(received.some((r) => r.path === "/api/v1/matching/monitoring/alerts/a1/acknowledge")).toBe(true);
      expect(received.some((r) => r.path === "/api/v1/matching/monitoring/proposals/p1/approve")).toBe(true);
    });
  });
});
