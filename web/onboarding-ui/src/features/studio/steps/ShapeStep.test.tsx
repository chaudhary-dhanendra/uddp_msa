import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { StudioDraft } from "../../../api/types";
import { I18nProvider } from "../../../i18n/i18n";
import type { ShapePreview, ShapeProposal, ShapeState } from "../../shaping/api";
import { compose, hydrate, outputName } from "../../shaping/shape";
import { ShapeStep } from "./ShapeStep";

const draft = { draftId: "d1", source: { sourceType: "file" } } as unknown as StudioDraft;

const headerStep = { op: "header-detect@1.0", maxScanRows: 30 };

const state: ShapeState = {
  proposals: [
    {
      section: "layout",
      kind: "header-detect",
      sheet: "Farmers",
      description: "Title rows above the header: the header is row 3",
      evidenceCount: 2,
      evidenceTotal: 3,
      step: headerStep,
      yaml: "{ op: 'header-detect@1.0', maxScanRows: 30 }",
      enabled: false,
    },
    {
      section: "parse",
      kind: "name-relation",
      sheet: "Farmers",
      description: "Name and relation in one cell in \"NAME\"",
      evidenceCount: 12,
      evidenceTotal: 40,
      step: { sourceField: "NAME", parser: "name-relation@1.0" },
      yaml: "{ sourceField: NAME, parser: 'name-relation@1.0' }",
      enabled: false,
    },
  ],
  layout: [],
  parse: [],
  sheets: ["Farmers"],
};

const preview: ShapePreview = {
  yaml: "layout:\n  - { op: 'header-detect@1.0' }",
  beforeColumns: ["col1", "col2"],
  beforeRows: [["Register of farmers", ""], ["ID", "NAME"]],
  afterColumns: ["ID", "NAME"],
  afterRows: [["1", "Ram"]],
  warnings: [],
};

type Call = { method: string; path: string; body: unknown };

function stub(shape: ShapeState = state): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      const path = String(input);
      const body = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
      calls.push({ method, path, body });
      const reply = (value: unknown) =>
        new Response(JSON.stringify(value), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/shaping") && method === "GET") return reply(shape);
      if (path.endsWith("/shaping/preview")) return reply(preview);
      if (path.endsWith("/shaping") && method === "PUT") return reply(draft);
      if (path === "/api/shaping/operations") {
        return reply({ builtIn: [{ kind: "parser", name: "mobile-number", version: "1.0", key: "mobile-number@1.0" }] });
      }
      if (path === "/api/shaping/plugins") {
        return reply([
          {
            fileName: "x.jar", sha256: "a".repeat(64), name: "x", version: "1.0", apiVersion: "1.0", status: "LOADED",
            reason: null, approved: true, provides: ["card-id@1.0"], packs: [], pendingChangeId: null, pendingAction: null,
          },
        ]);
      }
      if (path === "/api/shaping/word-lists/test") {
        return reply({
          parser: "card-id@1.0", value: "Ram", values: { district: "North", districtLgd: "9" }, confidence: 1, warnings: [],
        });
      }
      return reply({});
    }),
  );
  return calls;
}

function renderStep(onDone = vi.fn()) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <I18nProvider>
        <ShapeStep draft={draft} onBack={vi.fn()} onDone={onDone} />
      </I18nProvider>
    </QueryClientProvider>,
  );
  return onDone;
}

const previews = (calls: Call[]) => calls.filter((c) => c.path.endsWith("/shaping/preview"));

