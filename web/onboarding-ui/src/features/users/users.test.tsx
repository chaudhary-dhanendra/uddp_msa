import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { QueryClient } from "@tanstack/react-query";
import { App } from "../../app/App";
import { createAppRegistry } from "../../app/features";
import type { CurrentUser, Permission, RoleMatrix, UserView } from "../../api/types";

function me(permissions: Permission[]): CurrentUser {
  return {
    username: "root.admin",
    displayName: "Root Admin",
    roles: ["PLATFORM_ADMIN"],
    permissions,
    departmentScope: ["ALL"],
    mustChangePassword: false,
    authSource: "LOCAL",
    sessionTimeoutSeconds: 1800,
  };
}

function userView(username: string, roles: string[], scope: string[], enabled = true): UserView {
  return {
    userId: `00000000-0000-0000-0000-${username === "asha" ? "000000000001" : "000000000002"}`,
    username,
    displayName: username,
    enabled,
    locked: false,
    lockedUntil: null,
    failedAttempts: 0,
    mustChangePassword: false,
    authSource: "LOCAL",
    roles,
    departmentScope: scope,
    lastLoginAt: null,
    createdAt: "2026-10-27T00:00:00Z",
  };
}

const MATRIX: RoleMatrix = {
  roles: [
    { roleCode: "AUDITOR", description: "Reads everything", permissions: ["AUDIT_VIEW"] },
    { roleCode: "PLATFORM_ADMIN", description: "Runs the platform", permissions: ["AUDIT_VIEW", "USER_MANAGE"] },
  ],
  permissions: [
    { permissionCode: "AUDIT_VIEW", description: "Read the audit trail" },
    { permissionCode: "USER_MANAGE", description: "Manage users" },
  ],
};

interface Call {
  method: string;
  path: string;
  body: unknown;
}

function mockBackend(permissions: Permission[]): Call[] {
  const calls: Call[] = [];
  const users = [userView("asha", ["DATA_ENGINEER"], ["DEPT-1"]), userView("ravi", ["AUDITOR"], ["ALL"], false)];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const request =
        input instanceof Request ? input : new Request(new URL(String(input), "http://localhost"));
      const url = new URL(request.url, "http://localhost");
      const text = request.method === "GET" ? "" : await request.text();
      calls.push({ method: request.method, path: url.pathname + url.search, body: text ? JSON.parse(text) : null });
      const json = (body: unknown, code = 200) =>
        new Response(JSON.stringify(body), { status: code, headers: { "Content-Type": "application/json" } });
      const path = url.pathname;
      if (path === "/api/setup/status") {
        return json({ mode: "READY", source: "LOCAL_STORE", settings: null, settingsFile: "", environmentVariablesPresent: false,
          schemaVersion: "21", onboardingInProgress: false, resetAllowed: false, problem: null, adminRequired: false });
      }
      if (path === "/api/v1/auth/me") {
        return json(me(permissions));
      }
      if (path === "/api/v1/roles") {
        return json(MATRIX);
      }
      if (path === "/api/v1/users" && request.method === "POST") {
        return json({ user: userView("new.user", ["AUDITOR"], ["DEPT-2"]), oneTimePassword: "Temp-Pass-123-xyz" }, 201);
      }
      if (path === "/api/v1/users") {
        const status = url.searchParams.get("status");
        return json(users.filter((user) => !status || user.enabled === (status === "ENABLED")));
      }
      if (path.endsWith("/department-scope")) {
        return json(userView("asha", ["DATA_ENGINEER"], ["DEPT-1", "DEPT-2"]));
      }
      if (path.endsWith("/reset-password")) {
        return json({ user: users[0], oneTimePassword: "Reset-Pass-456-abc" });
      }
      if (path.startsWith("/api/v1/users/")) {
        return json(users[0]);
      }
      return json(path.includes("/health") ? {} : []);
    }),
  );
  return calls;
}

