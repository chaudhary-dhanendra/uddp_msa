import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";

const BATCH = "11111111-1111-1111-1111-111111111111";
const HELD_ID = "22222222-2222-2222-2222-222222222222";
const reason = (severity: string, code = "ID_FORMAT") => ({ code, field: "aadhaar", message: "bad id", severity });
const record = (severity = "ERROR", status = "OPEN") => ({
  heldId: HELD_ID,
  batchId: BATCH,
  status,
  sourceSystem: "pension",
  stateCode: "ST1",
  createdAt: new Date(Date.now() - 5 * 86_400_000).toISOString(),
  originalPayload: { name: "R*** S***", aadhaar: "12" },
  currentPayload: { name: "R*** S***", aadhaar: "12" },
  reasons: [reason(severity)],
});
const groups = [{ batchId: BATCH, reasonCode: "ID_FORMAT", stateCode: "ST1", count: 12, shareOfBatchPercent: 6.5, samples: [record()] }];
const detail = (severity = "ERROR") => ({ record: record(severity), history: [{ eventId: "e1", action: "HELD", actor: "system", at: null, note: null }], suggestions: { ID_FORMAT: "Expected Card number format: [0-9]{8}" } });
const VIEW = ["STEWARDSHIP_VIEW", "STEWARD_HELD_VIEW"];
const ACTION = "33333333-3333-3333-3333-333333333333";
const bulk = (ownRequest: boolean) => ({
  actionId: ACTION,
  batchId: BATCH,
  reasonCode: "ID_FORMAT",
  stateCode: "ST1",
  sourceSystem: "pension",
  rejectReasonCode: "SOURCE_ERROR",
  note: null,
  recordCount: 12,
  status: "PENDING_APPROVAL",
  requestedBy: "alice",
  requestedAt: new Date(Date.now() - 2 * 86_400_000).toISOString(),
  decidedBy: null,
  decidedAt: null,
  decisionReason: null,
  ownRequest,
  samples: [record()],
});
const DECIDE = [...VIEW, "STEWARD_HELD_DECIDE"];

function setup(permissions: string[], extra: Record<string, () => unknown> = {}, severity = "ERROR") {
  document.cookie = "XSRF-TOKEN=t";
  return mockBackend(testUser(permissions as never), {
    "/api/v1/held-records/groups": () => groups,
    "/api/v1/held-records/bulk-actions": () => [],
    [`/api/v1/held-records/${HELD_ID}`]: () => detail(severity),
    ...extra,
  });
}

async function openRecord(user: ReturnType<typeof userEvent.setup>) {
  renderAt("/stewardship?tab=held");
  await user.click(await screen.findByRole("button", { name: /ID_FORMAT · batch/ }));
  await user.click(await screen.findByRole("button", { name: /22222222/ }));
}

