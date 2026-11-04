import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Permission } from "../../api/types";
import { renderAt, testUser } from "../../shared/testBackend";
import { ageOn } from "./index";

/** A request the stub backend received, with its method and JSON body. */
type Sent = { method: string; path: string; search: string; body: unknown };
type Handler = (sent: Sent) => unknown;

/** A signed-in backend answering `routes` by "METHOD /path" (or "/path" for any method); an empty list otherwise. */
function backend(permissions: Permission[], routes: Record<string, Handler>): Sent[] {
  const sent: Sent[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), "http://localhost"), init);
    const url = new URL(request.url);
    const text = request.method === "GET" ? "" : await request.text();
    const entry = { method: request.method, path: url.pathname, search: url.search, body: text ? JSON.parse(text) : undefined };
    sent.push(entry);
    const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    if (url.pathname === "/api/setup/status") {
      return json({ mode: "READY", source: "LOCAL_STORE", settings: null, settingsFile: "", environmentVariablesPresent: false,
        schemaVersion: "21", onboardingInProgress: false, resetAllowed: false, problem: null, adminRequired: false });
    }
    if (url.pathname === "/api/v1/auth/me") return json(testUser(permissions));
    const route = routes[`${request.method} ${url.pathname}`] ?? routes[url.pathname];
    return json(route ? route(entry) : []);
  }));
  return sent;
}

const details = { classification: null, goReference: "GO-12", benefitType: "ENTITLEMENT", benefitForm: "CASH", unit: null, frequency: "MONTHLY", amount: 1000, quantity: null, codeCrosswalks: [] };
const v1 = { schemeId: "WIDOW-PENSION", id: "v1", version: 1, status: "APPROVED", name: "Widow pension", beneficiaryLevel: "INDIVIDUAL", owner: "SOCIAL", effectiveFrom: "2020-01-01", effectiveTo: null, eligibilityRuleSetId: "rule-v1", implementingDepartments: ["SOCIAL"], sourcePacks: [], exclusivityGroups: ["SOCIAL-PENSION"], configuration: "{}", details };
const v2 = { ...v1, id: "v2", version: 2, status: "DRAFT", effectiveFrom: "2020-01-01", eligibilityRuleSetId: "rule-v1" };
const oldAge = { ...v1, schemeId: "OLD-AGE-PENSION", id: "o1", name: "Old age pension" };
const rules = [
  { id: "rule-v1", type: "ELIGIBILITY", ownerType: "SCHEME", ownerId: "WIDOW-PENSION", version: 1, status: "RETIRED", effectiveFrom: "2020-01-01", effectiveTo: null, requiredAttributes: ["age.years"] },
  { id: "rule-v3", type: "ELIGIBILITY", ownerType: "SCHEME", ownerId: "WIDOW-PENSION", version: 3, status: "APPROVED", effectiveFrom: "2026-11-01", effectiveTo: null, requiredAttributes: ["age.years", "gender"] },
  { id: "rule-oap", type: "ELIGIBILITY", ownerType: "SCHEME", ownerId: "OLD-AGE-PENSION", version: 1, status: "APPROVED", effectiveFrom: "2020-01-01", effectiveTo: null, requiredAttributes: ["age.years"] },
];
const author: Permission[] = ["PLATFORM_SETTINGS", "RULES_APPROVE", "RESIDENT_VIEW", "BENEFIT_VIEW", "REPORT_VIEW"];

afterEach(() => vi.unstubAllGlobals());

describe("Scheme Studio catalogue", () => {
  it("lists one row per scheme with its newest version and the version in effect", async () => {
    backend(author, { "/api/scheme-packs": () => [v2, v1, oldAge] });
    renderAt("/scheme-studio");
    const table = await screen.findByRole("table", { name: "Scheme catalogue" });
    const rows = within(table).getAllByRole("row");
    expect(rows).toHaveLength(3);
    const widow = within(table).getByRole("link", { name: "Widow pension" }).closest("tr") as HTMLElement;
    expect(within(widow).getByText("WIDOW-PENSION")).toBeTruthy();
    expect(within(widow).getByText("v1 from 2020-01-01")).toBeTruthy();
    expect(within(widow).getByText(/v2/)).toBeTruthy();
    expect(screen.getByRole("link", { name: "+ Create scheme" })).toBeTruthy();
  });
});

