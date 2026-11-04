import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { QueryClient } from "@tanstack/react-query";
import { App } from "../../app/App";
import { createAppRegistry } from "../../app/features";
import type { CurrentUser, Permission, SetupStatus } from "../../api/types";

function status(mode: "SETUP" | "READY", adminRequired = false): SetupStatus {
  return {
    mode,
    source: mode === "SETUP" ? "NONE" : "LOCAL_STORE",
    settings:
      mode === "SETUP"
        ? null
        : {
            host: "localhost",
            port: 5432,
            database: "uddp_demo",
            user: "uddp",
            passwordSet: true,
          },
    settingsFile: "C:/Users/demo/.uddp/uddp.properties",
    environmentVariablesPresent: false,
    schemaVersion: mode === "SETUP" ? null : "16",
    onboardingInProgress: false,
    resetAllowed: mode === "READY",
    problem: null,
    adminRequired,
  };
}

const ALL_PERMISSIONS: Permission[] = [
  "STATE_PACK_VIEW",
  "STATE_PACK_CREATE",
  "STATE_PACK_EDIT",
  "STATE_PACK_PUBLISH",
  "ONBOARDING_VIEW",
  "RESIDENT_VIEW",
  "PLATFORM_SETTINGS",
  "AUDIT_VIEW",
];

function user(
  permissions: Permission[],
  roles = ["PLATFORM_ADMIN"],
): CurrentUser {
  return {
    username: "anita",
    displayName: "Anita Admin",
    roles,
    permissions,
    departmentScope: ["ALL"],
    mustChangePassword: false,
    authSource: "LOCAL",
    sessionTimeoutSeconds: 1800,
  };
}

interface Backend {
  mode: "SETUP" | "READY";
  adminRequired?: boolean;
  me?: CurrentUser | null;
  /** Paths (substring) that answer 401, as an expired session does. */
  expired?: string[];
}

function mockBackend(backend: Backend) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url =
        typeof input === "string"
          ? input
          : input instanceof URL
            ? input.href
            : input.url;
      const json = (body: unknown, code = 200) =>
        new Response(JSON.stringify(body), {
          status: code,
          headers: { "Content-Type": "application/json" },
        });
      if (url.includes("/api/setup/status")) {
        return json(status(backend.mode, backend.adminRequired));
      }
      if (url.includes("/api/v1/auth/me")) {
        return backend.me
          ? json(backend.me)
          : json(
              {
                status: 401,
                error: "UNAUTHENTICATED",
                message: "Sign in",
                path: "/api/v1/auth/me",
              },
              401,
            );
      }
      if (url.includes("/api/v1/auth/providers")) {
        return json({
          local: true,
          oidc: false,
          oidcDisplayName: null,
          oidcLoginUrl: null,
        });
      }
      if (backend.expired?.some((path) => url.includes(path))) {
        return json(
          {
            status: 401,
            error: "UNAUTHENTICATED",
            message: "Sign in",
            path: url,
          },
          401,
        );
      }
      return json(url.includes("/health") ? {} : []);
    }),
  );
}

function renderApp(path: string) {
  render(
    <App
      registry={createAppRegistry()}
      initialPath={path}
      queryClient={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    />,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("setup mode", () => {
  it("shows only the Setup screen while no database is configured", async () => {
    mockBackend({ mode: "SETUP" });
    renderApp("/");
    expect(await screen.findByTestId("setup-screen")).toBeInTheDocument();
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toHaveAttribute(
      "type",
      "password",
    );
  });

  it("asks for the first administrator once the database is ready but has no user", async () => {
    mockBackend({ mode: "READY", adminRequired: true });
    renderApp("/");
    expect(
      await screen.findByTestId("create-admin-screen"),
    ).toBeInTheDocument();
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
  });

  it("shows the Settings screen with the password masked once configured", async () => {
    mockBackend({ mode: "READY", me: user(ALL_PERMISSIONS) });
    renderApp("/settings");
    expect(
      await screen.findByText("•••••••• (stored encrypted)"),
    ).toBeInTheDocument();
    expect(screen.getByText("Saved from the Setup screen")).toBeInTheDocument();
    expect(screen.getByTestId("reset-demo")).toBeDisabled();
  });
});

describe("authentication", () => {
  it("shows the sign-in screen when nobody is signed in", async () => {
    mockBackend({ mode: "READY", me: null });
    renderApp("/");
    expect(await screen.findByTestId("login-screen")).toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toHaveAttribute(
      "type",
      "password",
    );
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
  });

  it("shows who is signed in and only the menu entries the permissions allow", async () => {
    mockBackend({
      mode: "READY",
      me: user(["RESIDENT_VIEW"], ["PROGRAM_USER"]),
    });
    renderApp("/golden-residents");
    expect(await screen.findByTestId("current-user")).toHaveTextContent(
      "Anita Admin",
    );
    expect(screen.getByTestId("user-menu")).toHaveTextContent("PROGRAM_USER");
    expect(screen.getByTestId("nav-golden")).toBeInTheDocument();
    expect(screen.queryByTestId("nav-studio")).not.toBeInTheDocument();
    expect(screen.queryByTestId("nav-settings")).not.toBeInTheDocument();
  });

  it("returns to the sign-in screen with a message when the session expires", async () => {
    mockBackend({
      mode: "READY",
      me: user(ALL_PERMISSIONS),
      expired: ["/api/v1/overview"],
    });
    renderApp("/");
    expect(await screen.findByTestId("session-expired")).toBeInTheDocument();
    expect(screen.getByTestId("login-screen")).toBeInTheDocument();
  });

  it("requires a password change before anything else", async () => {
    mockBackend({
      mode: "READY",
      me: { ...user([]), mustChangePassword: true },
    });
    renderApp("/");
    expect(
      await screen.findByTestId("change-password-form"),
    ).toBeInTheDocument();
    const userEvents = userEvent.setup();
    await userEvents.type(
      screen.getByLabelText("New password"),
      "First-Secret-123",
    );
    await userEvents.type(
      screen.getByLabelText("Repeat the new password"),
      "Different-123!",
    );
    expect(
      screen.getByText("The two new passwords differ."),
    ).toBeInTheDocument();
    expect(screen.getByTestId("change-password")).toBeDisabled();
  });
});
