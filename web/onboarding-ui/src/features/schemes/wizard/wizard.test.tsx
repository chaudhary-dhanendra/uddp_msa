import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { SchemePackVersion } from "../../../api/types";
import { AvailabilityStep, EligibilityStep, ImpactStep, ProfileStep, SourcesStep, SubmitStep, TestStep, VersionsStep } from "./steps";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const departments = [{ departmentCode: "DEPT-1", name: "Social Welfare", status: "ACTIVE", sourcePacks: [{ packId: "state-2", departmentCode: "DEPT-1", sourceCode: "ST2", sourceIds: ["pension"], status: "ACTIVE", version: "1.0.0" }] }];
const version: SchemePackVersion = {
  schemeId: "S1", id: "v1", version: 1, status: "DRAFT", name: "Scheme one", owner: "DEPT-1", beneficiaryLevel: "INDIVIDUAL",
  effectiveFrom: "2026-01-01", effectiveTo: null, eligibilityRuleSetId: "r1", implementingDepartments: ["DEPT-1"],
  sourcePacks: [], exclusivityGroups: [], configuration: "{}",
  details: { classification: "STATE", goReference: "GO 1", benefitType: "ENTITLEMENT", benefitForm: "CASH", unit: "INR", frequency: "MONTHLY", amount: 1000, quantity: null, codeCrosswalks: [] },
};
const wrap = (node: ReactNode) => render(<QueryClientProvider client={new QueryClient()}>{node}</QueryClientProvider>);
const bodyOf = (call: unknown[] | undefined) => JSON.parse(String((call?.[1] as RequestInit).body)) as Record<string, unknown>;

beforeEach(() => { document.cookie = "XSRF-TOKEN=t"; });
afterEach(() => vi.unstubAllGlobals());

describe("1 · ProfileStep", () => {
  it("creates a draft with classification, GO reference and amount", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn((url: string) => Promise.resolve(url === "/api/v1/departments" ? json(departments) : json({ ...version, id: "new" })));
    vi.stubGlobal("fetch", fetchMock);
    const onSaved = vi.fn();
    render(<ProfileStep onSaved={onSaved} />);
    await user.type(screen.getByLabelText("Scheme key"), "S1"); await user.type(screen.getByLabelText("Scheme name"), "Scheme one");
    await user.selectOptions(await screen.findByLabelText("Owning Department"), "DEPT-1");
    await user.selectOptions(screen.getByLabelText("Classification"), "CENTRALLY_SPONSORED");
    await user.type(screen.getByLabelText("GO reference"), "GO Ms 5"); await user.type(screen.getByLabelText("Amount"), "1500");
    await user.type(screen.getByLabelText("Effective from"), "2026-04-01");
    await user.click(screen.getByText("Save profile"));
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalled());
    const post = fetchMock.mock.calls.find((c) => c[0] === "/api/scheme-packs");
    const details = bodyOf(post)["details"] as Record<string, unknown>;
    expect(details).toMatchObject({ classification: "CENTRALLY_SPONSORED", goReference: "GO Ms 5", amount: 1500 });
    expect(bodyOf(post)["implementingDepartments"]).toEqual(["DEPT-1"]);
  });
});

describe("2 · SourcesStep", () => {
  it("links a source pack and a code crosswalk", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn((url: string) => Promise.resolve(url === "/api/v1/departments" ? json(departments) : json(version)));
    vi.stubGlobal("fetch", fetchMock);
    render(<SourcesStep version={version} onSaved={vi.fn()} />);
    await user.click(await screen.findByLabelText(/state-2/));
    await user.click(screen.getByText("Add code"));
    await user.selectOptions(screen.getByLabelText("Crosswalk pack 1"), "state-2"); await user.type(screen.getByLabelText("Source code 1"), "VRIDHA");
    await user.click(screen.getByText("Save sources"));
    await vi.waitFor(() => expect(fetchMock.mock.calls.some((c) => c[0] === "/api/scheme-packs/S1/versions/v1")).toBe(true));
    const put = bodyOf(fetchMock.mock.calls.find((c) => c[0] === "/api/scheme-packs/S1/versions/v1"));
    expect(put["sourcePacks"]).toEqual(["state-2"]);
    expect((put["details"] as { codeCrosswalks: unknown[] }).codeCrosswalks).toEqual([{ packId: "state-2", sourceCode: "VRIDHA" }]);
  });
});

