import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";
import type { BuiltIn, PluginInfo, ShapingChange, WordList } from "./api";

const honorifics: WordList = {
  id: "honorifics",
  description: "Stripped from the normalised name only.",
  pendingChangeId: null,
  groups: [{ section: "honorifics", key: "honorifics", attrs: {}, values: ["Haji", "Shri"] }],
};
const variants: WordList = {
  id: "name-variants",
  description: "",
  pendingChangeId: null,
  groups: [{ section: "variants", key: "MOHAMMAD", attrs: {}, values: ["Mohd", "Md"] }],
};
const markers: WordList = {
  id: "relation-markers",
  description: "",
  pendingChangeId: null,
  groups: [{ section: "markers", key: "1", attrs: { relation: "FATHER", gender: "MALE" }, values: ["S/O"] }],
};

const change = (over: Partial<ShapingChange>): ShapingChange => ({
  changeId: "c1",
  kind: "WORD_LIST",
  action: "UPDATE",
  target: "honorifics",
  payload: {},
  status: "PENDING_APPROVAL",
  createdBy: "bina",
  decidedBy: null,
  decisionReason: null,
  createdAt: "2026-10-29T10:00:00Z",
  ...over,
});

const builtIns: BuiltIn[] = [
  { kind: "operation", name: "header-detect", version: "1.0", key: "header-detect@1.0" },
  { kind: "parser", name: "name-normalise", version: "1.0", key: "name-normalise@1.0" },
];

const plugins: PluginInfo[] = [
  {
    fileName: "ok.jar", sha256: "a".repeat(64), name: "ok-plugin", version: "1.0", apiVersion: "1.0", status: "LOADED",
    reason: null, approved: true, provides: ["tidy-rows@1.0"], packs: ["reference-pack"], pendingChangeId: null, pendingAction: null,
  },
  {
    fileName: "new.jar", sha256: "b".repeat(64), name: "new-plugin", version: "2.0", apiVersion: "1.0", status: "NOT_APPROVED",
    reason: "SHA-256 is not listed in approved-plugins.yaml", approved: false, provides: [], packs: [], pendingChangeId: null,
    pendingAction: null,
  },
  {
    fileName: "bad.jar", sha256: "c".repeat(64), name: null, version: null, apiVersion: null, status: "REJECTED",
    reason: "unsupported apiVersion 2.0", approved: true, provides: [], packs: [], pendingChangeId: null, pendingAction: null,
  },
];

const writes = () =>
  vi.mocked(fetch).mock.calls
    .filter(([, init]) => init?.method === "POST")
    .map(([url, init]) => ({ url: String(url), body: JSON.parse(String(init?.body ?? "null")) as unknown }));

