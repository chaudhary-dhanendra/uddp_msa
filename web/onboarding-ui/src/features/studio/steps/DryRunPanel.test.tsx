import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { I18nProvider } from "../../../i18n/i18n";
import { DryRunPanel } from "./DryRunPanel";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const completed = {
  status: "COMPLETED",
  rowsRequested: 50000,
  rowsChecked: 50000,
  moreRows: true,
  rowsWithErrors: 4210,
  rowsWithWarnings: 12,
  problem: null,
  groups: [
    {
      code: "UNMAPPED_CODE",
      severity: "error",
      field: "gender",
      count: 4210,
      message: "Resident: no crosswalk value for X",
      fix: { kind: "CROSSWALK", key: "gender" },
      suggestion: null,
      sampleRows: [10, 20, 30],
    },
  ],
  startedAt: "2026-11-05T10:00:00Z",
  finishedAt: "2026-11-05T10:01:00Z",
};

afterEach(() => vi.unstubAllGlobals());

describe("DryRunPanel", () => {
  it("starts a check, polls while it runs and shows the reason groups with their fix links", async () => {
    let calls = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const request = input instanceof Request ? input : new Request(new URL(String(input), "http://localhost"));
        if (request.method === "POST") {
          return json({ ...completed, status: "RUNNING", groups: [], rowsChecked: 0 }, 202);
        }
        calls += 1;
        return json(calls === 1 ? { ...completed, status: "NONE", groups: [] } : completed);
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <I18nProvider>
            <DryRunPanel draftId="d1" />
          </I18nProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await userEvent.click(await screen.findByTestId("dry-run-start"));
    expect(await screen.findByTestId("dry-run-running")).toBeTruthy();
    const result = await screen.findByTestId("dry-run-result", {}, { timeout: 5000 });
    expect(result.textContent).toContain("50,000 rows checked");
    expect(result.textContent).toContain("4,210 would be held");
    const groups = screen.getByTestId("dry-run-groups");
    expect(groups.textContent).toContain("Rows 10, 20, 30");
    expect(within(groups).getByRole("link").getAttribute("href")).toBe("/onboarding-studio/drafts/d1/5?focus=gender");
  });
});
