import { screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";

const row = {
  heldId: "22222222-2222-2222-2222-222222222222",
  batchId: "11111111-1111-1111-1111-111111111111",
  reference: "•••1001",
  sourceSystem: "pension",
  stateCode: "ST1",
  department: null,
  reasonCode: "SOURCE_ERROR",
  note: "typed wrongly",
  originalReasons: [{ code: "RULE_FAILED:dob", field: "dateOfBirth", message: "bad" }],
  decidedBy: "steward",
  decidedAt: "2026-10-01T10:00:00Z",
  reopened: true,
  currentStatus: "OPEN",
  payloadPurged: false,
};
const page = { items: [row], page: 0, size: 50, totalItems: 1, totalPages: 1, sort: "decidedAt,desc" };
const summary = { total: 4, reopened: 1, perSourcePerWeek: [{ sourceSystem: "pension", weekStart: "2026-09-28", count: 3 }] };

describe("Final rejected records report", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shows the counts and weekly trend on the audit landing page", async () => {
    mockBackend(testUser(["AUDIT_VIEW"] as never), {
      "/api/v1/audit-events/actions": () => [],
      "/api/v1/audit-events": () => ({ items: [], page: 0, size: 50, totalItems: 0, totalPages: 0, sort: "occurredAt,desc" }),
      "/api/v1/audit-reports/final-rejected/summary": () => summary,
    });
    renderAt("/audit");
    const trend = await screen.findByTestId("final-rejected-trend");
    expect(within(trend).getByText("pension")).toBeTruthy();
    expect(within(trend).getByText("2026-09-28")).toBeTruthy();
    expect(screen.getByText(/4 rejected for good · 1 reopened/)).toBeTruthy();
  });

  it("lists masked records with the reopened flag and offers the export only with REPORT_EXPORT", async () => {
    mockBackend(testUser(["AUDIT_VIEW", "REPORT_EXPORT"] as never), {
      "/api/v1/audit-reports/final-rejected": () => page,
    });
    renderAt("/audit?category=final-rejected");
    const table = await screen.findByTestId("final-rejected-table");
    expect(within(table).getByText("•••1001")).toBeTruthy();
    expect(within(table).getByText("yes")).toBeTruthy();
    expect(screen.getByTestId("final-rejected-export").getAttribute("href")).toContain("/final-rejected/export");
  });

  it("hides the export without REPORT_EXPORT", async () => {
    mockBackend(testUser(["AUDIT_VIEW"] as never), { "/api/v1/audit-reports/final-rejected": () => page });
    renderAt("/audit?category=final-rejected");
    await screen.findByTestId("final-rejected-table");
    expect(screen.queryByTestId("final-rejected-export")).toBeNull();
  });
});