describe("Word lists page", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shows a list, proposes an edited list and lists changes waiting for approval", async () => {
    const user = userEvent.setup();
    document.cookie = "XSRF-TOKEN=t";
    mockBackend(testUser(["SHAPING_VIEW", "SHAPING_MANAGE"]), {
      "/api/shaping/word-lists": () => [honorifics, variants, markers],
      "/api/shaping/changes": () => [],
      "/api/shaping/operations": () => ({ builtIn: builtIns }),
      "/api/shaping/word-lists/honorifics/proposals": () => change({}),
    });
    renderAt("/reference-data/word-lists");

    const box = (await screen.findByLabelText("honorifics")) as HTMLTextAreaElement;
    expect(box.value).toBe("Haji\nShri");
    expect(screen.getByText("Stripped from the normalised name only.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Propose change" }).hasAttribute("disabled")).toBe(true);

    await user.type(box, "{Enter}Dr");
    await user.click(screen.getByRole("button", { name: "Propose change" }));
    await waitFor(() => expect(writes().at(-1)?.url).toBe("/api/shaping/word-lists/honorifics/proposals"));
    expect(writes().at(-1)?.body).toEqual({
      groups: [{ section: "honorifics", key: "honorifics", attrs: {}, values: ["Haji", "Shri", "Dr"] }],
    });
    expect(await screen.findByText("Proposed; it is waiting for approval.")).toBeTruthy();
  });

  it("edits a map list by group and a marker group's attributes", async () => {
    const user = userEvent.setup();
    document.cookie = "XSRF-TOKEN=t";
    mockBackend(testUser(["SHAPING_VIEW", "SHAPING_MANAGE"]), {
      "/api/shaping/word-lists": () => [variants, markers],
      "/api/shaping/changes": () => [],
      "/api/shaping/operations": () => ({ builtIn: builtIns }),
    });
    renderAt("/reference-data/word-lists");

    await screen.findByLabelText("MOHAMMAD");
    await user.type(screen.getByLabelText("Name of the new group"), "GHULAM");
    await user.click(screen.getByRole("button", { name: "Add group" }));
    const ghulam = (await screen.findByLabelText("GHULAM")) as HTMLTextAreaElement;
    await user.type(ghulam, "Gh");
    await user.click(screen.getByRole("button", { name: "Propose change" }));
    await waitFor(() => expect(writes().at(-1)?.url).toBe("/api/shaping/word-lists/name-variants/proposals"));
    expect(writes().at(-1)?.body).toEqual({
      groups: [
        { section: "variants", key: "MOHAMMAD", attrs: {}, values: ["Mohd", "Md"] },
        { section: "variants", key: "GHULAM", attrs: {}, values: ["Gh"] },
      ],
    });

    await user.selectOptions(screen.getByLabelText("Word list"), "relation-markers");
    expect(await screen.findByLabelText("FATHER (MALE)")).toBeTruthy();
    expect(screen.getByLabelText("Relation")).toBeTruthy();
  });

  it("lets another user approve a waiting change but not the proposer, and asks a reason to reject", async () => {
    const user = userEvent.setup();
    document.cookie = "XSRF-TOKEN=t";
    mockBackend(testUser(["SHAPING_VIEW", "SHAPING_APPROVE"]), {
      "/api/shaping/word-lists": () => [{ ...honorifics, pendingChangeId: "c1" }],
      "/api/shaping/changes": () => [change({ changeId: "c1", createdBy: "bina" }), change({ changeId: "c2", createdBy: "anita" })],
      "/api/shaping/operations": () => ({ builtIn: builtIns }),
      "/api/shaping/changes/c1/approve": () => change({ status: "APPROVED" }),
      "/api/shaping/changes/c1/reject": () => change({ status: "REJECTED" }),
    });
    renderAt("/reference-data/word-lists");

    const queue = await screen.findByTestId("change-queue");
    expect(screen.getByTestId("word-list-pending")).toBeTruthy();
    expect(within(queue).getAllByRole("button", { name: "Approve" })).toHaveLength(1);
    expect(within(queue).getByText("You proposed this; another user must decide it.")).toBeTruthy();
    expect(within(queue).getByRole("button", { name: "Reject" }).hasAttribute("disabled")).toBe(true);

    await user.click(within(queue).getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(writes().at(-1)?.url).toBe("/api/shaping/changes/c1/approve"));
  });

  it("is read-only without the manage permission", async () => {
    mockBackend(testUser(["SHAPING_VIEW"]), {
      "/api/shaping/word-lists": () => [honorifics],
      "/api/shaping/changes": () => [],
      "/api/shaping/operations": () => ({ builtIn: builtIns }),
    });
    renderAt("/reference-data/word-lists");
    const box = (await screen.findByLabelText("honorifics")) as HTMLTextAreaElement;
    expect(box.disabled).toBe(true);
    expect(screen.queryByRole("button", { name: "Propose change" })).toBeNull();
  });

  it("tests a value on a parser and shows what it returned", async () => {
    const user = userEvent.setup();
    document.cookie = "XSRF-TOKEN=t";
    mockBackend(testUser(["SHAPING_VIEW"]), {
      "/api/shaping/word-lists": () => [honorifics],
      "/api/shaping/changes": () => [],
      "/api/shaping/operations": () => ({ builtIn: builtIns }),
      "/api/shaping/word-lists/test": () => ({
        parser: "name-normalise@1.0", value: "Shri Ram", values: { nameNormalised: "RAM" }, confidence: 1, warnings: [],
      }),
    });
    renderAt("/reference-data/word-lists");

    await screen.findByLabelText("honorifics");
    await waitFor(() => expect((screen.getByLabelText("Parser") as HTMLSelectElement).value).toBe("name-normalise@1.0"));
    await user.type(screen.getByLabelText("Value"), "Shri Ram");
    await user.click(screen.getByRole("button", { name: "Test" }));
    const result = await screen.findByTestId("test-result");
    expect(within(result).getByText("RAM")).toBeTruthy();
    expect(writes().at(-1)).toEqual({
      url: "/api/shaping/word-lists/test",
      body: { parser: "name-normalise@1.0", value: "Shri Ram" },
    });
  });
});

