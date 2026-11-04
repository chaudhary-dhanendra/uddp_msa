import { afterEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";

afterEach(() => vi.unstubAllGlobals());

const SUGGESTION = {
  targetPrecision: 0.98, autoMatch: 0.96, possibleMatch: 0.7, expectedAutoMatchPairs: 5000, expectedReviewQueue: 420,
  estimatedMissedMatches: 37.4, precisionAtAutoMatch: 0.983, labelsUsed: 310,
  curve: [
    { threshold: 0, pairs: 20000, precision: 0.3, recall: 1 },
    { threshold: 0.7, pairs: 5420, precision: 0.9, recall: 0.97 },
    { threshold: 0.96, pairs: 5000, precision: 0.983, recall: 0.9 },
    { threshold: 1, pairs: 0, precision: 1, recall: 0 },
  ],
};

describe("thresholds", () => {
  it("charts precision and recall and states the queue size for the chosen target", async () => {
    const user = userEvent.setup();
    const received = mockBackend(testUser(["MATCHING_VIEW"]), {
      "/api/v1/matching/thresholds/suggestion": () => SUGGESTION,
    });
    renderAt("/matching-studio?tab=thresholds");

    await user.selectOptions(await screen.findByLabelText("Target precision"), "0.98");
    await user.click(screen.getByRole("button", { name: "Suggest thresholds" }));

    const summary = await screen.findByTestId("threshold-summary");
    expect(summary).toHaveTextContent("AUTO_MATCH from 0.96");
    expect(summary).toHaveTextContent("expected review queue 420 pairs");
    expect(summary).toHaveTextContent("Estimated missed matches below the review band: 37");
    expect(screen.getByRole("img", { name: /At 0.96 precision is 98.3%/ })).toBeInTheDocument();
    expect(received.some((r) => r.search.includes("targetPrecision=0.98"))).toBe(true);
  });

  it("asks for labels when there is nothing to base a suggestion on", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = new URL(input instanceof Request ? input.url : String(input), "http://localhost");
        const json = (body: unknown, status = 200) =>
          new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
        if (url.pathname === "/api/setup/status") {
          return json({ mode: "READY", source: "LOCAL_STORE", settings: null, settingsFile: "",
            environmentVariablesPresent: false, schemaVersion: "21", onboardingInProgress: false, resetAllowed: false,
            problem: null, adminRequired: false });
        }
        if (url.pathname === "/api/v1/auth/me") {
          return json(testUser(["MATCHING_VIEW"]));
        }
        if (url.pathname.endsWith("/suggestion")) {
          return json({ code: "BAD_REQUEST", message: "No labels" }, 400);
        }
        return json([]);
      }),
    );
    renderAt("/matching-studio?tab=thresholds");
    await user.click(await screen.findByRole("button", { name: "Suggest thresholds" }));
    expect(await screen.findByText("No suggestion yet: label some pairs first.")).toBeInTheDocument();
  });
});