describe("ShapeStep", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("lists the proposals with their evidence, the generated YAML and the before/after rows", async () => {
    document.cookie = "XSRF-TOKEN=t";
    stub();
    renderStep();

    expect(await screen.findByText(/Title rows above the header/)).toBeTruthy();
    expect(screen.getByText(/12 of 40/)).toBeTruthy();
    expect(screen.getByText("{ sourceField: NAME, parser: 'name-relation@1.0' }")).toBeTruthy();
    const yaml = await screen.findByTestId("shape-yaml");
    expect(yaml.textContent).toContain("header-detect@1.0");
    expect(yaml.tagName).toBe("PRE");
    const before = await screen.findByTestId("shape-before");
    expect(within(before).getByText("Register of farmers")).toBeTruthy();
    const after = screen.getByTestId("shape-after");
    expect(within(after).getByText("Ram")).toBeTruthy();
  });

  it("previews with a proposal switched on and saves exactly the chosen steps into the source", async () => {
    const user = userEvent.setup();
    document.cookie = "XSRF-TOKEN=t";
    const calls = stub();
    const onDone = renderStep();

    await user.click(await screen.findByRole("checkbox", { name: /Title rows above the header/ }));
    await vi.waitFor(() =>
      expect(previews(calls).at(-1)?.body).toEqual({
        layout: [{ op: "header-detect@1.0", maxScanRows: 30 }],
        parse: [],
        sheets: ["Farmers"],
      }),
    );

    await user.click(screen.getByTestId("continue"));
    await vi.waitFor(() => expect(onDone).toHaveBeenCalled());
    const put = calls.find((c) => c.method === "PUT");
    expect(put?.path).toBe("/api/studio/drafts/d1/shaping");
    expect(put?.body).toEqual({
      layout: [{ op: "header-detect@1.0", maxScanRows: 30 }],
      parse: [],
      sheets: ["Farmers"],
    });
  });

  it("adds a column alias by hand and does not render manual ditto controls", async () => {
    const user = userEvent.setup();
    document.cookie = "XSRF-TOKEN=t";
    const calls = stub();
    renderStep();

    await user.type(await screen.findByLabelText("Column name in the file"), "Farmer name");
    await user.type(screen.getByLabelText("Name to use"), "FARMER_NAME");
    await user.click(screen.getByRole("button", { name: "Add column alias" }));
    expect(within(screen.getByTestId("shape-aliases")).getByText(/Farmer name → FARMER_NAME/)).toBeTruthy();

    expect(screen.queryByLabelText("Ditto columns")).toBeNull();
    expect(screen.queryByRole("button", { name: "Add ditto column" })).toBeNull();

    await vi.waitFor(() =>
      expect(previews(calls).at(-1)?.body).toEqual({
        layout: [
          { op: "column-aliases@1.0", aliases: { FARMER_NAME: ["Farmer name"] } },
        ],
        parse: [],
        sheets: ["Farmers"],
      }),
    );
  });

  it("adds a field parser by hand: learns its outputs from a sample value and names the new columns", async () => {
    const user = userEvent.setup();
    document.cookie = "XSRF-TOKEN=t";
    const calls = stub();
    renderStep();

    const column = (await screen.findByLabelText("Column")) as HTMLSelectElement;
    await vi.waitFor(() => expect(column.value).toBe("ID"));
    await user.selectOptions(column, "NAME");
    const parser = screen.getByLabelText("Parser") as HTMLSelectElement;
    await vi.waitFor(() => expect(parser.querySelectorAll("option")).toHaveLength(2));
    await user.selectOptions(parser, "card-id@1.0");
    await user.type(screen.getByLabelText("Name the new columns"), "Card");
    await user.click(screen.getByRole("button", { name: "Add parser" }));

    await vi.waitFor(() =>
      expect(calls.find((c) => c.path === "/api/shaping/word-lists/test")?.body).toEqual({
        parser: "card-id@1.0",
        value: "Ram",
      }),
    );
    const step = {
      sourceField: "NAME",
      parser: "card-id@1.0",
      outputs: { district: "card_district", districtLgd: "card_district_lgd" },
    };
    await vi.waitFor(() =>
      expect(previews(calls).at(-1)?.body).toEqual({ layout: [], parse: [step], sheets: ["Farmers"] }),
    );
    expect(within(screen.getByTestId("shape-parsers")).getByText("NAME → card-id@1.0")).toBeTruthy();

    await user.click(screen.getByRole("button", { name: "Remove NAME → card-id@1.0" }));
    await vi.waitFor(() =>
      expect(previews(calls).at(-1)?.body).toEqual({ layout: [], parse: [], sheets: ["Farmers"] }),
    );
  });

  it("keeps saved ditto steps that are not proposals and says so when nothing is proposed", async () => {
    stub({
      proposals: [],
      layout: [
        { op: "column-aliases@1.0", aliases: { ID: ["Sl No"] } },
        { op: "ditto@1.0", columns: ["VILLAGE"] },
      ],
      parse: [],
      sheets: [],
    });
    renderStep();
    expect(await screen.findByTestId("shape-none")).toBeTruthy();
    expect(within(await screen.findByTestId("shape-aliases")).getByText(/Sl No → ID/)).toBeTruthy();
    expect(screen.queryByTestId("shape-ditto")).toBeNull();
  });
});

