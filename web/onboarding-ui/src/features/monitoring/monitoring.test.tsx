import { afterEach, describe, expect, it, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";

afterEach(() => vi.unstubAllGlobals());

const ALERT = {
  notificationId: "n1",
  stateCode: "ST1",
  sourceSystem: "pds",
  metric: "LOAD_FAILURES",
  observed: 2,
  threshold: 1,
  severity: "CRITICAL",
  message: "Load failures: LOAD_FAILURES is 2.0 (above 1.0) for ST1/pds",
  createdAt: "2026-10-28T00:00:00Z",
  readAt: null,
};

const ROUTES = {
  "/api/monitoring/overview": () => [
    {
      stateCode: "ST1",
      sourceSystem: "pds",
      department: "FOOD",
      DQ_SCORE: 87.456,
      LOAD_FAILURES: 2,
      GATE_FAILURES: 0,
      SCHEDULE_MISSES: 1,
      MATCH_DRIFT: null,
    },
  ],
  "/api/monitoring/notifications": () => [ALERT],
  "/api/monitoring/alert-rules": () => [
    {
      ruleId: "r1",
      name: "Load failures",
      metric: "LOAD_FAILURES",
      comparison: "GT",
      threshold: 1,
      severity: "CRITICAL",
      stateCode: null,
      sourceSystem: null,
      enabled: true,
    },
  ],
};

describe("monitoring", () => {
  it("shows each source's health, the alerts, the rules, and unread alerts in the header", async () => {
    mockBackend(testUser(["ONBOARDING_VIEW"]), ROUTES);
    renderAt("/monitoring");

    const table = await screen.findByTestId("monitoring-overview");
    const row = within(table).getAllByRole("row")[1];
    if (!row) {
      throw new Error("expected a source row");
    }
    expect(row).toHaveTextContent("FOOD · ST1/pds");
    expect(row).toHaveTextContent("87.5"); // data-quality score, rounded
    expect(row).toHaveTextContent("—"); // matching drift is not reported yet
    expect(await screen.findByTestId("alert")).toHaveTextContent("[CRITICAL] Load failures: LOAD_FAILURES is 2.0");
    expect(screen.getByTestId("alert-rules")).toHaveTextContent("Load failures: LOAD_FAILURES > 1 (CRITICAL)");
    expect(await screen.findByTestId("notification-bell")).toHaveTextContent("Alerts (1)");
    expect(screen.queryByRole("button", { name: "Add rule" })).not.toBeInTheDocument();
  });

  it("filters by department and source, and lets an operator add a rule and mark an alert read", async () => {
    const user = userEvent.setup();
    const received = mockBackend(testUser(["ONBOARDING_VIEW", "ONBOARDING_RUN"]), ROUTES);
    renderAt("/monitoring");
    await screen.findByTestId("monitoring-overview");

    await user.type(screen.getByLabelText("Department"), "FOOD");
    await user.type(screen.getByLabelText("Source"), "pds");
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await vi.waitFor(() => {
      const last = received.filter((r) => r.path === "/api/monitoring/overview").at(-1);
      expect(last?.search).toContain("department=FOOD");
      expect(last?.search).toContain("source=pds");
    });

    await user.type(screen.getByLabelText("Rule name"), "Quality drops");
    await user.selectOptions(screen.getByLabelText("Metric"), "DQ_SCORE");
    await user.selectOptions(screen.getByLabelText("Fires when the value is"), "LT");
    await user.clear(screen.getByLabelText("Threshold"));
    await user.type(screen.getByLabelText("Threshold"), "80");
    await user.click(screen.getByRole("button", { name: "Add rule" }));
    await user.click(await screen.findByRole("button", { name: "Mark read" }));

    await vi.waitFor(() => {
      expect(received.some((r) => r.path === "/api/monitoring/alert-rules")).toBe(true);
      expect(received.some((r) => r.path === "/api/monitoring/notifications/n1/read")).toBe(true);
    });
  });
});
