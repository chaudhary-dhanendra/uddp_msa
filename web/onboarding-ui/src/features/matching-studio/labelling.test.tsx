import { afterEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";
import { REC_A, REC_B } from "./testData";

afterEach(() => vi.unstubAllGlobals());

describe("labelling", () => {
  it("samples pairs, records the label of the steward and removes the pair", async () => {
    const user = userEvent.setup();
    const received = mockBackend(testUser(["MATCHING_VIEW", "MATCHING_MANAGE"]), {
      "/api/v1/matching/labels/sample": () => [
        { recordA: REC_A, recordB: REC_B, matchProbability: 0.62, band: 6, decision: "NO_MATCH",
          evidence: { name: ["ram singh", "r. singh"], dateOfBirth: ["1980-03-12", "1980-03-12"], mobile: ["•••3210", "•••3210"] } },
      ],
      "/api/v1/matching/labels": () => ({ recordA: REC_A, recordB: REC_B, label: "SAME" }),
      "/api/v1/matching/labels/summary": () => ({ total: 4, byLabel: { SAME: 3, DIFFERENT: 1 }, byBand: {} }),
    });
    renderAt("/matching-studio?tab=labels");

    expect(await screen.findByTestId("label-summary")).toHaveTextContent("4 labelled so far");
    await user.click(screen.getByRole("button", { name: "Get pairs to label" }));
    const pair = await screen.findByTestId("label-pair");
    expect(pair).toHaveTextContent("Score 0.620");
    expect(pair).toHaveTextContent("ram singh · r. singh");

    await user.click(screen.getByRole("button", { name: "Same person" }));

    await vi.waitFor(() => expect(screen.queryByTestId("label-pair")).not.toBeInTheDocument());
    expect(received.some((r) => r.path === "/api/v1/matching/labels")).toBe(true);
    expect(screen.getByText("No more pairs to label.")).toBeInTheDocument();
  });

  it("hides the labelling buttons from a user who cannot manage matching", async () => {
    const user = userEvent.setup();
    mockBackend(testUser(["MATCHING_VIEW"]), {
      "/api/v1/matching/labels/sample": () => [
        { recordA: REC_A, recordB: REC_B, matchProbability: 0.9, band: 9, decision: "AUTO_MATCH", evidence: {} },
      ],
    });
    renderAt("/matching-studio?tab=labels");
    await user.click(await screen.findByRole("button", { name: "Get pairs to label" }));
    expect(await screen.findByText("You may view but not label pairs.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Same person" })).not.toBeInTheDocument();
  });
});
