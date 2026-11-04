import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { RuleField } from "../../api/types";
import { RuleSetDraftForm, RuleTestPanel, VersionDiff } from "./index";
import {
  describe as describeCondition,
  newCondition,
  operatorsOf,
  problemOf,
  toPayload,
} from "./conditions";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

/** As GET /api/rule-sets/fields serves them; age comes without operators to prove the type-based fallback. */
const FIELDS = [
  {
    name: "gender",
    label: "Resident · Gender",
    type: "code",
    codeSet: "gender",
    valueKind: null,
    dataType: "CODE",
    operators: [
      { code: "eq", label: "is", arity: "ONE" },
      { code: "neq", label: "is not", arity: "ONE" },
      { code: "in", label: "is one of", arity: "MANY" },
      { code: "not_in", label: "is not one of", arity: "MANY" },
      { code: "present", label: "is provided", arity: "NONE" },
      { code: "missing", label: "is missing", arity: "NONE" },
    ],
    values: [
      { code: "MALE", label: "Male" },
      { code: "FEMALE", label: "Female" },
      { code: "OTHER", label: "Other" },
    ],
  },
  {
    name: "age.years",
    label: "Resident · Age in years (dated by the source's asOfDate)",
    type: "text",
    valueKind: "number",
    codeSet: null,
  },
  {
    name: "dateOfBirth",
    label: "Resident · Date of birth",
    type: "date",
    valueKind: "date",
    codeSet: null,
  },
  {
    name: "name",
    label: "Resident · Full name",
    type: "text",
    valueKind: null,
    codeSet: null,
  },
] as unknown as RuleField[];

const SCHEMES = [{ schemeId: "WIDOW-PENSION", name: "Widow pension" }];

function setup(
  response: (url: string, init?: RequestInit) => Response = () =>
    json({ id: "r1", version: 1, status: "DRAFT" }),
) {
  const fetchMock = vi
    .fn()
    .mockImplementation((url: string, init?: RequestInit) =>
      Promise.resolve(
        String(url) === "/api/scheme-packs"
          ? json(SCHEMES)
          : response(String(url), init),
      ),
    );
  vi.stubGlobal("fetch", fetchMock);
  document.cookie = "XSRF-TOKEN=t";
  const onSaved = vi.fn();
  render(
    <QueryClientProvider client={new QueryClient()}>
      <RuleSetDraftForm fields={FIELDS} onSaved={onSaved} />
    </QueryClientProvider>,
  );
  return { user: userEvent.setup(), fetchMock, onSaved };
}
const optionsOf = (label: string) =>
  within(screen.getByLabelText(label))
    .getAllByRole("option")
    .map((o) => o.textContent);

