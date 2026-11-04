import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ensureCsrfToken, unwrap } from "../../api/client";
import type { RoleMatrix, UserView, UserWithPassword } from "../../api/types";
import { useI18n } from "../../i18n/i18n";
import { optional, useListParams } from "../../shared/hooks";
import {
  Button,
  DataTable,
  DescriptionList,
  Field,
  FilterBar,
  PageHeader,
  Panel,
  QueryState,
  Select,
  StatusBadge,
  TextInput,
} from "../../shared/components";
import { useSession } from "../auth";

const USERS_KEY = ["users"];
const ROLES_KEY = ["roles"];
const ALL = "ALL";

function useRoles() {
  return useQuery({
    queryKey: ROLES_KEY,
    queryFn: () => unwrap(api.GET("/api/v1/roles")),
  });
}

function roleCodes(matrix: RoleMatrix | undefined): string[] {
  return (matrix?.roles ?? [])
    .map((role) => role.roleCode)
    .filter((code): code is string => code !== null);
}

/** Department codes typed as "DEPT-1, DEPT-2"; upper-cased, blanks dropped. */
function parseDepartments(text: string): string[] {
  return text
    .split(/[\s,]+/)
    .map((code) => code.trim().toUpperCase())
    .filter((code) => code !== "");
}

function scopeLabel(scope: readonly string[], allLabel: string): string {
  return scope.includes(ALL) ? allLabel : scope.join(", ");
}

/** The one-time password, shown once after creating a user or resetting a password. */
function OneTimePassword({ result }: { result: UserWithPassword }) {
  const { t } = useI18n();
  return (
    <div role="status" data-testid="one-time-password">
      <p>{t("users.oneTimeIntro", { username: result.user?.username ?? "" })}</p>
      <p>
        <code>{result.oneTimePassword}</code>
      </p>
      <p>{t("users.oneTimeHint")}</p>
    </div>
  );
}

/** A role checkbox list; the matrix gives the roles and their descriptions. */
function RoleChecklist({
  matrix,
  selected,
  onChange,
  idPrefix,
}: {
  matrix: RoleMatrix | undefined;
  selected: readonly string[];
  onChange: (roles: string[]) => void;
  idPrefix: string;
}) {
  return (
    <fieldset className="stack">
      {(matrix?.roles ?? []).map((role) => {
        const code = role.roleCode ?? "";
        const id = `${idPrefix}-${code}`;
        return (
          <label key={code} htmlFor={id}>
            <input
              id={id}
              type="checkbox"
              checked={selected.includes(code)}
              onChange={(event) =>
                onChange(
                  event.target.checked
                    ? [...selected, code]
                    : selected.filter((existing) => existing !== code),
                )
              }
            />{" "}
            <strong>{code}</strong> {role.description ?? ""}
          </label>
        );
      })}
    </fieldset>
  );
}

/** All departments, or the listed department codes. */
function ScopeEditor({
  all,
  departments,
  onAll,
  onDepartments,
  idPrefix,
}: {
  all: boolean;
  departments: string;
  onAll: (all: boolean) => void;
  onDepartments: (departments: string) => void;
  idPrefix: string;
}) {
  const { t } = useI18n();
  return (
    <div className="stack">
      <label htmlFor={`${idPrefix}-all`}>
        <input
          id={`${idPrefix}-all`}
          type="checkbox"
          checked={all}
          onChange={(event) => onAll(event.target.checked)}
        />{" "}
        {t("users.allDepartments")}
      </label>
      <Field
        id={`${idPrefix}-departments`}
        label={t("users.departmentCodes")}
        hint={t("users.departmentCodesHint")}
      >
        <TextInput
          id={`${idPrefix}-departments`}
          value={departments}
          disabled={all}
          onChange={(event) => onDepartments(event.target.value)}
        />
      </Field>
    </div>
  );
}

function CreateUserDialog({ onClose }: { onClose: () => void }) {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const roles = useRoles();
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [all, setAll] = useState(false);
  const [departments, setDepartments] = useState("");
  const create = useMutation({
    mutationFn: async () => {
      await ensureCsrfToken();
      return unwrap(
        api.POST("/api/v1/users", {
          body: {
            username,
            displayName: optional(displayName) ?? null,
            roles: selected,
            departmentScope: all ? [ALL] : parseDepartments(departments),
          },
        }),
      );
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: USERS_KEY }),
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };
  return (
    <div role="dialog" aria-modal="true" aria-labelledby="create-user-heading" data-testid="create-user-dialog">
      <Panel title={t("users.create")} id="create-user">
        {create.data ? (
          <div className="stack">
            <OneTimePassword result={create.data} />
            <Button onClick={onClose}>{t("users.done")}</Button>
          </div>
        ) : (
          <form className="stack" onSubmit={submit}>
            <Field id="new-username" label={t("users.username")}>
              <TextInput
                id="new-username"
                value={username}
                required
                onChange={(event) => setUsername(event.target.value)}
              />
            </Field>
            <Field id="new-display-name" label={t("users.displayName")}>
              <TextInput
                id="new-display-name"
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
              />
            </Field>
            <h3>{t("users.roles")}</h3>
            <RoleChecklist matrix={roles.data} selected={selected} onChange={setSelected} idPrefix="new-role" />
            <h3>{t("users.departmentScope")}</h3>
            <ScopeEditor
              all={all}
              departments={departments}
              onAll={setAll}
              onDepartments={setDepartments}
              idPrefix="new-scope"
            />
            {create.error && <p role="alert">{create.error.message}</p>}
            <div className="row">
              <Button type="submit" disabled={create.isPending} data-testid="create-user-submit">
                {t("users.createSubmit")}
              </Button>
              <Button variant="secondary" onClick={onClose}>
                {t("users.cancel")}
              </Button>
            </div>
          </form>
        )}
      </Panel>
    </div>
  );
}

