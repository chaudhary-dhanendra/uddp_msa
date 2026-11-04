import { afterEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";
import { REC_A, REC_B, RUN_ID } from "./testData";

afterEach(() => vi.unstubAllGlobals());

const BASE = {
  runId: RUN_ID, recordA: REC_A, recordB: REC_B, modelVersion: "resident-v2", decision: "AUTO_MATCH",
  matchProbability: 0.991, autoMatchThreshold: 0.95, possibleMatchThreshold: 0.7,
};

describe("why matched", () => {
  it("draws the waterfall and explains each field", async () => {
    mockBackend(testUser(["MATCH_EVIDENCE_VIEW"]), {
      "/api/v1/matching/pairs/explanation": () => ({
        ...BASE, matchWeight: 8.4, hasBreakdown: true,
        steps: [
          { label: "Starting point", field: null, valueA: null, valueB: null, weight: -6.6, plainLanguage: "Before looking at any field" },
          { label: "Name", field: "name", valueA: "Ram S.", valueB: "R. Singh", weight: 9.1, plainLanguage: "The name strongly supports the match" },
          { label: "Date of birth", field: "dateOfBirth", valueA: "1980", valueB: "1980", weight: 5.9, plainLanguage: "The date of birth strongly supports the match" },
        ],
      }),
    });
    renderAt(`/matching-studio/why/${RUN_ID}/${REC_A}/${REC_B}`);

    expect(await screen.findByTestId("why-summary")).toHaveTextContent("AUTO_MATCH: probability 0.991");
    expect(screen.getByRole("img", { name: /Match weight builds from -6.6 to 8.4/ })).toBeInTheDocument();
    expect(screen.getByText("The name strongly supports the match", { exact: false })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to the matching run" })).toHaveAttribute("href", `/matching-runs/${RUN_ID}`);
  });

  it("says so when the run used the built-in model", async () => {
    mockBackend(testUser(["MATCH_EVIDENCE_VIEW"]), {
      "/api/v1/matching/pairs/explanation": () => ({ ...BASE, modelVersion: "resident-match-v1", matchWeight: 7.2, hasBreakdown: false, steps: [] }),
    });
    renderAt(`/matching-studio/why/${RUN_ID}/${REC_A}/${REC_B}`);
    expect(await screen.findByText(/built-in model, which does not record a per-field breakdown/)).toBeInTheDocument();
  });
});