describe("Rules Studio condition builder", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("offers numeric operators and a number input for age", async () => {
    const { user } = setup();
    await user.selectOptions(screen.getByLabelText("Field 1"), "age.years");
    expect(optionsOf("Operator 1")).toEqual([
      "equals",
      "does not equal",
      "is greater than",
      "is at least",
      "is less than",
      "is at most",
      "is between",
      "is provided",
      "is missing",
    ]);
    expect(screen.getByLabelText("Value 1")).toHaveProperty("type", "number");
    await user.selectOptions(screen.getByLabelText("Operator 1"), "between");
    expect(screen.getByLabelText("Value 1 from")).toBeTruthy();
    expect(screen.getByLabelText("Value 1 to")).toBeTruthy();
  });

  it("offers a value dropdown for gender and date operators for date of birth", async () => {
    const { user } = setup();
    await user.selectOptions(screen.getByLabelText("Field 1"), "gender");
    expect(optionsOf("Operator 1")).toEqual([
      "is",
      "is not",
      "is one of",
      "is not one of",
      "is provided",
      "is missing",
    ]);
    expect(optionsOf("Value 1")).toEqual([
      "Choose a value",
      "Male",
      "Female",
      "Other",
    ]);
    await user.selectOptions(screen.getByLabelText("Operator 1"), "in");
    expect(
      within(screen.getByRole("group", { name: "Values 1" })).getAllByRole(
        "checkbox",
      ),
    ).toHaveLength(3);
    await user.selectOptions(screen.getByLabelText("Field 1"), "dateOfBirth");
    expect(optionsOf("Operator 1")).toContain("is before");
    expect(screen.getByLabelText("Value 1")).toHaveProperty("type", "date");
  });

  it("previews the rule in plain language and saves the built conditions", async () => {
    const { user, fetchMock, onSaved } = setup();
    await user.selectOptions(
      await screen.findByRole("combobox", { name: "Owner" }),
      "WIDOW-PENSION",
    );
    await user.type(
      screen.getByLabelText("Draft effective from"),
      "2026-04-01",
    );
    await user.selectOptions(screen.getByLabelText("Field 1"), "gender");
    await user.selectOptions(screen.getByLabelText("Value 1"), "FEMALE");
    await user.click(screen.getByText("+ Add condition"));
    await user.selectOptions(screen.getByLabelText("Field 2"), "age.years");
    await user.selectOptions(screen.getByLabelText("Operator 2"), "gte");
    await user.type(screen.getByLabelText("Value 2"), "18");
    await user.click(
      screen.getByLabelText(/Eligible/, { selector: "input[value=ELIGIBLE]" }),
    );
    await user.type(screen.getByLabelText("Clause reference"), "GO 12 para 3");

    const preview = screen.getByRole("heading", { name: "Rule preview" })
      .parentElement as HTMLElement;
    expect(preview.textContent).toContain("If Gender is Female");
    expect(preview.textContent).toContain(
      "and Age in years (dated by the source's asOfDate) is at least 18",
    );
    expect(preview.textContent).toContain("then the resident is Eligible");

    await user.click(screen.getByText("Save draft"));
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalled());
    const call = fetchMock.mock.calls.find(
      ([url]) => String(url) === "/api/rule-sets/table",
    );
    const body = JSON.parse(String((call?.[1] as RequestInit).body)) as Record<
      string,
      unknown
    >;
    expect(body).toMatchObject({
      type: "ELIGIBILITY",
      ownerType: "SCHEME",
      ownerId: "WIDOW-PENSION",
      effectiveFrom: "2026-04-01",
      effectiveTo: null,
    });
    expect(body.rows).toEqual([
      {
        condition: null,
        output: "ELIGIBLE",
        clauseReference: "GO 12 para 3",
        conditions: [
          {
            field: "gender",
            operator: "eq",
            value: "FEMALE",
            valueTo: null,
            values: [],
          },
          {
            field: "age.years",
            operator: "gte",
            value: "18",
            valueTo: null,
            values: [],
          },
        ],
      },
    ]);
  });

  it("shows what is missing instead of saving an incomplete rule", async () => {
    const { user, fetchMock } = setup();
    await user.selectOptions(screen.getByLabelText("Field 1"), "age.years");
    await user.click(screen.getByText("Save draft"));
    const summary = screen.getByText("Fix the following before saving:")
      .parentElement as HTMLElement;
    expect(summary.textContent).toContain(
      "Choose the scheme this rule applies to.",
    );
    expect(summary.textContent).toContain("Condition 1: Enter a value.");
    expect(summary.textContent).toContain("Choose an outcome.");
    expect(screen.getByLabelText("Value 1").getAttribute("aria-invalid")).toBe(
      "true",
    );
    expect(
      fetchMock.mock.calls.some(
        ([url]) => String(url) === "/api/rule-sets/table",
      ),
    ).toBe(false);
  });

  it("adds and removes conditions and shows a rejection from the server", async () => {
    const { user } = setup(() =>
      json(
        {
          message:
            "Condition 1: 'gte' is not an operator for Resident · Gender (code)",
        },
        400,
      ),
    );
    expect(screen.getByLabelText("Remove condition 1")).toHaveProperty(
      "disabled",
      true,
    );
    await user.click(screen.getByText("+ Add condition"));
    await user.selectOptions(screen.getByLabelText("Field 2"), "name");
    await user.click(screen.getByLabelText("Remove condition 1"));
    expect(screen.queryByLabelText("Field 2")).toBeNull();
    expect((screen.getByLabelText("Field 1") as HTMLSelectElement).value).toBe(
      "name",
    );

    await user.selectOptions(screen.getByLabelText("Operator 1"), "contains");
    await user.type(screen.getByLabelText("Value 1"), "Singh");
    await user.selectOptions(
      await screen.findByRole("combobox", { name: "Owner" }),
      "WIDOW-PENSION",
    );
    await user.type(
      screen.getByLabelText("Draft effective from"),
      "2026-04-01",
    );
    await user.click(
      screen.getByLabelText(/Not eligible/, {
        selector: "input[value=NOT_ELIGIBLE]",
      }),
    );
    await user.type(screen.getByLabelText("Clause reference"), "GO 1");
    await user.click(screen.getByText("Save draft"));
    expect(
      await screen.findByText(/is not an operator for Resident · Gender/),
    ).toBeTruthy();
  });
});

