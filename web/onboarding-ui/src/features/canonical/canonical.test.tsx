import { afterEach, describe, expect, it, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import type { CanonicalResidentDetail } from "../../api/types";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";

const ID = "11111111-1111-1111-1111-111111111111";
const GOLDEN = "22222222-2222-2222-2222-222222222222";

function detail(name: string): CanonicalResidentDetail {
  return {
    summary: {
      canonicalResidentId: ID, batchId: "33333333-3333-3333-3333-333333333333", createdAt: "2026-10-27T00:00:00Z",
      current: true, dateOfBirth: "1980", districtLGD: "100001", gender: "MALE", goldenResidentId: GOLDEN, mobile: "******0001",
      name, rawRecordId: "44444444-4444-4444-4444-444444444444", sourceEntity: "pds", sourceRecordKey: "XX|pds|pds|R1",
      sourceSystem: "pds", stateCode: "XX", statePackVersion: "1.0.0", versionNumber: 2,
    },
    batch: {
      batchId: "33333333-3333-3333-3333-333333333333", canonicalResidents: 3, completedAt: null, dqErrors: 0, dqWarnings: 1,
      durationMs: null, errorMessage: null, pipelineTemplate: "generic-file-ingest.hpl", processingErrors: 0, rawRecords: 3,
      recordCount: 3, sourceSchemaVersion: "1.0", sourceSystem: "pds", sourceType: "file", startedAt: null, stateCode: "XX",
      statePackVersion: "1.0.0", status: "COMPLETED",
    },
    raw: { rawRecordId: "44444444-4444-4444-4444-444444444444", sourceRecordId: "R1", ingestedAt: null, pipelineTemplate: "generic-file-ingest.hpl" },
    fieldMappings: [
      { sourceField: "full_name", sourceValue: name, target: "name", canonicalValue: name, conversion: null, crosswalk: null, required: true },
      { sourceField: "sex", sourceValue: "M", target: "gender", canonicalValue: "MALE", conversion: null, crosswalk: "gender", required: false },
    ],
    sourceFields: [],
    payload: { name },
    extensions: { ration_no: "R1001" },
    warnings: [{ fieldPath: "mobile", rule: "required", message: "mobile is missing", occurredAt: null }],
    dqFindings: [],
    versions: [
      { canonicalResidentId: "55555555-5555-5555-5555-555555555555", versionNumber: 1, current: false, batchId: null, rawRecordId: null, createdAt: null, statePackVersion: "1.0.0" },
      { canonicalResidentId: ID, versionNumber: 2, current: true, batchId: null, rawRecordId: null, createdAt: null, statePackVersion: "1.0.0" },
    ],
  };
}

afterEach(() => vi.unstubAllGlobals());

describe("canonical record page", () => {
  it("shows the record, source against canonical values, quality, lineage and versions, masked until revealed", async () => {
    const received = mockBackend(testUser(["CANONICAL_VIEW", "RESIDENT_VIEW", "RESIDENT_VIEW_FULL"]), {
      [`/api/v1/canonical-residents/${ID}`]: (request) =>
        detail(request.headers.get("X-UDDP-View-Purpose") ? "Ram Singh" : "Ram S."),
      "/api/v1/privacy/reveal-purposes": () => ["Duplicate investigation"],
    });
    renderAt(`/canonical-residents/${ID}`);

    expect(await screen.findByRole("heading", { name: "Ram S." })).toBeInTheDocument();
    const values = screen.getByTestId("canonical-values");
    expect(within(values).getByText("full_name")).toBeInTheDocument();
    expect(within(values).getByText("MALE")).toBeInTheDocument();
    expect(within(screen.getByTestId("canonical-warnings")).getByText("mobile is missing")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Extensions" })).toHaveTextContent("R1001");
    expect(screen.getByLabelText(`Golden Resident ${GOLDEN}`)).toHaveAttribute("href", `/golden-residents/${GOLDEN}`);
    const versions = screen.getByTestId("canonical-versions");
    expect(within(versions).getByText("Superseded")).toBeInTheDocument();
    expect(screen.getAllByText("COMPLETED").length).toBeGreaterThan(0);

    await userEvent.click(screen.getByTestId("reveal"));
    await userEvent.selectOptions(await screen.findByLabelText("Purpose"), "Duplicate investigation");
    await userEvent.click(screen.getByTestId("reveal-confirm"));
    expect(await screen.findByRole("heading", { name: "Ram Singh" })).toBeInTheDocument();
    expect(screen.getByTestId("revealed")).toBeInTheDocument();
    expect(received.some((request) => request.headers.get("X-UDDP-View-Purpose") === "Duplicate investigation")).toBe(true);
  });
});
