import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { SessionProvider } from "../auth";
import { GeographyMasterPage } from ".";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";
import type { GeographyPreview } from "../../api/types";

afterEach(() => vi.unstubAllGlobals());

const CLEAN: GeographyPreview = {
  sourceLabel: "snapshot.csv",
  asOf: "2024-01-01",
  rows: 12,
  rowsByType: { STATE: 2, DISTRICT: 10 },
  errors: [],
  errorsTruncated: false,
  changesByType: { STATE: { ADDED: 2 }, DISTRICT: { ADDED: 9, RENAMED: 1 } },
  changeTotals: { ADDED: 11, RENAMED: 1 },
  samples: {
    ADDED: [{ changeType: "ADDED", entityType: "STATE", code: "S1", oldValue: null, newValue: "Synthetic State 1" }],
    RENAMED: [{ changeType: "RENAMED", entityType: "DISTRICT", code: "D1", oldValue: "Old", newValue: "New" }],
  },
};
const BROKEN: GeographyPreview = {
  ...CLEAN,
  errors: [{ line: 7, message: "parent DISTRICT D9 is unknown" }],
  changesByType: {},
  changeTotals: {},
  samples: {},
};

interface Sent {
  url: string;
  method: string;
  form: FormData | null;
}

/** A backend for the page: `validate` answers the check; an upload is recorded and its run finishes at once. */
function backend(validate: GeographyPreview) {
  const sent: Sent[] = [];
  let loaded = false;
  const run = (status: string) => ({
    runId: "11111111-1111-1111-1111-111111111111", triggerKind: "UPLOAD", actor: "anita", status, snapshotId: null,
    changeCount: 12, error: null, startedAt: "2026-10-29T10:00:00Z", finishedAt: null, sourceLabel: "snapshot.csv",
  });
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), "http://localhost"), init);
    const url = new URL(request.url);
    const json = (body: unknown, status = 200) =>
      new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
    if (request.method === "POST") {
      sent.push({ url: url.pathname, method: "POST", form: init?.body instanceof FormData ? init.body : null });
      if (url.pathname === "/api/geography/snapshots/validate") {
        return json(validate);
      }
      loaded = true;
      return json(run("RUNNING"), 202);
    }
    switch (url.pathname) {
      case "/api/v1/auth/me":
        return json(testUser(["GEOGRAPHY_UPLOAD", "GEOGRAPHY_VIEW"]));
      case "/api/geography/sync/status":
        return json({ activeSnapshotId: "snap-1", activatedAt: "2026-10-29T10:00:01Z", entityCount: 12, stale: false });
      case "/api/geography/entity-types":
        return json([{ entityType: "STATE", levelOrder: 1 }]);
      case "/api/geography/entities":
        return json({ items: [], page: 0, size: 1, totalItems: loaded ? 2 : 0, totalPages: 1, sort: "name,asc" });
      case "/api/geography/sync/runs":
        return json({ items: loaded ? [run("SUCCEEDED")] : [], page: 0, size: 10, totalItems: 1, totalPages: 1, sort: "x" });
      default:
        return json([]);
    }
  }));
  return sent;
}

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <SessionProvider>
        <GeographyMasterPage />
      </SessionProvider>
    </QueryClientProvider>,
  );
}

const snapshot = () => new File(["entityType,code,name\nSTATE,S1,A\n"], "snapshot.csv", { type: "text/csv" });

describe("Geography master page", () => {
  it("walks through choose, check and load, asking for confirmation first", async () => {
    const sent = backend(CLEAN);
    const user = userEvent.setup();
    renderPage();

    const load = await screen.findByRole("button", { name: "Load into master" });
    expect(load).toBeDisabled();
    expect(screen.getByRole("button", { name: "Check file" })).toBeDisabled();
    expect(screen.getByRole("link", { name: "Download an example file" })).toHaveAttribute("href", "/api/geography/snapshots/template");

    await user.upload(screen.getByLabelText(/Drop a .csv or .json file/), snapshot());
    expect(screen.getByTestId("geo-file")).toHaveTextContent("snapshot.csv");

    await user.click(screen.getByRole("button", { name: "Check file" }));
    const report = await screen.findByTestId("geo-report");
    expect(report).toHaveTextContent("12 rows read.");
    expect(within(report).getByTestId("geo-diff")).toHaveTextContent("DISTRICT");
    expect(within(report).getByText("Renamed: first 1")).toBeInTheDocument();
    expect(sent.map((call) => call.url)).toEqual(["/api/geography/snapshots/validate"]);
    expect(load).toBeEnabled();

    await user.click(load);
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveTextContent("adds and updates");
    expect(dialog).toHaveTextContent("never deletes");
    expect(sent).toHaveLength(1);
    await user.click(within(dialog).getByRole("button", { name: "Load now" }));

    await waitFor(() => expect(screen.getByTestId("geo-result")).toHaveTextContent("Loaded: 12 changes applied."));
    const upload = sent[1] as Sent;
    expect(upload.url).toBe("/api/geography/snapshots");
    expect(upload.form?.get("confirm")).toBe("true");
    expect((upload.form?.get("file") as File).name).toBe("snapshot.csv");
    await waitFor(() => expect(screen.getByTestId("geo-runs")).toHaveTextContent("UPLOAD"));
    expect(screen.getByTestId("geo-runs")).toHaveTextContent("snapshot.csv");
    expect(screen.getByTestId("geo-active")).toHaveTextContent("Active snapshot snap-1");
    await waitFor(() => expect(screen.getByTestId("geo-type-counts")).toHaveTextContent("STATE2"));
  });

  it("lists the errors with their line numbers and keeps Load disabled", async () => {
    backend(BROKEN);
    const user = userEvent.setup();
    renderPage();
    await user.upload(await screen.findByLabelText(/Drop a .csv or .json file/), snapshot());
    await user.click(screen.getByRole("button", { name: "Check file" }));

    const errors = await screen.findByTestId("geo-errors");
    expect(within(errors).getByRole("columnheader", { name: "Line" })).toBeInTheDocument();
    expect(within(errors).getByRole("cell", { name: "7" })).toBeInTheDocument();
    expect(within(errors).getByText("parent DISTRICT D9 is unknown")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Load into master" })).toBeDisabled();
    expect(screen.queryByTestId("geo-diff")).not.toBeInTheDocument();
  });

  it("shows the menu item only to users who may upload", async () => {
    mockBackend(testUser(["GEOGRAPHY_UPLOAD", "ONBOARDING_VIEW"]), {});
    renderAt("/no-such-page");
    expect(await screen.findByTestId("nav-geography-master")).toHaveAttribute("href", "/admin/geography");
    vi.unstubAllGlobals();
  });

  it("hides the menu item without the permission (403 for everyone else)", async () => {
    mockBackend(testUser(["GEOGRAPHY_VIEW", "GEOGRAPHY_SYNC", "ONBOARDING_VIEW"]), {});
    renderAt("/no-such-page");
    await screen.findByTestId("nav-inbox");
    expect(screen.queryByTestId("nav-geography-master")).not.toBeInTheDocument();
  });
});
