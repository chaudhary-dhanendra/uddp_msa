import { afterEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";
import { PROFILE_ID, REC_A, REC_B, RUN_ID, profile } from "./testData";

afterEach(() => vi.unstubAllGlobals());

describe("impact preview", () => {
  it("shows merges, splits, Golden IDs, queue size and changed pairs, and says nothing was changed", async () => {
    const user = userEvent.setup();
    const received = mockBackend(testUser(["MATCHING_VIEW"]), {
      "/api/v1/matching/profiles": () => [profile()],
      [`/api/v1/matching/profiles/${PROFILE_ID}/impact-preview`]: () => ({
        baselineRunId: RUN_ID, baselineModel: "resident-match-v1", profileId: PROFILE_ID, autoMatch: 0.95, possibleMatch: 0.7,
        records: 100, baselineAutoPairs: 10, candidateAutoPairs: 12, baselineReviewQueue: 8, candidateReviewQueue: 5,
        newMerges: 3, splits: 1, goldenIdsAffected: 6, constraintsApplied: 2, changedPairCount: 4,
        changedPairs: [{ recordA: REC_A, recordB: REC_B, nameA: "Ram S.", nameB: "R. Singh", baselineDecision: "POSSIBLE_MATCH",
          candidateDecision: "AUTO_MATCH", baselineProbability: 0.8, candidateProbability: 0.97 }],
      }),
    });
    renderAt("/matching-studio?tab=impact");

    await user.selectOptions(await screen.findByLabelText("Profile to preview"), PROFILE_ID);
    await user.click(screen.getByRole("button", { name: "Run preview" }));

    expect(await screen.findByTestId("impact-merges")).toHaveTextContent("3");
    expect(screen.getByTestId("impact-splits")).toHaveTextContent("1");
    expect(screen.getByTestId("impact-goldens")).toHaveTextContent("6");
    expect(screen.getByTestId("impact-queue")).toHaveTextContent("8 → 5");
    expect(screen.getByTestId("impact-constraints")).toHaveTextContent("2");
    expect(screen.getByText("Ram S. / R. Singh")).toBeInTheDocument();
    expect(screen.getByText(/nothing was changed/)).toBeInTheDocument();
    expect(received.filter((r) => r.path.endsWith("/impact-preview"))).toHaveLength(1);
  });
});
