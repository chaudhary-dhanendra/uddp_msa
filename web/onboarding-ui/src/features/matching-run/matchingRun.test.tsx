import { afterEach, describe, expect, it, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import type { MatchingRunDetail, MatchPairDetail, MatchPairSummary, PageMatchPairSummary } from "../../api/types";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";

const RUN = "66666666-6666-6666-6666-666666666666";
const A = "77777777-7777-7777-7777-777777777777";
const B = "88888888-8888-8888-8888-888888888888";

const RUN_DETAIL: MatchingRunDetail = {
  run: {
    runId: RUN, modelVersion: "resident-match-v1", mode: "FULL", status: "COMPLETED", autoMatchThreshold: 0.95,
    possibleMatchThreshold: 0.75, recordsConsidered: 7, clusters: 5, multiMemberClusters: 1, durationMs: 1200,
    startedAt: "2026-10-27T00:00:00Z", completedAt: null, pairs: { autoMatch: 3, possibleMatch: 1, noMatch: 17 },
  },
  clusters: [],
  goldenBuilds: [],
};

function pair(decision: string, nameA: string): MatchPairSummary {
  return { runId: RUN, canonicalRecordA: A, canonicalRecordB: B, decision, matchProbability: 0.987, matchWeight: 9.1,
    nameA, nameB: "Ram S.", stateCodeA: "XA", stateCodeB: "XB" };
}

function page(items: MatchPairSummary[]): PageMatchPairSummary {
  return { items, page: 0, size: 20, totalItems: items.length, totalPages: 1, sort: "matchProbability,desc" };
}

function pairDetail(name: string): MatchPairDetail {
  return {
    pair: pair("AUTO_MATCH", name), autoMatchThreshold: 0.95, possibleMatchThreshold: 0.75, evidence: {},
    recordA: { canonicalResidentId: A, current: true, name, dateOfBirth: "1980", districtLGD: null, gender: "MALE", mobile: null, sourceSystem: "pds", stateCode: "XA" },
    recordB: { canonicalResidentId: B, current: true, name, dateOfBirth: "1980", districtLGD: null, gender: "MALE", mobile: null, sourceSystem: "pension", stateCode: "XB" },
    comparisons: [
      { field: "name", valueA: name, valueB: name, outcome: "EXACT", basis: "jaro-winkler" },
      { field: "dateOfBirth", valueA: "1980", valueB: "1980", outcome: "EXACT", basis: null },
    ],
  };
}

afterEach(() => vi.unstubAllGlobals());

describe("matching run page", () => {
  it("shows the summary and counts, filters pairs by decision and compares a pair side by side", async () => {
    const received = mockBackend(testUser(["MATCH_EVIDENCE_VIEW", "RESIDENT_VIEW_FULL"]), {
      [`/api/v1/matching-runs/${RUN}`]: () => RUN_DETAIL,
      [`/api/v1/matching-runs/${RUN}/pairs`]: (request) =>
        page(request.search.includes("decision=POSSIBLE_MATCH") ? [] : [pair("AUTO_MATCH", "Ram S.")]),
      [`/api/v1/matching-runs/${RUN}/pairs/${A}/${B}`]: (request) =>
        pairDetail(request.headers.get("X-UDDP-View-Purpose") ? "Ram Singh" : "Ram S."),
      "/api/v1/privacy/reveal-purposes": () => ["Duplicate investigation"],
    });
    renderAt(`/matching-runs/${RUN}`);

    expect(await screen.findByTestId("count-auto")).toHaveTextContent("3");
    expect(screen.getByTestId("count-possible")).toHaveTextContent("1");
    expect(screen.getByText("resident-match-v1")).toBeInTheDocument();
    expect(screen.getByText("0.95")).toBeInTheDocument();
    const pairs = await screen.findByTestId("pairs-table");
    expect(within(pairs).getByText("98.7%")).toBeInTheDocument();

    await userEvent.click(within(pairs).getByRole("button", { name: "Compare" }));
    const comparison = await screen.findByTestId("pair-comparison-table");
    expect(within(comparison).getByText("dateOfBirth")).toBeInTheDocument();
    expect(within(comparison).getAllByText("Ram S.")).toHaveLength(2);

    await userEvent.click(screen.getByTestId("reveal"));
    await userEvent.selectOptions(await screen.findByLabelText("Purpose"), "Duplicate investigation");
    await userEvent.click(screen.getByTestId("reveal-confirm"));
    expect(await within(screen.getByTestId("pair-comparison-table")).findAllByText("Ram Singh")).toHaveLength(2);

    await userEvent.selectOptions(screen.getByLabelText("Decision"), "POSSIBLE_MATCH");
    expect(await screen.findByText("No pairs for this decision.")).toBeInTheDocument();
    expect(received.some((request) => request.search.includes("decision=POSSIBLE_MATCH"))).toBe(true);
  });
});
