import { useMemo } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createBrowserRouter,
  createMemoryRouter,
  RouterProvider,
  type RouteObject,
} from "react-router-dom";
import { I18nProvider } from "../i18n/i18n";
import { SetupGate } from "../features/setup";
import { AuthGate, SessionProvider } from "../features/auth";
import { Layout } from "./Layout";
import { NotFoundPage } from "./NotFoundPage";
import type { FeatureRegistry } from "./registry";

/** Router routes for a registry: the shell layout with every feature's routes as children. */
export function routesFor(registry: FeatureRegistry): RouteObject[] {
  return [
    {
      path: "/",
      element: <Layout registry={registry} />,
      children: [
        ...registry.routes().map((route) => {
          const Component = route.component;
          return route.path === ""
            ? { index: true, element: <Component /> }
            : { path: route.path, element: <Component /> };
        }),
        { path: "*", element: <NotFoundPage /> },
      ],
    },
  ];
}

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { staleTime: 15_000, retry: 1, refetchOnWindowFocus: false },
    },
  });
}

/**
 * The application for a registry; `initialPath` renders in memory (tests) instead of the browser URL. Until the
 * platform database is configured, {@link SetupGate} shows only the Setup screen; then {@link AuthGate} shows the
 * sign-in screen until someone is signed in (`gate={false}` skips both in tests).
 */
export function App({
  registry,
  initialPath,
  queryClient,
  gate = true,
}: {
  registry: FeatureRegistry;
  initialPath?: string;
  queryClient?: QueryClient;
  gate?: boolean;
}) {
  const client = useMemo(
    () => queryClient ?? createQueryClient(),
    [queryClient],
  );
  const router = useMemo(() => {
    const routes = routesFor(registry);
    return initialPath
      ? createMemoryRouter(routes, { initialEntries: [initialPath] })
      : createBrowserRouter(routes);
  }, [registry, initialPath]);
  return (
    <I18nProvider locale="en">
      <QueryClientProvider client={client}>
        {gate ? (
          <SetupGate>
            <SessionProvider
              onSignedOut={() => void router.navigate("/", { replace: true })}
            >
              <AuthGate>
                <RouterProvider
                  router={router}
                  future={{ v7_startTransition: true }}
                />
              </AuthGate>
            </SessionProvider>
          </SetupGate>
        ) : (
          <SessionProvider>
            <RouterProvider
              router={router}
              future={{ v7_startTransition: true }}
            />
          </SessionProvider>
        )}
      </QueryClientProvider>
    </I18nProvider>
  );
}