describe("Held records tab", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("groups by batch and reason with count, share and age, and shows samples with a fix-in-pack link", async () => {
    const user = userEvent.setup();
    setup(DECIDE);
    renderAt("/stewardship?tab=held");
    const table = await screen.findByTestId("held-groups");
    expect(within(table).getByText("12")).toBeTruthy();
    expect(within(table).getByText("6.5%")).toBeTruthy();
    expect(within(table).getByText("5 d")).toBeTruthy();
    await user.click(within(table).getByRole("button", { name: /ID_FORMAT/ }));
    expect(within(await screen.findByTestId("held-samples")).getAllByRole("listitem")).toHaveLength(1);
    expect(screen.getByRole("link", { name: /Fix in pack: Map columns step/ })).toBeTruthy();
  });

  it("rectifies a record and keeps it open when it still fails", async () => {
    const user = userEvent.setup();
    const seen = setup(DECIDE, {
      [`/api/v1/held-records/${HELD_ID}/rectify`]: () => ({ heldId: HELD_ID, status: "OPEN", correctionVersion: 1, reasons: [reason("ERROR")] }),
    });
    await openRecord(user);
    const input = await screen.findByLabelText("Correct aadhaar");
    await user.clear(input);
    await user.type(input, "123");
    await user.click(screen.getByRole("button", { name: "Rectify" }));
    expect((await screen.findByTestId("rectify-result")).textContent).toContain("Still failing");
    expect(screen.getByRole("button", { name: "Rectify" })).toBeTruthy();
    expect(seen.some((r) => r.path.endsWith("/rectify"))).toBe(true);
  });

  it("shows the server's suggested fix for a reason", async () => {
    const user = userEvent.setup();
    setup(DECIDE);
    await openRecord(user);
    expect((await screen.findByTestId("held-suggestion")).textContent).toBe(
      "Suggested fix: Expected Card number format: [0-9]{8}",
    );
  });

  it("shows a record as valid after a passing rectify", async () => {
    const user = userEvent.setup();
    setup(DECIDE, {
      [`/api/v1/held-records/${HELD_ID}/rectify`]: () => ({ heldId: HELD_ID, status: "RECTIFIED", correctionVersion: 1, reasons: [] }),
    });
    await openRecord(user);
    await user.type(await screen.findByLabelText("Correct aadhaar"), "9");
    await user.click(screen.getByRole("button", { name: "Rectify" }));
    expect((await screen.findByTestId("rectify-result")).textContent).toContain("Valid now");
  });

  it("offers release as-is only when every reason is SEND_TO_STEWARD, and asks for a justification", async () => {
    const user = userEvent.setup();
    setup(DECIDE, {}, "SEND_TO_STEWARD");
    await openRecord(user);
    const release = await screen.findByRole("button", { name: "Release as-is" });
    expect(release.hasAttribute("disabled")).toBe(true);
    await user.type(screen.getByLabelText("Justification for releasing as it is"), "checked the card");
    expect(release.hasAttribute("disabled")).toBe(false);
  });

  it("offers no release as-is for a hard error", async () => {
    const user = userEvent.setup();
    setup(DECIDE, {}, "ERROR");
    await openRecord(user);
    await screen.findByRole("button", { name: "Rectify" });
    expect(screen.queryByRole("button", { name: "Release as-is" })).toBeNull();
  });

  it("needs a note to reject permanently with reason Other", async () => {
    const user = userEvent.setup();
    setup(DECIDE);
    await openRecord(user);
    await user.selectOptions(await screen.findByLabelText("Reject reason"), "OTHER");
    const reject = screen.getByRole("button", { name: "Reject permanently" });
    expect(reject.hasAttribute("disabled")).toBe(true);
    await user.type(screen.getByLabelText("Reject note"), "not a resident");
    expect(reject.hasAttribute("disabled")).toBe(false);
  });

  it("says a large bulk reject needs a second approver", async () => {
    const user = userEvent.setup();
    setup(DECIDE, {
      [`/api/v1/held-records/groups/${BATCH}/ID_FORMAT/reject`]: () => ({ outcome: "PENDING_APPROVAL", records: 12, actionId: "33333333-3333-3333-3333-333333333333" }),
    });
    renderAt("/stewardship?tab=held");
    await user.click(await screen.findByRole("button", { name: /ID_FORMAT · batch/ }));
    await user.type(await screen.findByLabelText("Bulk reject note"), "wrong file");
    await user.click(screen.getByRole("button", { name: "Bulk reject" }));
    expect((await screen.findByTestId("needs-second-approver")).textContent).toContain("Needs second approver");
  });

  it("hides every decision from a viewer without STEWARD_HELD_DECIDE", async () => {
    const user = userEvent.setup();
    setup(VIEW);
    await openRecord(user);
    await screen.findByTestId("held-history");
    for (const name of ["Rectify", "Release as-is", "Reject permanently"]) {
      expect(screen.queryByRole("button", { name })).toBeNull();
    }
  });

  it("lists pending bulk rejects with samples and lets another steward approve or reject with a reason", async () => {
    const user = userEvent.setup();
    const seen = setup(DECIDE, {
      "/api/v1/held-records/bulk-actions": () => [bulk(false)],
      [`/api/v1/held-records/bulk-actions/${ACTION}/decline`]: () => ({}),
    });
    renderAt("/stewardship?tab=held");
    const list = await screen.findByTestId("held-bulk-pending");
    expect(within(list).getByText("ID_FORMAT · batch 11111111")).toBeTruthy();
    expect(within(list).getByText("alice")).toBeTruthy();
    expect(within(list).getByText("2 d")).toBeTruthy();
    expect(within(list).getAllByRole("listitem")).toHaveLength(1);
    expect(await screen.findByRole("tab", { name: "Held records (1 bulk rejects waiting)" })).toBeTruthy();
    const reject = within(list).getByRole("button", { name: "Reject" });
    expect(reject.hasAttribute("disabled")).toBe(true);
    await user.type(within(list).getByLabelText("Reason for rejecting"), "fix at source");
    await user.click(reject);
    expect(await within(list).findByText("Rejected: the records stay held.")).toBeTruthy();
    const call = seen.find((r) => r.path.endsWith("/decline"));
    expect(call).toBeTruthy();
  });

  it("tells the requester that another steward must decide their own bulk reject", async () => {
    setup(DECIDE, { "/api/v1/held-records/bulk-actions": () => [bulk(true)] });
    renderAt("/stewardship?tab=held");
    const list = await screen.findByTestId("held-bulk-pending");
    expect(within(list).getByTestId("bulk-own").textContent).toBe("You requested this; another steward must decide");
    expect(within(list).queryByRole("button", { name: "Approve" })).toBeNull();
  });

  it("hides the tab from a user without STEWARD_HELD_VIEW", async () => {
    mockBackend(testUser(["STEWARDSHIP_VIEW"]), {});
    renderAt("/stewardship");
    await screen.findByRole("heading", { name: /Stewardship/i });
    expect(screen.queryByRole("tab", { name: "Held records" })).toBeNull();
  });
});