describe("Create New Version and Save Draft", () => {
  it("copies the version, opens a labelled draft form, and saves the chosen rule and date with the details kept", async () => {
    let current = [v1];
    const sent = backend(author, {
      "GET /api/scheme-packs/WIDOW-PENSION/versions": () => current,
      "POST /api/scheme-packs/WIDOW-PENSION/versions/v1/copy": () => { current = [v2, v1]; return v2; },
      "PUT /api/scheme-packs/WIDOW-PENSION/versions/v2": (s) => { const saved = { ...v2, ...(s.body as object) }; current = [saved, v1]; return saved; },
      "/api/rule-sets": () => rules,
      "/api/v1/departments": () => [{ departmentCode: "SOCIAL", name: "Social Welfare" }],
      "/api/v1/source-packs": () => [{ packId: "demo-pension", status: "PUBLISHED" }],
    });
    renderAt("/scheme-studio/WIDOW-PENSION/v1");
    await userEvent.click(await screen.findByRole("button", { name: "Create New Version" }));

    expect(await screen.findByRole("heading", { level: 1, name: /Edit scheme draft · Widow pension v2/ })).toBeTruthy();
    expect((screen.getByLabelText("Scheme key") as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByLabelText("Scheme name") as HTMLInputElement).value).toBe("Widow pension");
    expect(screen.getByLabelText("Owning Department")).toBeTruthy();
    expect(screen.getByLabelText("Beneficiary level")).toBeTruthy();
    expect(screen.getByLabelText("Benefit type")).toBeTruthy();
    expect(screen.getByLabelText("Benefit form")).toBeTruthy();

    const rule = screen.getByLabelText(/Eligibility rule/) as HTMLSelectElement;
    await screen.findByRole("option", { name: "WIDOW-PENSION v3 (APPROVED)" });
    await userEvent.selectOptions(rule, "rule-v3");
    expect(screen.getByTestId("selected-rule").textContent).toContain("Reads: age.years, gender");

    const from = screen.getByLabelText("Effective from") as HTMLInputElement;
    await userEvent.clear(from);
    await userEvent.type(from, "2026-11-01");
    await userEvent.click(screen.getByRole("button", { name: "Save Draft" }));

    expect(await screen.findByText("Draft saved.")).toBeTruthy();
    const put = sent.find((s) => s.method === "PUT");
    expect(put?.body).toMatchObject({ schemeId: "WIDOW-PENSION", eligibilityRuleSetId: "rule-v3", effectiveFrom: "2026-11-01", details: { goReference: "GO-12", amount: 1000 } });
    expect(screen.getByRole("button", { name: "Submit for approval" })).toBeTruthy();
  });

  it("shows which required fields are missing instead of saving", async () => {
    const sent = backend(author, { "/api/rule-sets": () => rules });
    renderAt("/scheme-studio/new");
    await userEvent.click(await screen.findByRole("button", { name: "Save Draft" }));
    expect(screen.getByText("Enter a scheme key, for example WIDOW-PENSION.")).toBeTruthy();
    expect(screen.getByText("Choose the date this version takes effect.")).toBeTruthy();
    expect(sent.some((s) => s.method === "POST" && s.path === "/api/scheme-packs")).toBe(false);
  });
});

