import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SessionProvider } from "../auth";
import { RunReleasePanel } from "./index";

function response(body: unknown) {
  return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
}

describe("RunReleasePanel", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("keeps processing and stewardship rejections distinct", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const path = input instanceof Request ? input.url : String(input);
      if (path.includes("/auth/me")) {
        return Promise.resolve(response({ permissions: ["STEWARD_HELD_VIEW", "BATCH_VIEW", "AUDIT_VIEW"] }));
      }
      if (path.includes("/held-records")) {
        const status = new URL(path, "http://localhost").searchParams.get("status");
        return Promise.resolve(response({ totalItems: status === "REJECTED_FINAL" ? 0 : 0, items: [] }));
      }
      if (path.includes("/release")) {
        return Promise.resolve(response({ gates: [], decisions: [] }));
      }
      return Promise.resolve(response({ status: "PAUSED", deliveryChanges: { new: 1, changed: 0, unchanged: 8, removed: 0 },
        processing: { batchId: "batch-1", rejectedRows: 1 } }));
    }));
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <SessionProvider><MemoryRouter><RunReleasePanel runId="run-1" state="ST" source="pds" /></MemoryRouter></SessionProvider>
      </QueryClientProvider>,
    );
    expect((await screen.findByTestId("run-held-counts")).textContent).toContain(
      "Processing rejected 1 · Held 0 · Rectified 0 · Final rejected 0",
    );
    expect(screen.getByRole("link", { name: "Processing rejected rows (CSV)" }).getAttribute("href")).toBe(
      "/api/onboarding/runs/run-1/rejected-rows.csv",
    );
    expect(screen.getByRole("link", { name: "Stewardship rejection report (CSV)" }).getAttribute("href")).toBe(
      "/api/v1/batches/batch-1/rejection-report",
    );
  });
});