describe("Shaping plug-ins page", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shows built-in operations with versions and each jar with its status, hash and packs", async () => {
    mockBackend(testUser(["SHAPING_VIEW"]), {
      "/api/shaping/operations": () => ({ builtIn: builtIns }),
      "/api/shaping/plugins": () => plugins,
      "/api/shaping/changes": () => [],
    });
    renderAt("/settings/shaping-plugins");

    const table = await screen.findByTestId("plugin-table");
    expect(await screen.findByText("header-detect")).toBeTruthy();
    expect(within(table).getByText("LOADED")).toBeTruthy();
    expect(within(table).getByText("NOT_APPROVED")).toBeTruthy();
    expect(within(table).getByText("REJECTED")).toBeTruthy();
    expect(within(table).getByText("unsupported apiVersion 2.0")).toBeTruthy();
    expect(within(table).getByText("reference-pack")).toBeTruthy();
    expect(within(table).getByText("tidy-rows@1.0")).toBeTruthy();
    expect(within(table).getByText("a".repeat(12)).getAttribute("title")).toBe("a".repeat(64));
    // A viewer can test a loaded plug-in's parser but not propose an approval or revocation.
    expect(within(table).queryByRole("button", { name: /^Propose/ })).toBeNull();
    expect(within(table).getByRole("button", { name: "Test tidy-rows@1.0" })).toBeTruthy();
  });

  it("proposes approving a new jar and revoking an approved one", async () => {
    const user = userEvent.setup();
    document.cookie = "XSRF-TOKEN=t";
    mockBackend(testUser(["SHAPING_VIEW", "SHAPING_MANAGE"]), {
      "/api/shaping/operations": () => ({ builtIn: builtIns }),
      "/api/shaping/plugins": () => plugins,
      "/api/shaping/changes": () => [],
      [`/api/shaping/plugins/${"b".repeat(64)}/proposals`]: () => change({ kind: "PLUGIN", action: "APPROVE" }),
      [`/api/shaping/plugins/${"a".repeat(64)}/proposals`]: () => change({ kind: "PLUGIN", action: "REVOKE" }),
    });
    renderAt("/settings/shaping-plugins");

    await user.click(await screen.findByRole("button", { name: "Propose approval of new.jar" }));
    await waitFor(() => expect(writes().at(-1)).toEqual({
      url: `/api/shaping/plugins/${"b".repeat(64)}/proposals`,
      body: { action: "APPROVE" },
    }));
    await user.click(screen.getByRole("button", { name: "Propose revocation of ok.jar" }));
    await waitFor(() => expect(writes().at(-1)).toEqual({
      url: `/api/shaping/plugins/${"a".repeat(64)}/proposals`,
      body: { action: "REVOKE" },
    }));
  });

  it("offers Approve and Reject on a waiting plug-in change to a different user", async () => {
    const user = userEvent.setup();
    document.cookie = "XSRF-TOKEN=t";
    mockBackend(testUser(["SHAPING_VIEW", "SHAPING_APPROVE"]), {
      "/api/shaping/operations": () => ({ builtIn: builtIns }),
      "/api/shaping/plugins": () => plugins,
      "/api/shaping/changes": () => [
        change({ changeId: "p1", kind: "PLUGIN", action: "APPROVE", target: "b".repeat(64), payload: { name: "new-plugin" } }),
      ],
      "/api/shaping/changes/p1/reject": () => change({ status: "REJECTED" }),
    });
    renderAt("/settings/shaping-plugins");

    const queue = await screen.findByTestId("change-queue");
    expect(within(queue).getByText(/Approve plug-in new-plugin/)).toBeTruthy();
    await user.type(within(queue).getByLabelText("Reason"), "not reviewed");
    await user.click(within(queue).getByRole("button", { name: "Reject" }));
    await waitFor(() => expect(writes().at(-1)).toEqual({
      url: "/api/shaping/changes/p1/reject",
      body: { reason: "not reviewed" },
    }));
  });

  it("tests a parser from the plug-ins page: a Test button picks it and the result shows every output", async () => {
    const user = userEvent.setup();
    document.cookie = "XSRF-TOKEN=t";
    const loaded: PluginInfo = { ...(plugins[0] as PluginInfo), provides: ["card-id@1.0"] };
    mockBackend(testUser(["SHAPING_VIEW"]), {
      "/api/shaping/operations": () => ({ builtIn: builtIns }),
      "/api/shaping/plugins": () => [loaded],
      "/api/shaping/changes": () => [],
      "/api/shaping/word-lists/test": () => ({
        parser: "card-id@1.0", value: "X-1", values: { district: "North", districtLgd: "9", parts: ["X", "1"] },
        confidence: 0.6, warnings: ["serial is short"],
      }),
    });
    renderAt("/settings/shaping-plugins");

    const form = await screen.findByRole("region", { name: "Test a parser or plug-in" });
    await waitFor(() => expect((within(form).getByLabelText("Parser") as HTMLSelectElement).value).toBe("name-normalise@1.0"));
    await user.click(await screen.findByRole("button", { name: "Test card-id@1.0" }));
    expect((within(form).getByLabelText("Parser") as HTMLSelectElement).value).toBe("card-id@1.0");

    await user.type(within(form).getByLabelText("Value"), "X-1");
    await user.type(within(form).getByLabelText("Options (JSON, optional)"), '{{"strict": true}');
    await user.click(within(form).getByRole("button", { name: "Test" }));
    const result = await screen.findByTestId("test-result");
    expect(within(result).getByText("districtLgd")).toBeTruthy();
    expect(within(result).getByText('["X","1"]')).toBeTruthy();
    expect(within(result).getByText("serial is short")).toBeTruthy();
    expect(writes().at(-1)).toEqual({
      url: "/api/shaping/word-lists/test",
      body: { parser: "card-id@1.0", value: "X-1", config: { strict: true } },
    });
  });
});
