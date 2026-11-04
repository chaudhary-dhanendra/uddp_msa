import { afterEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";

afterEach(() => vi.unstubAllGlobals());

const POLICY_ID = "7f0c5a3e-1111-4c2b-9d9e-000000000001";
const POLICY = {
  deterministicTypes: ["AADHAAR"], neverMergeTypes: ["AADHAAR", "VOTER_ID"], evidenceTypes: ["VOTER_ID"],
  familyAnchorType: "RATION_CARD", dobToleranceDays: 366, genderMustAgree: true, noIdentifierAutoMatch: 0.98,
  baseSource: "ST1:pds",
};
const type = (code: string, present: number) => ({
  type: code, present, invalid: 0, duplicated: 0, presentPercent: present, invalidPercent: 0, duplicatedPercent: 0,
});

describe("identity step", () => {
  it("shows coverage and the recommended policy, previews its impact and submits it for approval", async () => {
    const user = userEvent.setup();
    const received = mockBackend(testUser(["MATCHING_VIEW", "IDENTITY_VIEW", "IDENTITY_MANAGE"]), {
      "/api/v1/matching/active-model": () => ({ modelVersion: "resident-match-v1", profile: null }),
      "/api/identity/coverage": () => ({
        sources: [{ stateCode: "ST1", sourceSystem: "pds", records: 100,
          types: [type("AADHAAR", 80), type("RATION_CARD", 90), type("VOTER_ID", 10)] }],
        overallPresentPercent: { AADHAAR: 80, RATION_CARD: 90, VOTER_ID: 10 },
      }),
      "/api/identity/recommendation": () => ({
        policy: POLICY,
        reasons: ["AADHAAR is on 80.0% of records: same value auto-matches"],
        warnings: ["ST1/pds: 2 AADHAAR values are held by more than one record"],
      }),
      "/api/identity/preview": () => ({
        records: 100, autoMerges: 7, newAutoMerges: 3, conflicts: 2, neverMerge: 1, blockedAutoMatches: 1,
        probabilisticReviews: 4, probabilisticRemainder: 20, evidencePairs: 5, coverage: { AADHAAR: 80 },
        matchingRunId: null, warnings: [],
      }),
      "/api/identity/policies": () => ({ policyId: POLICY_ID, version: 2, status: "DRAFT" }),
      [`/api/identity/policies/${POLICY_ID}/submit`]: () => ({ policyId: POLICY_ID, version: 2, status: "PENDING_APPROVAL" }),
    });
    renderAt("/matching-studio?tab=identity");

    expect(await screen.findByText("ST1 / pds")).toBeInTheDocument();
    expect(screen.getAllByTestId("identity-coverage-cell").map((c) => c.textContent))
      .toEqual(["80% / 0% / 0%", "90% / 0% / 0%", "10% / 0% / 0%"]);
    expect(await screen.findByTestId("identity-deterministic")).toHaveTextContent("AADHAAR");
    expect(screen.getByTestId("identity-anchor")).toHaveTextContent("RATION_CARD");
    expect(screen.getByTestId("identity-base")).toHaveTextContent("ST1:pds");
    expect(screen.getByText(/held by more than one record/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Submit for approval" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Preview impact" }));
    expect(await screen.findByTestId("identity-merges")).toHaveTextContent("7");
    expect(screen.getByTestId("identity-new-merges")).toHaveTextContent("3");
    expect(screen.getByTestId("identity-conflicts")).toHaveTextContent("2");
    expect(screen.getByTestId("identity-blocked")).toHaveTextContent("1");
    expect(screen.getByText(/nothing was changed/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Submit for approval" }));
    expect(await screen.findByTestId("identity-submitted")).toHaveTextContent("Version 2 submitted for approval.");
    expect(received.filter((r) => r.path === `/api/identity/policies/${POLICY_ID}/submit`)).toHaveLength(1);
  });
});
