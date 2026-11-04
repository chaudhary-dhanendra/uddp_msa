import { useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ensureCsrfToken, unwrap } from "../../api/client";
import { Link } from "react-router-dom";
import { ME_KEY, useSession } from "../auth/session";
import type { SetupStatus } from "../../api/types";
import { useI18n } from "../../i18n/i18n";
import {
  Button,
  DescriptionList,
  Field,
  PageHeader,
  Panel,
  QueryState,
  TextInput,
} from "../../shared/components";
import { ConnectionForm } from "./ConnectionForm";
import styles from "./setup.module.css";

export const SETUP_STATUS_KEY = ["setup-status"];

export function useSetupStatus() {
  return useQuery({
    queryKey: SETUP_STATUS_KEY,
    queryFn: () => unwrap(api.GET("/api/setup/status")),
    retry: 2,
  });
}

/**
 * Setup mode: until the platform database is configured, the UI shows only the Setup screen. Once settings are
 * applied the backend switches to normal mode without a restart, and the full application renders.
 */
export function SetupGate({ children }: { children: ReactNode }) {
  const status = useSetupStatus();
  return (
    <QueryState query={status}>
      {(data) =>
        data.mode === "SETUP" ? (
          <SetupScreen status={data} />
        ) : data.adminRequired ? (
          <CreateAdministratorScreen />
        ) : (
          <>{children}</>
        )
      }
    </QueryState>
  );
}

export function SetupScreen({ status }: { status: SetupStatus }) {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  return (
    <div className={styles.screen}>
      <main className={styles.card} data-testid="setup-screen">
        <div className={styles.brand}>
          <span className={styles.brandMark} aria-hidden="true">
            U
          </span>
          <strong>{t("app.name")}</strong>
        </div>
        <h1>{t("setup.title")}</h1>
        <p className="muted">{t("setup.intro")}</p>
        {status.problem && (
          <p className={styles.notice} role="alert">
            {t("setup.problem", { problem: status.problem })}
          </p>
        )}
        <ConnectionForm
          initial={status.settings}
          keepPasswordAllowed={false}
          onApplied={() =>
            void queryClient.invalidateQueries({ queryKey: SETUP_STATUS_KEY })
          }
        />
      </main>
    </div>
  );
}

/**
 * The database is ready but has no user yet: whoever completes setup chooses the first platform administrator's
 * username and password (it meets the password policy) and is signed in.
 */
export function CreateAdministratorScreen() {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [repeat, setRepeat] = useState("");
  const create = useMutation({
    mutationFn: async () => {
      await ensureCsrfToken();
      return unwrap(
        api.POST("/api/setup/admin", {
          body: {
            username: username.trim(),
            displayName: displayName.trim() || null,
            password,
          },
        }),
      );
    },
    onSuccess: async (user) => {
      setPassword("");
      setRepeat("");
      queryClient.setQueryData(ME_KEY, user);
      await queryClient.invalidateQueries({ queryKey: SETUP_STATUS_KEY });
    },
  });
  const mismatch = repeat !== "" && password !== repeat;
  return (
    <div className={styles.screen}>
      <main className={styles.card} data-testid="create-admin-screen">
        <div className={styles.brand}>
          <span className={styles.brandMark} aria-hidden="true">
            U
          </span>
          <strong>{t("app.name")}</strong>
        </div>
        <h1>{t("setup.adminTitle")}</h1>
        <p className="muted">{t("setup.adminIntro")}</p>
        <form
          className={styles.form}
          aria-label={t("setup.adminTitle")}
          onSubmit={(event) => {
            event.preventDefault();
            if (!mismatch) {
              create.mutate();
            }
          }}
        >
          <Field id="admin-username" label={t("auth.username")}>
            <TextInput
              id="admin-username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              required
            />
          </Field>
          <Field id="admin-display-name" label={t("setup.adminDisplayName")}>
            <TextInput
              id="admin-display-name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              autoComplete="name"
            />
          </Field>
          <Field
            id="admin-password"
            label={t("auth.password")}
            hint={t("auth.policy")}
          >
            <TextInput
              id="admin-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
              aria-describedby="admin-password-hint"
              required
            />
          </Field>
          <Field id="admin-repeat" label={t("auth.repeatPassword")}>
            <TextInput
              id="admin-repeat"
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
          {create.isError && (
            <p
              className={styles.error}
              role="alert"
              data-testid="create-admin-error"
            >
              {create.error.message}
            </p>
          )}
          <Button
            type="submit"
            disabled={create.isPending || mismatch}
            data-testid="create-admin"
          >
            {t("setup.adminCreate")}
          </Button>
        </form>
      </main>
    </div>
  );
}