/** Platform settings: every user with status, roles and department scope; search, filter and create. */
export function UsersPage() {
  const { t } = useI18n();
  const params = useListParams();
  const [creating, setCreating] = useState(false);
  const filters = { q: optional(params.get("q")), status: optional(params.get("status")) };
  const users = useQuery({
    queryKey: [...USERS_KEY, filters],
    queryFn: () => unwrap(api.GET("/api/v1/users", { params: { query: filters } })),
  });
  return (
    <>
      <PageHeader
        title={t("users.title")}
        description={t("users.description")}
        actions={
          <>
            <Link className="button-link" to="/settings/roles">
              {t("nav.roles")}
            </Link>
            <Button onClick={() => setCreating(true)} data-testid="create-user">
              {t("users.create")}
            </Button>
          </>
        }
      />
      {creating && <CreateUserDialog onClose={() => setCreating(false)} />}
      <FilterBar label={t("users.filters")}>
        <Field id="users-q" label={t("users.search")}>
          <TextInput
            id="users-q"
            defaultValue={params.get("q")}
            onBlur={(event) => params.set({ q: event.target.value })}
          />
        </Field>
        <Field id="users-status" label={t("users.status")}>
          <Select
            id="users-status"
            value={params.get("status")}
            onChange={(event) => params.set({ status: event.target.value })}
            options={[
              { value: "", label: t("users.anyStatus") },
              { value: "ENABLED", label: t("users.enabled") },
              { value: "DISABLED", label: t("users.disabled") },
            ]}
          />
        </Field>
      </FilterBar>
      <QueryState query={users} label={t("users.title")}>
        {(rows) => (
          <DataTable<UserView>
            caption={t("users.title")}
            testId="users-table"
            rows={rows}
            rowKey={(user) => user.userId ?? user.username ?? ""}
            emptyText={t("users.empty")}
            columns={[
              {
                key: "username",
                header: t("users.username"),
                render: (user) => <Link to={`/settings/users/${user.userId ?? ""}`}>{user.username}</Link>,
              },
              { key: "name", header: t("users.displayName"), render: (user) => user.displayName ?? "—" },
              {
                key: "status",
                header: t("users.status"),
                render: (user) => <StatusBadge status={user.enabled ? (user.locked ? "LOCKED" : "ENABLED") : "DISABLED"} />,
              },
              { key: "roles", header: t("users.roles"), render: (user) => user.roles.join(", ") },
              {
                key: "scope",
                header: t("users.departmentScope"),
                render: (user) => scopeLabel(user.departmentScope, t("users.allDepartments")),
              },
            ]}
          />
        )}
      </QueryState>
    </>
  );
}

