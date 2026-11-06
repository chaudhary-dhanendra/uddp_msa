import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { StudioDraft } from "../../../api/types";
import { I18nProvider } from "../../../i18n/i18n";
import { OnboardStep } from "./OnboardStep";

const draft = {
  draftId: "d1",
  packId: "pack-a",
  lastRunId: "run-1",
  published: { version: "1.0.0" },
  source: { sourceId: "source-a", sourceType: "file", location: "studio-workspace/uploads/d1/members.xlsx" },
} as unknown as StudioDraft;

function stub(status: string): string[] {
  const paths: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const path = input instanceof Request ? input.url : String(input);
      paths.push(path);
      const body = path.includes("/onboarding-runs/") ? { run: { status }, timeline: [] }
        : path.endsWith("/landing") ? { waiting: [] } : {};
      return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    }),
  );
  return paths;
}

function renderStep(source: StudioDraft = draft) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <I18nProvider>
        <MemoryRouter>
          <OnboardStep draft={source} onBack={vi.fn()} onDone={vi.fn()} />
        </MemoryRouter>
      </I18nProvider>
    </QueryClientProvider>,
  );
}

describe("OnboardStep", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("says a paused run waits for a release, links to the run and does not offer a second start", async () => {
    stub("PAUSED");
    renderStep();
    expect((await screen.findByTestId("onboarding-paused")).textContent).toContain("needs a person to release it");
    expect(screen.getByRole("link", { name: "Open the run" }).getAttribute("href")).toBe("/onboarding-runs/run-1");
    expect((screen.getByTestId("start-onboarding") as HTMLButtonElement).disabled).toBe(true);
  });

  it("offers the next delivery of a database source once a run is completed", async () => {
    stub("COMPLETED");
    renderStep({ ...draft, source: { sourceId: "cards", sourceType: "database" } } as unknown as StudioDraft);
    await screen.findByTestId("onboarding-completed");
    expect((screen.getByTestId("start-onboarding") as HTMLButtonElement).disabled).toBe(false);
  });

  it("keeps a completed file source closed until a new delivery is uploaded", async () => {
    stub("COMPLETED");
    renderStep();
    await screen.findByTestId("onboarding-completed");
    expect((screen.getByTestId("start-onboarding") as HTMLButtonElement).disabled).toBe(true);
  });

  it("does not ask for a landing folder when the source reads one file", async () => {
    const paths = stub("COMPLETED");
    renderStep();
    await screen.findByTestId("onboarding-completed");
    expect(paths.filter((p) => p.endsWith("/landing"))).toEqual([]);
  });

  it("words a re-delivery's counts by what they mean: file rows, new or changed, skipped and one rejected row", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const path = input instanceof Request ? input.url : String(input);
        const reply = (value: unknown) =>
          new Response(JSON.stringify(value), { status: 200, headers: { "Content-Type": "application/json" } });
        if (path.includes("/onboarding-runs/")) {
          const latest = (inputCount: number, outputCount: number) => ({ latest: { inputCount, outputCount, durationMs: 100 } });
          return reply({
            run: { status: "RUNNING" },
            timeline: [
              { stage: "MAPPED", status: "COMPLETED", ...latest(10, 1) },
              { stage: "DQ_CHECKED", status: "COMPLETED", ...latest(7, 0) },
              { stage: "PACK_VALIDATED", status: "COMPLETED", ...latest(0, 0) },
            ],
          });
        }
        if (path === "/api/onboarding/runs/run-1") {
          return reply({
            status: "COMPLETED",
            deliveryChanges: { new: 2, changed: 0, unchanged: 6, removed: 0 },
            processing: {
              totalRows: 10, rowsProcessed: 10, rowsPerSecond: 333, rejectedRows: 1,
              chunks: [{ chunk: 1, rows: 10, mapped: 1, skipped: 8, rejected: 1, rowsPerSecond: 333 }],
            },
          });
        }
        return reply({});
      }),
    );
    renderStep();
    expect((await screen.findByTestId("processing-summary")).textContent).toBe(
      "10 of 10 file rows read · 333 rows/s · 1 row rejected",
    );
    expect(screen.getByTestId("delivery-changes").textContent).toBe(
      "Compared with the previous delivery: 2 new · 0 changed · 6 unchanged · 0 removed",
    );
    expect(screen.getByRole("columnheader", { name: "New or changed" })).toBeTruthy();
    expect(screen.getByRole("columnheader", { name: "Skipped" })).toBeTruthy();
    const timeline = await screen.findByTestId("stage-timeline");
    expect(timeline.textContent).toContain("10 file rows · 1 new or changed records");
    expect(timeline.textContent).toContain("7 records checked · 0 with a finding");
    expect(timeline.textContent).toContain("in 0 · out 0");
  });
});