function renderApp(path: string) {
  render(
    <App
      registry={createAppRegistry()}
      initialPath={path}
      queryClient={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    />,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("user management", () => {
  it("lists users with status, roles and department scope, and filters by status", async () => {
    const calls = mockBackend(["USER_MANAGE", "PLATFORM_SETTINGS"]);
    renderApp("/settings/users");
    const table = await screen.findByTestId("users-table");
    expect(await within(table).findByRole("link", { name: "asha" })).toBeInTheDocument();
    expect(within(table).getByText("DATA_ENGINEER")).toBeInTheDocument();
    expect(within(table).getByText("DEPT-1")).toBeInTheDocument();
    expect(within(table).getByText("All departments")).toBeInTheDocument();
    expect(screen.getByTestId("nav-users")).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText("Status"), "DISABLED");
    expect(await within(screen.getByTestId("users-table")).findByRole("link", { name: "ravi" })).toBeInTheDocument();
    expect(calls.some((call) => call.path === "/api/v1/users?status=DISABLED")).toBe(true);
  });

  it("creates a user and shows the one-time password once", async () => {
    const calls = mockBackend(["USER_MANAGE"]);
    renderApp("/settings/users");
    await userEvent.click(await screen.findByTestId("create-user"));
    const dialog = screen.getByTestId("create-user-dialog");
    await userEvent.type(within(dialog).getByLabelText("Username"), "new.user");
    await userEvent.click(await within(dialog).findByLabelText(/AUDITOR/));
    await userEvent.type(within(dialog).getByLabelText(/Department codes/), "dept-2");
    await userEvent.click(within(dialog).getByTestId("create-user-submit"));

    expect(await screen.findByTestId("one-time-password")).toHaveTextContent("Temp-Pass-123-xyz");
    const create = calls.find((call) => call.method === "POST" && call.path === "/api/v1/users");
    expect(create?.body).toEqual({ username: "new.user", displayName: null, roles: ["AUDITOR"], departmentScope: ["DEPT-2"] });
  });

  it("edits a user's department scope and resets the password", async () => {
    const calls = mockBackend(["USER_MANAGE"]);
    renderApp("/settings/users/00000000-0000-0000-0000-000000000001");
    const departments = await screen.findByLabelText(/Department codes/);
    expect(departments).toHaveValue("DEPT-1");
    expect(screen.getByRole("heading", { name: "Department scope" })).toBeInTheDocument();
    await userEvent.clear(departments);
    await userEvent.type(departments, "DEPT-1, dept-2");
    await userEvent.click(screen.getByTestId("save-scope"));
    await userEvent.click(screen.getByTestId("reset-password"));

    expect(await screen.findByTestId("one-time-password")).toHaveTextContent("Reset-Pass-456-abc");
    const scope = calls.find((call) => call.method === "PUT");
    expect(scope?.path).toBe("/api/v1/users/00000000-0000-0000-0000-000000000001/department-scope");
    expect(scope?.body).toEqual({ departmentScope: ["DEPT-1", "DEPT-2"] });
    expect(screen.getByTestId("toggle-enabled")).toHaveTextContent("Disable");
  });

  it("shows the role and permission matrix", async () => {
    mockBackend(["USER_MANAGE"]);
    renderApp("/settings/roles");
    const matrix = await screen.findByTestId("roles-matrix");
    expect(await within(matrix).findByText("USER_MANAGE")).toBeInTheDocument();
    expect(within(matrix).getAllByLabelText("Granted")).toHaveLength(3);
    expect(within(matrix).getAllByLabelText("Not granted")).toHaveLength(1);
  });

  it("hides user management from users without USER_MANAGE", async () => {
    mockBackend(["PLATFORM_SETTINGS"]);
    renderApp("/settings");
    expect(await screen.findByTestId("nav-settings")).toBeInTheDocument();
    expect(screen.queryByTestId("nav-users")).not.toBeInTheDocument();
    expect(screen.queryByTestId("nav-roles")).not.toBeInTheDocument();
  });
});