function UserEditors({ user }: { user: UserView }) {
  const { t } = useI18n();
  const session = useSession();
  const queryClient = useQueryClient();
  const roles = useRoles();
  const id = user.userId ?? "";
  const [selected, setSelected] = useState<string[]>(user.roles);
  const [all, setAll] = useState(user.departmentScope.includes(ALL));
  const [departments, setDepartments] = useState(
    user.departmentScope.filter((code) => code !== ALL).join(", "),
  );
  const [reset, setReset] = useState<UserWithPassword | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: USERS_KEY });
  const path = { params: { path: { id } } };
  const saveRoles = useMutation({
    mutationFn: async () => {
      await ensureCsrfToken();
      return unwrap(api.PUT("/api/v1/users/{id}/roles", { ...path, body: { roles: selected } }));
    },
    onSuccess: refresh,
  });
  const saveScope = useMutation({
    mutationFn: async () => {
      await ensureCsrfToken();
      return unwrap(
        api.PUT("/api/v1/users/{id}/department-scope", {
          ...path,
          body: { departmentScope: all ? [ALL] : parseDepartments(departments) },
        }),
      );
    },
    onSuccess: refresh,
  });
  const toggle = useMutation({
    mutationFn: async () => {
      await ensureCsrfToken();
      return unwrap(
        user.enabled
          ? api.POST("/api/v1/users/{id}/disable", path)
          : api.POST("/api/v1/users/{id}/enable", path),
      );
    },
    onSuccess: refresh,
  });
  const resetPassword = useMutation({
    mutationFn: async () => {
      await ensureCsrfToken();
      return unwrap(api.POST("/api/v1/users/{id}/reset-password", path));
    },
    onSuccess: (result) => {
      setReset(result);
      void refresh();
    },
  });
  const self = session.user?.username === user.username;
  const error = saveRoles.error ?? saveScope.error ?? toggle.error ?? resetPassword.error;
  return (
    <div className="stack">
      <Panel
        title={t("users.account")}
        actions={
          <div className="row">
            {user.authSource === "LOCAL" && (
              <Button
                variant="secondary"
                onClick={() => resetPassword.mutate()}
                disabled={resetPassword.isPending}
                data-testid="reset-password"
              >
                {t("users.resetPassword")}
              </Button>
            )}
            <Button
              variant="secondary"
              onClick={() => toggle.mutate()}
              disabled={toggle.isPending || (self && user.enabled)}
              data-testid="toggle-enabled"
            >
              {user.enabled ? t("users.disable") : t("users.enable")}
            </Button>
          </div>
        }
      >
        <DescriptionList
          items={[
            { label: t("users.username"), value: user.username },
            { label: t("users.displayName"), value: user.displayName },
            {
              label: t("users.status"),
              value: <StatusBadge status={user.enabled ? "ENABLED" : "DISABLED"} />,
            },
            { label: t("users.authSource"), value: user.authSource },
            {
              label: t("users.lastLogin"),
              value: user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString() : "—",
            },
            {
              label: t("users.mustChange"),
              value: user.mustChangePassword ? t("users.yes") : t("users.no"),
            },
          ]}
        />
        {reset && <OneTimePassword result={reset} />}
        {error && <p role="alert">{error.message}</p>}
      </Panel>
      <Panel title={t("users.roles")}>
        <RoleChecklist matrix={roles.data} selected={selected} onChange={setSelected} idPrefix="role" />
        <Button onClick={() => saveRoles.mutate()} disabled={saveRoles.isPending} data-testid="save-roles">
          {t("users.saveRoles")}
        </Button>
      </Panel>
      <Panel title={t("users.departmentScope")}>
        <ScopeEditor
          all={all}
          departments={departments}
          onAll={setAll}
          onDepartments={setDepartments}
          idPrefix="scope"
        />
        <Button onClick={() => saveScope.mutate()} disabled={saveScope.isPending} data-testid="save-scope">
          {t("users.saveDepartmentScope")}
        </Button>
      </Panel>
    </div>
  );
}

/** One user: account details, Reset password, Enable/Disable, and the role and department-scope editors. */
export function UserDetailPage() {
  const { t } = useI18n();
  const { id = "" } = useParams();
  const user = useQuery({
    queryKey: [...USERS_KEY, "detail", id],
    queryFn: () => unwrap(api.GET("/api/v1/users/{id}", { params: { path: { id } } })),
  });
  return (
    <>
      <PageHeader
        title={user.data?.username ?? t("users.title")}
        actions={
          <Link className="button-link" to="/settings/users">
            {t("users.back")}
          </Link>
        }
      />
      <QueryState query={user} label={t("users.title")}>
        {(data) => <UserEditors key={`${data.userId ?? ""}`} user={data} />}
      </QueryState>
    </>
  );
}

/** The permission matrix: which role grants which permission (read-only). */
export function RolesPage() {
  const { t } = useI18n();
  const roles = useRoles();
  return (
    <>
      <PageHeader title={t("roles.title")} description={t("roles.description")} />
      <QueryState query={roles} label={t("roles.title")}>
        {(matrix) => {
          const codes = roleCodes(matrix);
          return (
            <DataTable
              caption={t("roles.title")}
              testId="roles-matrix"
              rows={matrix.permissions}
              rowKey={(permission) => permission.permissionCode ?? ""}
              columns={[
                {
                  key: "permission",
                  header: t("roles.permission"),
                  render: (permission) => (
                    <span title={permission.description ?? ""}>{permission.permissionCode}</span>
                  ),
                },
                ...codes.map((code) => ({
                  key: code,
                  header: code,
                  render: (permission: (typeof matrix.permissions)[number]) =>
                    matrix.roles
                      .find((role) => role.roleCode === code)
                      ?.permissions.includes(permission.permissionCode ?? "") ? (
                      <span aria-label={t("roles.granted")}>✓</span>
                    ) : (
                      <span aria-label={t("roles.notGranted")}>·</span>
                    ),
                })),
              ]}
            />
          );
        }}
      </QueryState>
    </>
  );
}
