import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  api,
  ApiFailure,
  ensureCsrfToken,
  onSessionExpired,
  unwrap,
} from "../../api/client";
import type { CurrentUser, Permission } from "../../api/types";

export const ME_KEY = ["auth", "me"];

/** The signed-in user, or null; and why the last session ended, when it expired. */
export interface Session {
  user: CurrentUser | null;
  expired: boolean;
  /** True when the user holds the permission (always false while a password change is required). */
  can: (permission: Permission) => boolean;
  /** True when the user holds at least one of the permissions; an empty list is always allowed. */
  canAny: (permissions: readonly Permission[] | undefined) => boolean;
  signedIn: (user: CurrentUser) => void;
  signOut: () => Promise<void>;
}

const SessionContext = createContext<Session | null>(null);

/**
 * Loads "who am I" once; 401 means nobody is signed in. Signing out, and any later 401 from the API, ends the local
 * session: the user is cleared, every cached query is dropped (nothing of the previous user remains) and
 * `onSignedOut` runs (the app replaces the history entry with the start page, so Back cannot return to a protected
 * screen). The user is kept in component state as well as the query cache, because clearing the cache detaches the
 * "who am I" query from its observer.
 */
export function SessionProvider({
  children,
  onSignedOut,
}: {
  children: ReactNode;
  onSignedOut?: () => void;
}) {
  const queryClient = useQueryClient();
  const [expired, setExpired] = useState(false);
  /** Set by sign-in and sign-out; undefined until then, while the loaded "who am I" applies. */
  const [current, setCurrent] = useState<CurrentUser | null | undefined>(undefined);
  const me = useQuery({
    queryKey: ME_KEY,
    queryFn: async () => {
      try {
        return await unwrap(api.GET("/api/v1/auth/me"));
      } catch (error) {
        if (error instanceof ApiFailure && error.status === 401) {
          return null;
        }
        throw error;
      }
    },
    staleTime: Infinity,
    retry: false,
  });
  const user = current !== undefined ? current : (me.data ?? null);
  const signedInRef = useRef(false);
  signedInRef.current = user !== null;

  const endLocalSession = useCallback(
    (sessionExpired: boolean) => {
      setExpired(sessionExpired);
      setCurrent(null);
      queryClient.clear();
      queryClient.setQueryData(ME_KEY, null);
      onSignedOut?.();
    },
    [queryClient, onSignedOut],
  );

  useEffect(
    () =>
      onSessionExpired(() => {
        if (signedInRef.current) {
          endLocalSession(true);
        }
      }),
    [endLocalSession],
  );

  const signedIn = useCallback(
    (signedInUser: CurrentUser) => {
      setExpired(false);
      queryClient.removeQueries({
        predicate: (query) => query.queryKey[0] !== ME_KEY[0],
      });
      queryClient.setQueryData(ME_KEY, signedInUser);
      setCurrent(signedInUser);
    },
    [queryClient],
  );

  const signOut = useCallback(async () => {
    try {
      await ensureCsrfToken();
      await unwrap(api.POST("/api/v1/auth/logout"));
    } catch {
      // Signing out never fails for the user: whatever the server answered, the local session ends here.
    } finally {
      endLocalSession(false);
    }
  }, [endLocalSession]);

  const value = useMemo<Session>(() => {
    const permissions = new Set<string>(user?.permissions ?? []);
    const can = (permission: Permission) => permissions.has(permission);
    return {
      user,
      expired,
      can,
      canAny: (list) => !list || list.length === 0 || list.some(can),
      signedIn,
      signOut,
    };
  }, [user, expired, signedIn, signOut]);

  if (me.isPending) {
    return null;
  }
  return (
    <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
  );
}

export function useSession(): Session {
  const session = useContext(SessionContext);
  if (!session) {
    throw new Error("useSession needs a SessionProvider");
  }
  return session;
}