/** View (masked) and change the connection; reset a *_demo database. */
export function SettingsPage() {
  const { t } = useI18n();
  const session = useSession();
  const status = useSetupStatus();
  return (
    <>
      <PageHeader
        title={t("settings.title")}
        description={t("settings.description")}
        actions={
          session.can("USER_MANAGE") ? (
            <>
              <Link className="button-link" to="/settings/users">
                {t("nav.users")}
              </Link>
              <Link className="button-link" to="/settings/roles">
                {t("nav.roles")}
              </Link>
            </>
          ) : undefined
        }
      />
      <QueryState query={status}>
        {(data) => (
          <div className="stack">
            <Panel title={t("settings.connection")}>
              <DescriptionList
                items={[
                  {
                    label: t("settings.source"),
                    value: t(`settings.sourceValue.${data.source}`),
                  },
                  { label: t("setup.host"), value: data.settings?.host },
                  { label: t("setup.port"), value: data.settings?.port },
                  {
                    label: t("setup.database"),
                    value: data.settings?.database,
                  },
                  { label: t("setup.user"), value: data.settings?.user },
                  {
                    label: t("settings.password"),
                    value: t("settings.passwordMasked"),
                  },
                  {
                    label: t("settings.schemaVersion"),
                    value: data.schemaVersion,
                  },
                  {
                    label: t("settings.settingsFile"),
                    value: <code>{data.settingsFile}</code>,
                  },
                ]}
              />
            </Panel>
            <Panel title={t("settings.change")}>
              {data.source === "ENVIRONMENT" && (
                <p className={styles.notice}>
                  {t("settings.environmentLocked")}
                </p>
              )}
              {data.onboardingInProgress && (
                <p className={styles.notice}>{t("settings.runInProgress")}</p>
              )}
              <ConnectionForm
                initial={data.settings}
                keepPasswordAllowed
                disabled={
                  data.source === "ENVIRONMENT" || data.onboardingInProgress
                }
              />
            </Panel>
            <ResetPanel status={data} />
          </div>
        )}
      </QueryState>
    </>
  );
}

function ResetPanel({ status }: { status: SetupStatus }) {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const [typed, setTyped] = useState("");
  const database = status.settings?.database ?? "";
  const reset = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/setup/reset-demo", {
          body: { confirmDatabaseName: typed },
        }),
      ),
    onSuccess: async () => {
      setTyped("");
      await queryClient.invalidateQueries();
    },
  });
  return (
    <Panel title={t("settings.reset")} id="reset">
      <p>{t("settings.resetIntro", { database })}</p>
      {!status.resetAllowed ? (
        <p className={styles.notice}>{t("settings.resetNotAllowed")}</p>
      ) : (
        <form
          className="row"
          onSubmit={(event) => {
            event.preventDefault();
            reset.mutate();
          }}
        >
          <Field id="reset-confirm" label={t("settings.resetConfirm")}>
            <TextInput
              id="reset-confirm"
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              placeholder={database}
              autoComplete="off"
            />
          </Field>
          <Button
            type="submit"
            disabled={
              typed !== database ||
              reset.isPending ||
              status.onboardingInProgress
            }
            data-testid="reset-demo"
          >
            {t("settings.resetButton")}
          </Button>
        </form>
      )}
      {reset.isError && (
        <p role="alert" className={styles.error}>
          {reset.error.message}
        </p>
      )}
      {reset.isSuccess && (
        <p role="status" className={styles.ok}>
          {t("settings.resetDone")}
        </p>
      )}
    </Panel>
  );
}
