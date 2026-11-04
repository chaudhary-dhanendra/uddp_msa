import { render, screen, within } from "@testing-library/react";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { RuleField } from "../../api/types";
import { DecisionTableEditor, type DecisionTable, RuleSetDraftForm, RuleTestPanel, VersionDiff } from "./index";

describe("DecisionTableEditor", () => {
  it("adds, edits and removes rows in the rule representation", async () => {
    const user = userEvent.setup(); const values: { rows: { condition: string | null; output: string | null; clauseReference: string | null }[] }[] = [];
    function Harness() { const [value, setValue] = useState({ rows: [] as { condition: string | null; output: string | null; clauseReference: string | null }[] }); return <DecisionTableEditor value={value} onChange={(next) => { values.push(next); setValue(next); }} />; }
    render(<Harness />); await user.click(screen.getByText("Add row")); await user.type(screen.getByLabelText("Condition 1"), "age >= 18"); await user.type(screen.getByLabelText("Output 1"), "true"); await user.type(screen.getByLabelText("Clause reference 1"), "GO-1");
    expect(values.at(-1)?.rows[0]).toEqual({ condition: "age >= 18", output: "true", clauseReference: "GO-1" }); await user.click(screen.getByText("Remove row")); expect(values.at(-1)?.rows).toHaveLength(0);
  });
});
describe("RuleTestPanel", () => {
  it("submits a sample and renders fired clause explanations", async () => {
    const user = userEvent.setup(); vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ firedRules: [{ clauseReference: "GO-1", outputs: { message: "eligible" } }] }), { status: 200 })));
    render(<RuleTestPanel type="ELIGIBILITY" ownerType="SCHEME" ownerId="pension" />); await user.click(screen.getByText("Evaluate"));
    expect(await screen.findByText("Clause: GO-1")).toBeTruthy(); expect(fetch).toHaveBeenCalledWith("/api/rule-sets/evaluate", expect.any(Object));
  });
});
describe("VersionDiff", () => {
  it("shows readable decision-table changes between selected versions", async () => {
    const user = userEvent.setup(); render(<VersionDiff versions={[{ id: "one", type: "DATA_QUALITY", ownerId: "pack", ownerType: "SOURCE_PACK", version: 1, status: "DRAFT", effectiveFrom: "2026-01-01", dmnXml: "<rule>old</rule>" }, { id: "two", type: "DATA_QUALITY", ownerId: "pack", ownerType: "SOURCE_PACK", version: 2, status: "DRAFT", effectiveFrom: "2026-02-01", dmnXml: "<rule>new</rule>" }]} />); await user.selectOptions(screen.getByLabelText("Earlier version"), "one"); await user.selectOptions(screen.getByLabelText("Later version"), "two"); expect(screen.getByText("<rule>new</rule>")).toBeTruthy();
  });
});
describe("Rules Studio approvals", () => {
  const rows = [
    { id: "d1", type: "ELIGIBILITY", ownerType: "SCHEME", ownerId: "SCHEME-A", version: 1, status: "DRAFT", effectiveFrom: "2026-01-01" },
    { id: "p1", type: "ELIGIBILITY", ownerType: "SCHEME", ownerId: "SCHEME-A", version: 2, status: "PENDING_APPROVAL", effectiveFrom: "2026-02-01" },
  ];
  afterEach(() => vi.unstubAllGlobals());

  it("lets an author submit a draft and an approver decide a submitted version, sending the CSRF token", async () => {
    const user = userEvent.setup();
    mockBackend(testUser(["PLATFORM_SETTINGS", "RULES_APPROVE"]), { "/api/rule-sets": () => rows });
    document.cookie = "XSRF-TOKEN=token-1";
    renderAt("/rules");
    expect(await screen.findByRole("button", { name: "Submit for approval" })).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Approve" }));
    await vi.waitFor(() => {
      const call = vi.mocked(fetch).mock.calls.find(([url]) => String(url) === "/api/rule-sets/p1/approve");
      expect(call?.[1]).toMatchObject({ method: "POST", headers: { "X-XSRF-TOKEN": "token-1" } });
    });
  });

  it("offers no decision to a user without approval rights", async () => {
    mockBackend(testUser(["PLATFORM_SETTINGS"]), { "/api/rule-sets": () => rows });
    renderAt("/rules");
    expect(await screen.findByRole("button", { name: "Submit for approval" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
  });
});

describe("Rules Studio outcome (H1)", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("offers Send to steward next to Error and Warning for a data-quality rule, with help text", async () => {
    const user = userEvent.setup(); const values: { rows: { condition: string | null; output: string | null; clauseReference: string | null }[] }[] = [];
    function Harness() { const [value, setValue] = useState<DecisionTable>({ rows: [{ condition: "", output: "", clauseReference: "" }] }); return <DecisionTableEditor outcomes value={value} onChange={(next) => { values.push(next); setValue(next); }} />; }
    render(<Harness />);
    const select = screen.getByLabelText("Outcome 1");
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toEqual(["Outcome", "Error", "Warning", "Send to steward"]);
    expect(screen.getByText(/Send to steward: the row is held until a steward/)).toBeTruthy();
    await user.selectOptions(select, "SEND_TO_STEWARD");
    expect(values.at(-1)?.rows[0]?.output).toBe("SEND_TO_STEWARD");
  });

  it("shows the outcome of a fired rule in the test panel", async () => {
    const user = userEvent.setup(); vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ firedRules: [{ clauseReference: "DQ-1", outputs: { outcome: "SEND_TO_STEWARD" } }] }), { status: 200 })));
    render(<RuleTestPanel type="DATA_QUALITY" ownerType="SOURCE_PACK" ownerId="pack" />); await user.click(screen.getByText("Evaluate"));
    expect(await screen.findByText("Outcome: Send to steward")).toBeTruthy();
  });
});

