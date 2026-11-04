import createClient from "openapi-fetch";
import type { paths } from "./schema";
import type { ApiError } from "./types";

/**
 * The generated, typed API client (openapi-fetch over src/api/schema.d.ts, which `npm run gen:api` generates from
 * openapi.json). Paths are relative: the Vite dev/preview server proxies /api to Common Core.
 */
export const api = createClient<paths>({
  // Same origin: the page's own origin (absolute, so a Request can be built outside a browser too).
  baseUrl: typeof window === "undefined" ? "" : window.location.origin,
  // Resolved on every call, so tests can replace the global fetch after this module is loaded.
  fetch: (request) => globalThis.fetch(request),
});

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);
/** Calls whose 401 is an answer ("not signed in", "wrong password"), not an expired session. */
const SESSION_PROBES = [
  "/api/v1/auth/me",
  "/api/v1/auth/login",
  "/api/v1/auth/logout",
];

/** The CSRF token the backend sets in the XSRF-TOKEN cookie (readable by design; the session cookie is not). */
export function csrfToken(): string | undefined {
  if (typeof document === "undefined") {
    return undefined;
  }
  const cookie = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith("XSRF-TOKEN="));
  return cookie
    ? decodeURIComponent(cookie.substring("XSRF-TOKEN=".length))
    : undefined;
}

type SessionListener = () => void;
const sessionListeners = new Set<SessionListener>();

/** Called when an API call answers 401: the session has expired or was ended elsewhere. */
export function onSessionExpired(listener: SessionListener): () => void {
  sessionListeners.add(listener);
  return () => sessionListeners.delete(listener);
}

api.use({
  onRequest({ request }) {
    const token = csrfToken();
    if (!SAFE_METHODS.has(request.method) && token) {
      request.headers.set("X-XSRF-TOKEN", token);
    }
    return request;
  },
  onResponse({ request, response }) {
    const path = new URL(request.url).pathname;
    if (response.status === 401 && !SESSION_PROBES.includes(path)) {
      sessionListeners.forEach((listener) => listener());
    }
    return response;
  },
});

/** Fetches the CSRF cookie before the first write when the page has none yet. */
export async function ensureCsrfToken(): Promise<void> {
  if (!csrfToken()) {
    await globalThis.fetch("/api/v1/auth/csrf", { credentials: "same-origin" });
  }
}

/** A JSON write for screens that call fetch directly: it fetches the CSRF cookie if needed and sends the token. */
export async function sendJson(url: string, method: "POST" | "PUT", body?: unknown): Promise<Response> {
  await ensureCsrfToken();
  return globalThis.fetch(url, {
    method,
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfToken() ?? "" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

/** A failed API call, carrying the backend's error body when it sent one. */
export class ApiFailure extends Error {
  readonly status: number;
  readonly body: ApiError | null;

  constructor(status: number, body: ApiError | null, fallback: string) {
    super(body?.message ?? fallback);
    this.name = "ApiFailure";
    this.status = status;
    this.body = body;
  }
}

interface FetchResult<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

function errorBody(error: unknown): ApiError | null {
  if (error && typeof error === "object" && "message" in error) {
    return error as ApiError;
  }
  return null;
}

/** Unwraps an openapi-fetch result: the data, or an {@link ApiFailure} with the server's message. */
export async function unwrap<T>(call: Promise<FetchResult<T>>): Promise<T> {
  let result: FetchResult<T>;
  try {
    result = await call;
  } catch (cause) {
    throw new ApiFailure(
      0,
      null,
      cause instanceof Error ? cause.message : "Network error",
    );
  }
  if (result.error !== undefined || !result.response.ok) {
    throw new ApiFailure(
      result.response.status,
      errorBody(result.error),
      `Request failed (${result.response.status})`,
    );
  }
  return result.data as T;
}

/** As {@link unwrap} for calls that return no body (204). */
export async function unwrapEmpty(
  call: Promise<FetchResult<unknown>>,
): Promise<void> {
  await unwrap(call);
}