describe("3 · EligibilityStep", () => {
  it("shows the linked rule set and hides Draft from GO when AI is off", async () => {
    const fetchMock = vi.fn((url: string) => Promise.resolve(url.startsWith("/api/rule-sets") ? json([{ id: "r1", ownerId: "S1", version: 2, status: "APPROVED", type: "ELIGIBILITY", ownerType: "SCHEME", effectiveFrom: "2026-01-01", effectiveTo: null, requiredAttributes: ["age"] }]) : json({ enabled: false })));
    vi.stubGlobal("fetch", fetchMock);
    wrap(<EligibilityStep version={version} onSaved={vi.fn()} />);
    expect(await screen.findByText("Linked rule set: v2 (APPROVED)")).toBeTruthy();
    expect(screen.getByText("Reads: age")).toBeTruthy();
    expect(screen.getByText("Save draft")).toBeTruthy();
    expect(screen.queryByText("Draft from GO")).toBeNull();
  });
});

describe("4 · AvailabilityStep", () => {
  it("lists each attribute with its sources and coverage", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ schemeId: "S1", versionId: "v1", level: "INDIVIDUAL", population: 5, attributes: [{ attribute: "gender", suppliedBy: ["state-1/pds"], withValue: 4, population: 5, percent: 80 }] })));
    render(<AvailabilityStep version={version} />);
    expect(await screen.findByText("4 of 5 (80%)")).toBeTruthy();
    expect(screen.getByText("state-1/pds")).toBeTruthy();
  });
});

describe("5 · TestStep", () => {
  it("asks for a rule set when none is linked", () => {
    render(<TestStep version={{ ...version, eligibilityRuleSetId: null }} />);
    expect(screen.getByText("Link an eligibility rule set first.")).toBeTruthy();
  });
});

describe("6 · ImpactStep", () => {
  it("shows totals and the district breakdown", async () => {
    const user = userEvent.setup();
    const counts = { eligible: 2, enrolled: 1, eligibleNotEnrolled: 1, enrolledNotEligible: 0, indeterminate: 0 };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ schemeId: "S1", versionId: "v1", evaluated: 5, totals: counts, exclusivityOverlaps: 1, byDistrict: { "100001": counts } })));
    render(<ImpactStep version={version} />);
    await user.click(screen.getByText("Run impact preview"));
    expect(await screen.findByText(/eligible not enrolled 1, enrolled not eligible 0/)).toBeTruthy();
    expect(screen.getByText("100001")).toBeTruthy();
  });
});

describe("7 · SubmitStep", () => {
  it("submits a draft and lets an approver decide a pending version", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn((url: string) => Promise.resolve(url.startsWith("/api/rule-sets") ? json([{ id: "r1", version: 1, status: "PENDING_APPROVAL", requiredAttributes: [] }]) : json({ ...version, status: "PENDING_APPROVAL" })));
    vi.stubGlobal("fetch", fetchMock);
    const onSaved = vi.fn();
    const { rerender } = render(<SubmitStep version={version} canApprove onSaved={onSaved} />);
    expect(await screen.findByText(/must be approved in the Rules Studio first/)).toBeTruthy();
    await user.click(screen.getByText("Submit for approval"));
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalled());
    rerender(<SubmitStep version={{ ...version, status: "PENDING_APPROVAL" }} canApprove onSaved={onSaved} />);
    expect(screen.getByText("Approve")).toBeTruthy();
    rerender(<SubmitStep version={{ ...version, status: "PENDING_APPROVAL" }} canApprove={false} onSaved={onSaved} />);
    expect(screen.queryByText("Approve")).toBeNull();
  });
});

describe("8 · VersionsStep", () => {
  it("lists versions and starts a new version from one", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockResolvedValue(json({ ...version, id: "v2", version: 2 }));
    vi.stubGlobal("fetch", fetchMock);
    const onCopied = vi.fn();
    render(<VersionsStep versions={[{ ...version, status: "APPROVED" }]} onCopied={onCopied} />);
    expect(screen.getByText("GO 1")).toBeTruthy();
    await user.click(screen.getByText("New version from v1"));
    await vi.waitFor(() => expect(onCopied).toHaveBeenCalled());
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe("/api/scheme-packs/S1/versions/v1/copy");
  });
});
