import { afterEach, describe, expect, it, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";

afterEach(() => vi.unstubAllGlobals());

const RUN = "11111111-2222-3333-4444-555555555555";

const ITEMS = [
  {
    department: "FOOD",
    item: {
      id: `GATE_FAILURE:1`,
      type: "GATE_FAILURE",
      title: "Quality gate failed: ERROR_RATE_PCT on pds",
      detail: "Measured 40 against a limit of 10",
      priority: "HIGH",
      stateCode: "ST1",
      sourceSystem: "pds",
      createdAt: "2026-10-01T00:00:00Z",
      link: `/onboarding-runs/${RUN}`,
    },
  },
  {
    department: "FOOD",
    item: {
      id: "REVIEW_TASK:2",
      type: "REVIEW_TASK",
      title: "Possible match to review",
      detail: "Match probability 0.82",
      priority: "LOW",
      stateCode: "ST1",
      sourceSystem: "pds",
      createdAt: "2026-10-20T00:00:00Z",
      link: "/stewardship",
    },
  },
];

const COUNTS = { GATE_FAILURE: 1, PAUSED_RUN: 0, REVIEW_TASK: 1 };

const ROUTES = {
  "/api/inbox/items": () => ITEMS,
  "/api/inbox/counts": () => COUNTS,
  "/api/v1/overview": () => ({ totals: {}, states: [] }),
};

describe("exception inbox", () => {
  it("lists what needs a person with a link to the screen that resolves each item", async () => {
    mockBackend(testUser(["ONBOARDING_VIEW"]), ROUTES);
    renderAt("/inbox");

    const table = await screen.findByTestId("inbox-items");
    const [, first, second, ...rest] = within(table).getAllByRole("row");
    expect(rest).toHaveLength(0); // header + two items
    if (!first || !second) {
      throw new Error("expected two item rows");
    }
    expect(within(first).getByText("Quality gate failed: ERROR_RATE_PCT on pds")).toBeInTheDocument();
    expect(within(first).getByText("FOOD · ST1/pds")).toBeInTheDocument();
    expect(within(first).getByRole("link", { name: "Open" })).toHaveAttribute("href", `/onboarding-runs/${RUN}`);
    expect(within(second).getByRole("link", { name: "Open" })).toHaveAttribute("href", "/stewardship");
  });

  it("sends the department, type, priority and age filters to the backend", async () => {
    const user = userEvent.setup();
    const received = mockBackend(testUser(["ONBOARDING_VIEW"]), ROUTES);
    renderAt("/inbox");
    await screen.findByTestId("inbox-items");

    await user.type(screen.getByLabelText("Department"), "FOOD");
    await user.selectOptions(screen.getByLabelText("Type"), "GATE_FAILURE");
    await user.selectOptions(screen.getByLabelText("Priority"), "HIGH");
    await user.type(screen.getByLabelText("At least this many days old"), "7");
    await user.click(screen.getByRole("button", { name: "Apply filters" }));

    await vi.waitFor(() => {
      const last = received.filter((r) => r.path === "/api/inbox/items").at(-1);
      expect(last?.search).toContain("department=FOOD");
      expect(last?.search).toContain("type=GATE_FAILURE");
      expect(last?.search).toContain("priority=HIGH");
      expect(last?.search).toContain("minAgeDays=7");
    });
  });

  it("shows the count per type on the Overview, each linking to the filtered inbox", async () => {
    mockBackend(testUser(["ONBOARDING_VIEW", "REPORT_VIEW"]), ROUTES);
    renderAt("/");

    const counts = await screen.findByTestId("inbox-counts");
    expect(within(counts).getByText("Quality gate failures")).toHaveAttribute("href", "/inbox?type=GATE_FAILURE");
    expect(counts).toHaveTextContent("Runs waiting for release: 0");
    expect(counts).toHaveTextContent("Match review tasks: 1");
  });
});
