import { useState, type FormEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type {
  ConnectionRequest,
  ConnectionTest,
  MaskedSettings,
  SetupStatus,
} from "../../api/types";
import { useI18n } from "../../i18n/i18n";
import { Button, Field, TextInput } from "../../shared/components";
import styles from "./setup.module.css";

/**
 * The platform database connection form used by the Setup screen and the Settings screen. The password is sent only
 * on Test and Apply and is never read back; on the Settings screen a blank password keeps the saved one.
 */
export function ConnectionForm({
  initial,
  keepPasswordAllowed,
  disabled,
  onApplied,
}: {
  initial: MaskedSettings | null | undefined;
  keepPasswordAllowed: boolean;
  disabled?: boolean;
  onApplied?: (status: SetupStatus) => void;
}) {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const [host, setHost] = useState(initial?.host ?? "localhost");
  const [port, setPort] = useState(String(initial?.port ?? 5432));
  const [database, setDatabase] = useState(initial?.database ?? "uddp_demo");
  const [user, setUser] = useState(initial?.user ?? "postgres");
  const [password, setPassword] = useState("");
  const [create, setCreate] = useState(true);
  const [test, setTest] = useState<ConnectionTest | null>(null);

  const request = (): ConnectionRequest => ({
    host: host.trim(),
    port: Number.parseInt(port, 10),
    database: database.trim(),
    user: user.trim(),
    password: password === "" ? null : password,
    createDatabaseIfMissing: create,
  });

  const testConnection = useMutation({
    mutationFn: () =>
      unwrap(api.POST("/api/setup/test-connection", { body: request() })),
    onSuccess: (result) => setTest(result),
  });

  const apply = useMutation({
    mutationFn: () => unwrap(api.POST("/api/setup/apply", { body: request() })),
    onSuccess: async (status) => {
      setPassword("");
      await queryClient.invalidateQueries();
      onApplied?.(status);
    },
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    apply.mutate();
  };

  return (
    <form
      className={styles.form}
      onSubmit={submit}
      aria-label={t("setup.title")}
    >
      <div className={styles.grid}>
        <Field id="db-host" label={t("setup.host")}>
          <TextInput
            id="db-host"
            value={host}
            onChange={(e) => setHost(e.target.value)}
            disabled={disabled}
            required
            autoComplete="off"
          />
        </Field>
        <Field id="db-port" label={t("setup.port")}>
          <TextInput
            id="db-port"
            value={port}
            inputMode="numeric"
            onChange={(e) => setPort(e.target.value)}
            disabled={disabled}
            required
          />
        </Field>
        <Field id="db-name" label={t("setup.database")}>
          <TextInput
            id="db-name"
            value={database}
            onChange={(e) => setDatabase(e.target.value)}
            disabled={disabled}
            required
            autoComplete="off"
          />
        </Field>
        <Field id="db-user" label={t("setup.user")}>
          <TextInput
            id="db-user"
            value={user}
            onChange={(e) => setUser(e.target.value)}
            disabled={disabled}
            required
            autoComplete="off"
          />
        </Field>
        <Field
          id="db-password"
          label={t("setup.password")}
          hint={
            keepPasswordAllowed
              ? t("setup.passwordKeep")
              : t("setup.storedHint")
          }
        >
          <TextInput
            id="db-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={disabled}
            required={!keepPasswordAllowed}
            autoComplete="new-password"
          />
        </Field>
      </div>
      <label className={styles.checkbox}>
        <input
          type="checkbox"
          checked={create}
          onChange={(e) => setCreate(e.target.checked)}
          disabled={disabled}
        />
        {t("setup.createDatabase")}
      </label>
      <div className="row">
        <Button
          variant="secondary"
          onClick={() => testConnection.mutate()}
          disabled={disabled || testConnection.isPending}
          data-testid="test-connection"
        >
          {testConnection.isPending ? t("setup.testing") : t("setup.test")}
        </Button>
        <Button
          type="submit"
          disabled={disabled || apply.isPending}
          data-testid="apply-settings"
        >
          {apply.isPending ? t("setup.applying") : t("setup.apply")}
        </Button>
      </div>
      {test && (
        <p
          role="status"
          className={test.ok ? styles.ok : styles.error}
          data-testid="connection-result"
        >
          <strong>{test.ok ? t("setup.connected") : t("setup.failed")}</strong>{" "}
          {test.message}
          {test.serverVersion ? ` (PostgreSQL ${test.serverVersion})` : ""}
        </p>
      )}
      {testConnection.isError && (
        <p role="alert" className={styles.error}>
          {testConnection.error.message}
        </p>
      )}
      {apply.isError && (
        <p role="alert" className={styles.error} data-testid="apply-error">
          {apply.error.message}
        </p>
      )}
      {apply.isSuccess && (
        <p role="status" className={styles.ok}>
          {t("setup.applied", { version: apply.data.schemaVersion ?? "—" })}
        </p>
      )}
    </form>
  );
}
