import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { QueryClient } from "@tanstack/react-query";
import { App } from "../../app/App";
import { createAppRegistry } from "../../app/features";
import type { CurrentUser } from "../../api/types";

const USER: CurrentUser = {
  username: "anita",
  displayName: "Anita Admin",
  roles: ["PLATFORM_ADMIN"],
  permissions: ["AUDIT_VIEW", "PLATFORM_SETTINGS"],
  departmentScope: ["ALL"],
  mustChangePassword: false,
  authSource: "LOCAL",
  sessionTimeoutSeconds: 1800,
};

/** A backend whose session ends when logout is called; /api/v1/auth/me answers 401 afterwards. */
function mockBackend() {
  let signedIn = true;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const request =
        input instanceof Request ? input : new Request(new URL(String(input), "http://localhost"));
      const path = new URL(request.url).pathname;
      const json = (body: unknown, code = 200) =>
        new Response(JSON.stringify(body), { status: code, headers: { "Content-Type": "application/json" } });
      if (path === "/api/setup/status") {
        return json({ mode: "READY", source: "LOCAL_STORE", settings: null, settingsFile: "",
          environmentVariablesPresent: false, schemaVersion: "21", onboardingInProgress: false, resetAllowed: false,
          problem: null, adminRequired: false });
      }
      if (path === "/api/v1/auth/logout") {
        signedIn = false;
        return new Response(null, { status: 204 });
      }
      if (path === "/api/v1/auth/me") {
        return signedIn
          ? json(USER)
          : json({ status: 401, error: "UNAUTHENTICATED", message: "Sign in", path }, 401);
      }
      if (path === "/api/v1/auth/providers") {
        return json({ local: true, oidc: false, oidcDisplayName: null, oidcLoginUrl: null });
      }
      if (path === "/api/v1/audit-events/actions") {
        return json(["LOGIN"]);
      }
      if (path === "/api/v1/audit-events") {
        return json({ items: [], page: 0, size: 50, totalItems: 0, totalPages: 0, sort: "occurredAt,desc" });
      }
      return json(path.includes("/health") ? {} : []);
    }),
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("sign out", () => {
  it("shows the sign-in screen and drops every cached query of the previous user", async () => {
    mockBackend();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<App registry={createAppRegistry()} initialPath="/audit" queryClient={queryClient} />);
    expect(await screen.findByTestId("current-user")).toHaveTextContent("Anita Admin");
    expect(await screen.findByTestId("audit-table")).toBeInTheDocument();
    expect(queryClient.getQueryData(["audit", "actions"])).toEqual(["LOGIN"]);

    await userEvent.click(screen.getByTestId("sign-out"));

    expect(await screen.findByTestId("login-screen")).toBeInTheDocument();
    expect(screen.queryByTestId("current-user")).not.toBeInTheDocument();
    expect(screen.queryByTestId("audit-table")).not.toBeInTheDocument();
    expect(screen.queryByTestId("session-expired")).not.toBeInTheDocument();
    expect(queryClient.getQueryData(["audit", "actions"])).toBeUndefined();
    const remaining = queryClient
      .getQueryCache()
      .getAll()
      .filter((query) => query.state.data !== null && query.state.data !== undefined);
    // Only what the sign-in screen loads itself (the sign-in providers) is cached again.
    expect(remaining.map((query) => query.queryKey)).toEqual([["auth", "providers"]]);
  });
});
