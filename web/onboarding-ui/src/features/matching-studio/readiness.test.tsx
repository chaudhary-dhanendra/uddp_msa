import { afterEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";

afterEach(() => vi.unstubAllGlobals());

describe("data readiness", () => {
  it("shows field completeness per source and flags sparse fields", async () => {
    mockBackend(testUser(["MATCHING_VIEW"]), {
      "/api/v1/matching/active-model": () => ({ modelVersion: "resident-match-v1", profile: null }),
      "/api/v1/matching/readiness": () => [
        { stateCode: "ST1", sourceSystem: "pds", records: 100,
          completeness: { name: 1, date_of_birth: 0.9, mobile: 0.2 } },
      ],
    });
    renderAt("/matching-studio?tab=readiness");

    expect(await screen.findByText("ST1 / pds")).toBeInTheDocument();
    const cells = screen.getAllByTestId("readiness-cell");
    expect(cells.map((c) => c.textContent)).toEqual(["100%", "90%", "20% — low"]);
    expect(cells[2]).toHaveAttribute("data-low", "true");
    expect(screen.getByText(/Model in use: resident-match-v1/)).toBeInTheDocument();
  });
});