describe("Scheme workspace", () => {
  const preview = {
    evaluated: 14, eligible: 7, notEligible: 7, indeterminate: 0, missingAttributeCounts: {},
    sample: [
      { entityId: "r1", outcome: "ELIGIBLE", missingAttributes: [], clauseReferences: ["GO-12 cl.3"], explanation: "DMN evaluated", exclusivityConflicts: ["OLD-AGE-PENSION"] },
      { entityId: "r2", outcome: "NOT_ELIGIBLE", missingAttributes: [], clauseReferences: [], explanation: "DMN evaluated", exclusivityConflicts: [] },
    ],
  };
  const pending = { ...v2, status: "PENDING_APPROVAL", eligibilityRuleSetId: "rule-v3", effectiveFrom: "2026-11-01" };

  it("shows the age the backend evaluated when the date of birth is masked to a year", async () => {
    backend(author, {
      "/api/scheme-packs/WIDOW-PENSION/versions": () => [pending, v1],
      "/api/rule-sets": () => rules,
      "/api/scheme-packs/WIDOW-PENSION/versions/v2/impact-preview": () => ({ ...preview, sample: [{ ...preview.sample[0], ageYears: 76 }] }),
      "/api/v1/golden-residents": () => ({ items: [
        { goldenResidentId: "r1", name: "Ibemhal D.", dateOfBirth: "1950", gender: "FEMALE" },
      ], page: 0, size: 200, totalElements: 1, totalPages: 1 }),
    });
    renderAt("/scheme-studio/WIDOW-PENSION/v2?tab=impact-preview");
    await userEvent.click(await screen.findByRole("button", { name: "Run impact preview" }));
    const row = (await screen.findByRole("link", { name: "Ibemhal D." })).closest("tr") as HTMLElement;
    expect(within(row).getByText("76")).toBeTruthy();
  });

  it("runs the impact preview with totals, resident names, ages from date of birth and exclusivity conflicts", async () => {
    backend(author, {
      "/api/scheme-packs/WIDOW-PENSION/versions": () => [pending, v1],
      "/api/rule-sets": () => rules,
      "/api/scheme-packs/WIDOW-PENSION/versions/v2/impact-preview": () => preview,
      "/api/v1/golden-residents": () => ({ items: [
        { goldenResidentId: "r1", name: "Ibemhal Devi", dateOfBirth: "1950-03-04", gender: "FEMALE" },
        { goldenResidentId: "r2", name: "Thoiba Meetei", dateOfBirth: "1955-07-01", gender: "MALE" },
      ], page: 0, size: 200, totalElements: 2, totalPages: 1 }),
    });
    renderAt("/scheme-studio/WIDOW-PENSION/v2?tab=impact-preview");
    await userEvent.click(await screen.findByRole("button", { name: "Run impact preview" }));
    expect((await screen.findByTestId("impact-evaluated")).textContent).toContain("14");
    expect(screen.getByTestId("impact-eligible").textContent).toContain("7");
    expect(screen.getByTestId("impact-not-eligible").textContent).toContain("7");
    expect(screen.getByTestId("impact-indeterminate").textContent).toContain("0");
    const row = (await screen.findByRole("link", { name: "Ibemhal Devi" })).closest("tr") as HTMLElement;
    expect(within(row).getByText(String(ageOn("1950-03-04")))).toBeTruthy();
    expect(within(row).getByText("Potential conflicts: OLD-AGE-PENSION")).toBeTruthy();
    expect(within(row).getByText("Eligible")).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText("Sample outcome"), "NOT_ELIGIBLE");
    expect(screen.queryByRole("link", { name: "Ibemhal Devi" })).toBeNull();
    expect(screen.getByRole("link", { name: "Thoiba Meetei" })).toBeTruthy();
  });

  it("lets a steward approve a submitted version and names the linked rule", async () => {
    let current = [pending, v1];
    const sent = backend(["RULES_APPROVE", "RESIDENT_VIEW"], {
      "/api/scheme-packs/WIDOW-PENSION/versions": () => current,
      "/api/rule-sets": () => rules,
      "POST /api/scheme-packs/WIDOW-PENSION/versions/v2/approve": () => { current = [{ ...pending, status: "APPROVED" }, v1]; return current[0]; },
    });
    renderAt("/scheme-studio/WIDOW-PENSION/v2");
    expect(await screen.findByText("WIDOW-PENSION v3 (APPROVED)")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Create New Version" })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Approve" }));
    expect(await screen.findByText(/Approved: this version takes effect/)).toBeTruthy();
    expect(sent.some((s) => s.method === "POST" && s.path.endsWith("/v2/approve"))).toBe(true);
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
  });

  it("explains that impact needs an eligibility rule", async () => {
    backend(author, { "/api/scheme-packs/WIDOW-PENSION/versions": () => [{ ...v1, eligibilityRuleSetId: null }] });
    renderAt("/scheme-studio/WIDOW-PENSION/v1?tab=impact-preview");
    expect(await screen.findByText(/Impact cannot be determined/)).toBeTruthy();
  });
});

describe("ageOn", () => {
  it("counts completed years and ignores partial dates", () => {
    expect(ageOn("1960-11-02", new Date(2026, 10, 2))).toBe(66);
    expect(ageOn("1960-11-03", new Date(2026, 10, 2))).toBe(65);
    expect(ageOn("1960", new Date(2026, 10, 2))).toBeNull();
  });
});
