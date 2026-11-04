import { vi } from "vitest";
import { render } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import { App } from "../app/App";
import { createAppRegistry } from "../app/features";
import type { CurrentUser, Permission } from "../api/types";

/** Test-only: a signed-in user with these permissions. */
export function testUser(permissions: Permission[]): CurrentUser {
  return {
    username: "anita",
    displayName: "Anita Admin",
    roles: ["PLATFORM_ADMIN"],
    permissions,
    departmentScope: ["ALL"],
    mustChangePassword: false,
    authSource: "LOCAL",
    sessionTimeoutSeconds: 1800,
  };
}

/** One request the mocked backend received. */
export interface ReceivedRequest {
  path: string;
  search: string;
  headers: Headers;
}

/**
 * Test-only: replaces fetch with a backend that is ready and signed in as `user`, answering `routes` (by path) and
 * an empty list otherwise. Returns the requests it received.
 */
export function mockBackend(
  user: CurrentUser,
  routes: Record<string, (request: ReceivedRequest) => unknown>,
): ReceivedRequest[] {
  const received: ReceivedRequest[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const request =
        input instanceof Request ? input : new Request(new URL(String(input), "http://localhost"));
      const url = new URL(request.url);
      const entry = { path: url.pathname, search: url.search, headers: request.headers };
      received.push(entry);
      const json = (body: unknown) =>
        new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
      if (url.pathname === "/api/setup/status") {
        return json({ mode: "READY", source: "LOCAL_STORE", settings: null, settingsFile: "",
          environmentVariablesPresent: false, schemaVersion: "21", onboardingInProgress: false, resetAllowed: false,
          problem: null, adminRequired: false });
      }
      if (url.pathname === "/api/v1/auth/me") {
        return json(user);
      }
      const route = routes[url.pathname];
      return json(route ? route(entry) : []);
    }),
  );
  return received;
}

/** Test-only: renders the whole app at `path`. */
export function renderAt(path: string) {
  render(
    <App
      registry={createAppRegistry()}
      initialPath={path}
      queryClient={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    />,
  );
}
