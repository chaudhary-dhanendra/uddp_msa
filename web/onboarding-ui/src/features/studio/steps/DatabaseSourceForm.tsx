import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../../../api/client";
import type { StudioDraft } from "../../../api/types";
import { useI18n } from "../../../i18n/i18n";
import { Button, Field, Select, TextInput } from "../../../shared/components";
import { refreshDraft, studioKeys } from "../hooks";
import { ErrorNote } from "./StepFooter";
import styles from "../studio.module.css";

const NEW = "__new__";

/**
 * A "Database table" source: choose or add a named PostgreSQL connection (its password is stored encrypted on this
 * machine and never shown again), then a table or a single SELECT.
 */
export function DatabaseSourceForm({ draft }: { draft: StudioDraft }) {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const draftId = draft.draftId ?? "";
  const connections = useQuery({
    queryKey: ["studio", "connections"],
    queryFn: () => unwrap(api.GET("/api/studio/connections")),
  });
  const [selected, setSelected] = useState(draft.source?.connectionName ?? NEW);
  const [name, setName] = useState("");
  const [host, setHost] = useState("localhost");
  const [port, setPort] = useState("5432");
  const [database, setDatabase] = useState("");
  const [user, setUser] = useState("");
  const [password, setPassword] = useState("");
  const [table, setTable] = useState(draft.source?.table ?? "");
  const [query, setQuery] = useState(draft.source?.query ?? "");
  const [changeColumn, setChangeColumn] = useState(
    draft.source?.changeColumn ?? "",
  );

  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT("/api/studio/connections/{name}", {
          params: { path: { name } },
          body: {
            host,
            port: Number.parseInt(port, 10),
            database,
            user,
            password: password === "" ? null : password,
          },
        }),
      ),
    onSuccess: async (connection) => {
      setPassword("");
      await queryClient.invalidateQueries({
        queryKey: ["studio", "connections"],
      });
      setSelected(connection.name ?? NEW);
    },
  });

  const use = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/studio/drafts/{draftId}/database-source", {
          params: { path: { draftId } },
          body: {
            connectionName: selected,
            table: table === "" ? null : table,
            query: query === "" ? null : query,
            changeColumn: changeColumn === "" ? null : changeColumn,
          },
        }),
      ),
    onSuccess: async (saved) => {
      refreshDraft(queryClient, draftId, saved);
      await queryClient.invalidateQueries({
        queryKey: studioKeys.profile(draftId),
      });
      await queryClient.invalidateQueries({
        queryKey: studioKeys.suggestions(draftId),
      });
    },
  });

  const options = [
    ...(connections.data ?? []).map((c) => ({
      value: c.name ?? "",
      label: `${c.name} (${c.user}@${c.host}:${c.port}/${c.database})`,
    })),
    { value: NEW, label: t("studio.upload.newConnection") },
  ];

  return (
    <div className="stack">
      <Field id="db-connection" label={t("studio.upload.connection")}>
        <Select
          id="db-connection"
          value={selected}
          onChange={(e) => setSelected(e.target.value)}
          options={options}
        />
      </Field>
      {selected === NEW && (
        <form
          className="stack"
          onSubmit={(event) => {
            event.preventDefault();
            save.mutate();
          }}
        >
          <div className="grid-2">
            <Field
              id="src-name"
              label={t("studio.upload.connectionName")}
              hint={t("studio.upload.connectionNameHint")}
            >
              <TextInput
                id="src-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </Field>
            <Field id="src-host" label={t("setup.host")}>
              <TextInput
                id="src-host"
                value={host}
                onChange={(e) => setHost(e.target.value)}
              />
            </Field>
            <Field id="src-port" label={t("setup.port")}>
              <TextInput
                id="src-port"
                value={port}
                onChange={(e) => setPort(e.target.value)}
              />
            </Field>
            <Field id="src-db" label={t("setup.database")}>
              <TextInput
                id="src-db"
                value={database}
                onChange={(e) => setDatabase(e.target.value)}
              />
            </Field>
            <Field id="src-user" label={t("setup.user")}>
              <TextInput
                id="src-user"
                value={user}
                onChange={(e) => setUser(e.target.value)}
                autoComplete="off"
              />
            </Field>
            <Field id="src-password" label={t("setup.password")}>
              <TextInput
                id="src-password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="new-password"
              />
            </Field>
          </div>
          <div className="row">
            <Button
              type="submit"
              variant="secondary"
              disabled={!name || save.isPending}
            >
              {t("studio.upload.saveConnection")}
            </Button>
          </div>
          <ErrorNote error={save.error} />
        </form>
      )}
      {save.isSuccess && (
        <p className={styles.ok} role="status">
          {t("studio.upload.connectionSaved", { name: save.data.name ?? "" })}
        </p>
      )}
      <div className="grid-2">
        <Field
          id="src-table"
          label={t("studio.upload.table")}
          hint={t("studio.upload.tableHint")}
        >
          <TextInput
            id="src-table"
            value={table}
            onChange={(e) => setTable(e.target.value)}
          />
        </Field>
        <Field id="src-query" label={t("studio.upload.query")}>
          <TextInput
            id="src-query"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </Field>
        <Field
          id="src-change-column"
          label={t("studio.upload.changeColumn")}
          hint={t("studio.upload.changeColumnHint")}
        >
          <TextInput
            id="src-change-column"
            value={changeColumn}
            onChange={(e) => setChangeColumn(e.target.value)}
          />
        </Field>
      </div>
      <div className="row">
        <Button
          onClick={() => use.mutate()}
          disabled={selected === NEW || (!table && !query) || use.isPending}
          data-testid="use-database"
        >
          {t("studio.upload.useDatabase")}
        </Button>
      </div>
      <ErrorNote error={use.error} />
    </div>
  );
}
