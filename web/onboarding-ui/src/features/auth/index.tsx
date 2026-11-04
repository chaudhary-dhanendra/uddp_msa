import { useState, type FormEvent, type ReactNode } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, ensureCsrfToken, unwrap } from "../../api/client";
import type { CurrentUser } from "../../api/types";
import { useI18n } from "../../i18n/i18n";
import { Button, Field, TextInput } from "../../shared/components";
import { useSession } from "./session";
import styles from "./auth.module.css";

export { SessionProvider, useSession, ME_KEY } from "./session";

/** Until someone is signed in only the sign-in screen renders; a required password change comes next. */
export function AuthGate({ children }: { children: ReactNode }) {
  const session = useSession();
  if (!session.user) {
    return <LoginScreen expired={session.expired} />;
  }
  if (session.user.mustChangePassword) {
    return <ChangePasswordScreen />;
  }
  return <>{children}</>;
}

function AuthCard({ title, children }: { title: string; children: ReactNode }) {
  const { t } = useI18n();
  return (
    <div className={styles.screen}>
      <main className={styles.card}>
        <div className={styles.brand}>
          <span className={styles.brandMark} aria-hidden="true">
            U
          </span>
          <strong>{t("app.name")}</strong>
        </div>
        <h1>{title}</h1>
        {children}
      </main>
    </div>
  );
}

export function LoginScreen({ expired }: { expired: boolean }) {
  const { t } = useI18n();
  const session = useSession();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const providers = useQuery({
    queryKey: ["auth", "providers"],
    queryFn: () => unwrap(api.GET("/api/v1/auth/providers")),
  });
  const sso = new URLSearchParams(window.location.search).get("sso");
  const login = useMutation({
    mutationFn: async () => {
      await ensureCsrfToken();
      return unwrap(
        api.POST("/api/v1/auth/login", {
          body: { username: username.trim(), password },
        }),
      );
    },
    onSuccess: (user) => {
      setPassword("");
      session.signedIn(user);
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    login.mutate();
  };
  return (
    <AuthCard title={t("auth.signInTitle")}>
      <div data-testid="login-screen">
        {expired && (
          <p
            className={styles.notice}
            role="alert"
            data-testid="session-expired"
          >
            {t("auth.sessionExpired")}
          </p>
        )}
        {sso === "denied" && (
          <p className={styles.error} role="alert">
            {t("auth.ssoDenied")}
          </p>
        )}
        {sso === "failed" && (
          <p className={styles.error} role="alert">
            {t("auth.ssoFailed")}
          </p>
        )}
        <form
          className={styles.form}
          onSubmit={submit}
          aria-label={t("auth.signInTitle")}
        >
          <Field id="login-username" label={t("auth.username")}>
            <TextInput
              id="login-username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              required
            />
          </Field>
          <Field id="login-password" label={t("auth.password")}>
            <TextInput
              id="login-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </Field>
          {login.isError && (
            <p className={styles.error} role="alert" data-testid="login-error">
              {login.error.message}
            </p>
          )}
          <Button
            type="submit"
            disabled={login.isPending}
            data-testid="sign-in"
          >
            {t("auth.signIn")}
          </Button>
        </form>
        {providers.data?.oidc && providers.data.oidcLoginUrl && (
          <p className={styles.alternative}>
            <a href={providers.data.oidcLoginUrl} data-testid="sso-sign-in">
              {t("auth.ssoSignIn", {
                provider: providers.data.oidcDisplayName ?? "",
              })}
            </a>
          </p>
        )}
      </div>
    </AuthCard>
  );
}

export function ChangePasswordScreen() {
  const { t } = useI18n();
  const session = useSession();
  return (
    <AuthCard title={t("auth.changeTitle")}>
      <p className="muted">{t("auth.changeRequired")}</p>
      <PasswordChangeForm
        onChanged={(user) => session.signedIn(user)}
        onCancel={() => void session.signOut()}
        cancelLabel={t("auth.signOut")}
      />
    </AuthCard>
  );
}

/** Change your own password: the current one, then the new one twice. */
export function PasswordChangeForm({
  onChanged,
  onCancel,
  cancelLabel,
}: {
  onChanged: (user: CurrentUser) => void;
  onCancel?: () => void;
  cancelLabel?: string;
}) {
  const { t } = useI18n();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [repeat, setRepeat] = useState("");
  const change = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/auth/change-password", {
          body: { currentPassword: current, newPassword: next },
        }),
      ),
    onSuccess: (user) => {
      setCurrent("");
      setNext("");
      setRepeat("");
      onChanged(user);
    },
  });
  const mismatch = repeat !== "" && next !== repeat;
  return (
    <form
      className={styles.form}
      onSubmit={(event) => {
        event.preventDefault();
        if (!mismatch) {
          change.mutate();
        }
      }}
      aria-label={t("auth.changeTitle")}
      data-testid="change-password-form"
    >
      <Field id="current-password" label={t("auth.currentPassword")}>
        <TextInput
          id="current-password"
          type="password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
          autoComplete="current-password"
          required
        />
      </Field>
      <Field
        id="new-password"
        label={t("auth.newPassword")}
        hint={t("auth.policy")}
      >
        <TextInput
          id="new-password"
          type="password"
          value={next}
          onChange={(e) => setNext(e.target.value)}
          autoComplete="new-password"
          aria-describedby="new-password-hint"
          required
        />
      </Field>
      <Field id="repeat-password" label={t("auth.repeatPassword")}>
        <TextInput
          id="repeat-password"
          type="password"
          value={repeat}
          onChange={(e) => setRepeat(e.target.value)}
          autoComplete="new-password"
          required
        />
      </Field>
      {mismatch && (
        <p className={styles.error} role="alert">
          {t("auth.mismatch")}
        </p>
      )}
      {change.isError && (
        <p className={styles.error} role="alert" data-testid="change-error">
          {change.error.message}
        </p>
      )}
      <div className="row">
        <Button
          type="submit"
          disabled={change.isPending || mismatch}
          data-testid="change-password"
        >
          {t("auth.changeButton")}
        </Button>
        {onCancel && (
          <Button variant="secondary" onClick={onCancel}>
            {cancelLabel}
          </Button>
        )}
      </div>
    </form>
  );
}

/** Header: who is signed in, their roles and states, and sign-out. */
export function UserMenu() {
  const { t } = useI18n();
  const session = useSession();
  if (!session.user) {
    return null;
  }
  const user = session.user;
  return (
    <div className={styles.userMenu} data-testid="user-menu">
      <span>
        <strong data-testid="current-user">
          {user.displayName || user.username}
        </strong>
        <span className={styles.userDetail}>
          {user.roles.join(", ")} ·{" "}
          {t("auth.departments", { departments: user.departmentScope.join(", ") })}
        </span>
      </span>
      <Button
        variant="secondary"
        onClick={() => void session.signOut()}
        data-testid="sign-out"
      >
        {t("auth.signOut")}
      </Button>
    </div>
  );
}