describe("Rules Studio authoring (A3)", () => {
  afterEach(() => vi.unstubAllGlobals());
  const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

  it("builds a condition from a metadata field, operator and value", async () => {
    const user = userEvent.setup(); const values: { rows: { condition: string | null; output: string | null; clauseReference: string | null }[] }[] = [];
    function Harness() { const [value, setValue] = useState({ rows: [{ condition: "", output: "", clauseReference: "" }] as { condition: string | null; output: string | null; clauseReference: string | null }[] }); return <DecisionTableEditor value={value} fields={[{ name: "age", label: "Resident · Age", type: "number" }, { name: "gender", label: "Resident · Gender", type: "code" }] as unknown as RuleField[]} onChange={(next) => { values.push(next); setValue(next); }} />; }
    render(<Harness />);
    await user.selectOptions(screen.getByLabelText("Field 1"), "age"); await user.type(screen.getByLabelText("Value 1"), "60"); await user.click(screen.getByText("Add to condition"));
    await user.selectOptions(screen.getByLabelText("Field 1"), "gender"); await user.selectOptions(screen.getByLabelText("Operator 1"), "="); await user.clear(screen.getByLabelText("Value 1")); await user.type(screen.getByLabelText("Value 1"), "FEMALE"); await user.click(screen.getByText("Add to condition"));
    expect(values.at(-1)?.rows[0]?.condition).toBe('age >= 60 and gender = "FEMALE"');
  });

  it("tests a draft version with a picked generated case", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockResolvedValueOnce(json([{ name: "age = 59 (boundary of age >= 60)", record: { age: 59 }, firedClauses: [], outcomes: [], error: null }]))
      .mockResolvedValueOnce(json({ ruleSet: null, firedRules: [] }));
    vi.stubGlobal("fetch", fetchMock); document.cookie = "XSRF-TOKEN=t";
    render(<RuleTestPanel type="ELIGIBILITY" ownerType="SCHEME" ownerId="S" ruleSetId="r1" />);
    await user.selectOptions(await screen.findByLabelText("Sample record"), "0");
    expect((screen.getByLabelText("Sample record JSON") as HTMLTextAreaElement).value).toContain('"age": 59');
    await user.click(screen.getByText("Evaluate"));
    expect(await screen.findByText("No rule fired.")).toBeTruthy();
    expect(String(fetchMock.mock.calls[1]?.[0])).toBe("/api/rule-sets/r1/evaluate");
  });

  it("saves a typed FEEL condition as a draft when no fields are available", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(String(url) === "/api/scheme-packs" ? json([]) : json({ id: "new", version: 1, status: "DRAFT" })));
    vi.stubGlobal("fetch", fetchMock); document.cookie = "XSRF-TOKEN=t";
    const onSaved = vi.fn();
    render(<QueryClientProvider client={new QueryClient()}><RuleSetDraftForm fields={[]} onSaved={onSaved} /></QueryClientProvider>);
    await user.type(screen.getByLabelText("Owner"), "SCHEME-A"); await user.type(screen.getByLabelText("Draft effective from"), "2026-01-01");
    await user.type(screen.getByLabelText("Condition"), "age >= 60"); await user.click(screen.getByLabelText(/Eligible/, { selector: "input[value=ELIGIBLE]" })); await user.type(screen.getByLabelText("Clause reference"), "GO 2");
    await user.click(screen.getByText("Save draft"));
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalled());
    const call = fetchMock.mock.calls.find(([url]) => String(url) === "/api/rule-sets/table");
    const body = JSON.parse(String((call?.[1] as RequestInit).body)) as { rows: unknown[]; ownerId: string };
    expect(body.ownerId).toBe("SCHEME-A"); expect(body.rows).toEqual([{ condition: "age >= 60", conditions: [], output: "ELIGIBLE", clauseReference: "GO 2" }]);
  });
});