describe("conditions", () => {
  const fields = FIELDS;
  it("derives operators from the data type when the server sends none", () => {
    expect(operatorsOf(fields[1]).map((o) => o.code)).toEqual([
      "eq",
      "neq",
      "gt",
      "gte",
      "lt",
      "lte",
      "between",
      "present",
      "missing",
    ]);
    expect(operatorsOf(fields[3]).map((o) => o.code)).toContain("starts_with");
  });

  it("validates values by type and describes conditions", () => {
    const age = {
      ...newCondition(1),
      field: "age.years",
      operator: "between",
      value: "60",
      valueTo: "18",
    };
    expect(problemOf(age, fields)).toBe(
      "The first value must not be after the second.",
    );
    expect(problemOf({ ...age, operator: "gte", value: "abc" }, fields)).toBe(
      "Enter a number.",
    );
    expect(
      problemOf({ ...age, value: "18", valueTo: "60" }, fields),
    ).toBeNull();
    expect(
      describeCondition({ ...age, value: "18", valueTo: "60" }, fields),
    ).toBe(
      "Age in years (dated by the source's asOfDate) is between 18 and 60",
    );
    const gender = {
      ...newCondition(2),
      field: "gender",
      operator: "in",
      values: ["FEMALE", "OTHER"],
    };
    expect(describeCondition(gender, fields)).toBe(
      "Gender is one of Female or Other",
    );
    expect(toPayload(gender, fields)).toEqual({
      field: "gender",
      operator: "in",
      value: null,
      valueTo: null,
      values: ["FEMALE", "OTHER"],
    });
    expect(
      describeCondition(
        {
          ...newCondition(3),
          field: "dateOfBirth",
          operator: "lt",
          value: "1966-01-01",
        },
        fields,
      ),
    ).toBe("Date of birth is before 1 Jan 1966");
    expect(describeCondition(newCondition(4), fields)).toBe("[choose a field]");
  });
});

describe("Rules Studio testing and history", () => {
  afterEach(() => vi.unstubAllGlobals());
  const DMN = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions" name="x" namespace="https://uddp.gov/rules">
  <decision id="decision" name="x"><decisionTable id="table" hitPolicy="COLLECT">
    <input id="in1"><inputExpression id="in1-expression" typeRef="boolean"><text>gender = "FEMALE" and age.years &gt;= 18</text></inputExpression></input>
    <output id="outcome" name="outcome" typeRef="string" /><output id="clause" name="clauseReference" typeRef="string" />
    <rule id="rule1"><inputEntry id="rule1-in1"><text>true</text></inputEntry><outputEntry id="rule1-outcome"><text>"ELIGIBLE"</text></outputEntry><outputEntry id="rule1-clause"><text>"GO 2"</text></outputEntry></rule>
  </decisionTable></decision>
</definitions>`;

  it("keeps the picked case selected and explains which condition failed", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(String(url).endsWith("/test-cases")
      ? json([{ name: "age.years = 17 (boundary of age.years >= 18)", record: { age: { years: 17 }, gender: "FEMALE" }, firedClauses: [], outcomes: [], error: null },
              { name: "age.years = 18 (boundary of age.years >= 18)", record: { age: { years: 18 }, gender: "FEMALE" }, firedClauses: ["GO 2"], outcomes: ["ELIGIBLE"], error: null }])
      : json({ ruleSet: null, firedRules: [], checks: [{ clauseReference: "GO 2", condition: 'gender = "FEMALE"', matched: true }, { clauseReference: "GO 2", condition: "age.years >= 18", matched: false }] })));
    vi.stubGlobal("fetch", fetchMock); document.cookie = "XSRF-TOKEN=t";
    render(<RuleTestPanel type="ELIGIBILITY" ownerType="SCHEME" ownerId="S" ruleSetId="r1" />);
    const select = await screen.findByLabelText("Sample record");
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toContain("age.years = 18 (boundary of age.years >= 18) → fires GO 2");
    await user.selectOptions(select, "0");
    expect((select as HTMLSelectElement).value).toBe("0");
    expect(JSON.parse((screen.getByLabelText("Sample record JSON") as HTMLTextAreaElement).value)).toEqual({ age: { years: 17 }, gender: "FEMALE" });
    await user.click(screen.getByText("Evaluate"));
    expect(await screen.findByText("No rule fired.")).toBeTruthy();
    expect(screen.getByText("age.years >= 18")).toBeTruthy();
    expect(screen.queryByText('gender = "FEMALE"')).toBeNull();
  });

  it("shows a single version with its readable rule and compares two versions by rule", async () => {
    const { unmount } = render(<VersionDiff versions={[{ id: "v1", version: 1, status: "DRAFT", effectiveFrom: "2026-11-01", dmnXml: DMN }]} />);
    expect(screen.getByText(/This is the first version/)).toBeTruthy();
    expect(screen.getByText('IF gender = "FEMALE" and age.years >= 18 THEN ELIGIBLE (GO 2)')).toBeTruthy();
    unmount();
    render(<VersionDiff versions={[{ id: "v1", version: 1, status: "APPROVED", dmnXml: DMN }, { id: "v2", version: 2, status: "DRAFT", dmnXml: DMN.replace("18", "21") }]} />);
    expect((screen.getByLabelText("Earlier version") as HTMLSelectElement).value).toBe("v1");
    expect((screen.getByLabelText("Later version") as HTMLSelectElement).value).toBe("v2");
    expect(screen.getByText('+ IF gender = "FEMALE" and age.years >= 21 THEN ELIGIBLE (GO 2)')).toBeTruthy();
    expect(screen.getByText('− IF gender = "FEMALE" and age.years >= 18 THEN ELIGIBLE (GO 2)')).toBeTruthy();
  });
});