describe("hydrate a saved proposal", () => {
  it("recognises a saved parse step whose keys came back in another order, even saved twice", () => {
    const [header, proposal] = state.proposals as [ShapeProposal, ShapeProposal];
    const step = { sourceField: "NAME", parser: "name-relation@1.0", outputs: { name: "n", "relation.type": "t" } };
    const reordered = { outputs: { "relation.type": "t", name: "n" }, parser: "name-relation@1.0", sourceField: "NAME" };
    const saved: ShapeState = {
      ...state,
      proposals: [header, { ...proposal, step, enabled: true }],
      parse: [reordered, reordered],
    };
    const choices = hydrate(saved);
    expect(choices.otherParse).toEqual([]);
    expect(compose(saved.proposals, choices, ["Farmers"]).parse).toEqual([step]);
  });
});

describe("outputName", () => {
  it("names a parser output <prefix>_<key> in snake case", () => {
    expect(outputName("MOBILE", "mobile")).toBe("mobile_mobile");
    expect(outputName("MOBILE", "otherMobiles")).toBe("mobile_other_mobiles");
    expect(outputName("NAME_BHOTI", "latinGuess")).toBe("name_bhoti_latin_guess");
    expect(outputName("ADDR", "geography.village.name")).toBe("addr_geography_village_name");
  });
});

describe("compose and hydrate", () => {
  it("round-trips proposals and aliases while keeping unsupported saved steps", () => {
    const kept = { op: "skip-rows@1.0", rules: [{ anyColumn: "Total" }] };
    const saved: ShapeState = {
      ...state,
      proposals: state.proposals.map((p, i) => ({ ...p, enabled: i === 0 })),
      layout: [
        headerStep,
        kept,
        { op: "column-aliases@1.0", aliases: { ID: ["Sl No", "S.No"] } },
        { op: "ditto@1.0", columns: ["VILLAGE"] },
      ],
    };
    const choices = hydrate(saved);
    expect(choices.on).toEqual([0]);
    expect(choices.aliases).toEqual([
      { logical: "ID", variant: "Sl No" },
      { logical: "ID", variant: "S.No" },
    ]);
    expect(choices.otherLayout).toEqual([kept]);
    expect(choices.legacyDittoLayout).toEqual([{ op: "ditto@1.0", columns: ["VILLAGE"] }]);
    expect(compose(saved.proposals, choices, ["Farmers"]).layout).toEqual([
      headerStep,
      kept,
      { op: "ditto@1.0", columns: ["VILLAGE"] },
      { op: "column-aliases@1.0", aliases: { ID: ["Sl No", "S.No"] } },
    ]);
  });

  it("keeps independent automatic ditto and alias proposals available and enables both ditto rules", async () => {
    const dittoState: ShapeState = {
      ...state,
      proposals: [
        { section: "layout", kind: "ditto", sheet: "Leh", description: 'Ditto marks (") repeat VILLAGE', evidenceCount: 1, evidenceTotal: 2, step: { op: "ditto@1.0", columns: ["VILLAGE"], tokens: ['"'] }, yaml: "leh", enabled: false },
        { section: "layout", kind: "ditto", sheet: "Kargil", description: "Ditto marks (-do-) repeat Village", evidenceCount: 1, evidenceTotal: 2, step: { op: "ditto@1.0", columns: ["Village"], tokens: ["-do-"] }, yaml: "kargil", enabled: false },
        { section: "layout", kind: "column-aliases", sheet: "Kargil", description: "Village maps to VILLAGE", evidenceCount: 2, evidenceTotal: 2, step: { op: "column-aliases@1.0", aliases: { VILLAGE: ["Village"] } }, yaml: "alias", enabled: false },
      ],
      layout: [{ op: "header-detect@1.0" }],
    };
    const user = userEvent.setup();
    const calls = stub(dittoState);
    renderStep();

    await user.click(await screen.findByRole("checkbox", { name: 'Ditto marks (") repeat VILLAGE' }));
    await user.click(screen.getByRole("checkbox", { name: "Ditto marks (-do-) repeat Village" }));
    await user.click(screen.getByRole("checkbox", { name: "Village maps to VILLAGE" }));
    expect(screen.getByRole("checkbox", { name: "Village maps to VILLAGE" })).toBeTruthy();
    await vi.waitFor(() =>
      expect(previews(calls).at(-1)?.body).toEqual({
        layout: [
          { op: "header-detect@1.0" },
          dittoState.proposals[0]?.step,
          dittoState.proposals[1]?.step,
          dittoState.proposals[2]?.step,
        ],
        parse: [],
        sheets: ["Farmers"],
      }),
    );
  });
});
