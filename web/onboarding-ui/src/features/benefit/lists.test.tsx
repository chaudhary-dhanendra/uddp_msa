import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";

const benefit = (n: number, status: string) => ({
  benefitId: `00000000-0000-0000-0000-00000000000${n}`, benefitRecordKey: `DP:pension:PN0${n}`, schemeId: "WIDOW-PENSION",
  schemeName: "Widow pension", departmentCode: "SOCIAL", departmentName: "Social Welfare", benefitType: "ENTITLEMENT",
  benefitForm: "CASH", beneficiaryLevel: "INDIVIDUAL", exclusivityGroup: "SOCIAL-PENSION", stateCode: "DP",
  sourceSystem: "pension", sourceSchemeCode: "WP", sourceBenefitId: `PN0${n}`, status, startDate: "2024-01-01",
  entitlement: null, sourceFamilyId: null, goldenFamilyId: null, lastDisbursement: null, createdAt: null, observedAt: null,
});
const catalogue = { catalogueVersion: "1", departments: [], schemes: [{ schemeId: "WIDOW-PENSION", name: "Widow pension" }, { schemeId: "OLD-AGE-PENSION", name: "Old age pension" }] };

afterEach(() => vi.unstubAllGlobals());

describe("Schemes and benefits", () => {
  it("lists benefits with scheme, status and source lineage, and filters by scheme", async () => {
    const sent = mockBackend(testUser(["BENEFIT_VIEW"]), {
      "/api/v1/schemes": () => catalogue,
      "/api/v1/benefits": () => ({ items: [benefit(1, "ACTIVE"), benefit(2, "CLOSED")], page: 0, size: 50, totalElements: 2, totalPages: 1 }),
    });
    renderAt("/benefits");
    const table = await screen.findByRole("table", { name: "Benefits" });
    const first = within(table).getByRole("link", { name: "DP:pension:PN01" }).closest("tr") as HTMLElement;
    expect(within(first).getByText("Widow pension")).toBeTruthy();
    expect(within(first).getByText("DP · pension · WP #PN01")).toBeTruthy();
    expect(screen.getByText("2 benefits")).toBeTruthy();
    await screen.findByRole("option", { name: "Old age pension (OLD-AGE-PENSION)" });
    await userEvent.selectOptions(screen.getByLabelText("Scheme"), "OLD-AGE-PENSION");
    await vi.waitFor(() => expect(sent.some((r) => r.path === "/api/v1/benefits" && r.search.includes("schemeId=OLD-AGE-PENSION"))).toBe(true));
  });
});

describe("Operational reports", () => {
  it("shows duplicate findings and runs the eligibility report for an as-of date and scheme code", async () => {
    const sent = mockBackend(testUser(["REPORT_VIEW", "BENEFIT_VIEW"]), {
      "/api/v1/schemes": () => catalogue,
      "/api/v1/reports/duplicate-benefits": () => ({ generatedAt: null, catalogueVersion: "1", findingCount: 0, findings: [] }),
      "/api/v1/reports/eligible-not-enrolled": () => ({ asOf: "2026-11-01", catalogueVersion: "1", schemes: [{
        schemeId: "OLD-AGE-PENSION", schemeName: "Old age pension", departmentCode: "SOCIAL", exclusivityGroup: "SOCIAL-PENSION",
        eligibility: null, residentsEvaluated: 14, eligible: 6, enrolled: 5, undetermined: 0, notEnrolledCount: 1,
        notEnrolled: [{ goldenResidentId: "r9", name: "Nganba Luwang", dateOfBirth: "1950-01-01", ageYears: 76, gender: "MALE", stateCodes: ["DP"] }],
      }] }),
    });
    renderAt("/reports");
    expect(await screen.findByText("0 findings")).toBeTruthy();
    expect(screen.getByText("No resident holds two active benefits in one exclusivity group.")).toBeTruthy();
    const date = screen.getByLabelText("As-of date") as HTMLInputElement;
    await userEvent.clear(date);
    await userEvent.type(date, "2026-11-01");
    await screen.findByRole("option", { name: "OLD-AGE-PENSION · Old age pension" });
    await userEvent.selectOptions(screen.getByLabelText("Scheme code"), "OLD-AGE-PENSION");
    await userEvent.click(screen.getByRole("button", { name: "Run report" }));
    expect(await screen.findByRole("link", { name: "Nganba Luwang" })).toBeTruthy();
    const request = sent.find((r) => r.path === "/api/v1/reports/eligible-not-enrolled");
    expect(request?.search).toContain("asOf=2026-11-01");
    expect(request?.search).toContain("schemeId=OLD-AGE-PENSION");
  });
});
