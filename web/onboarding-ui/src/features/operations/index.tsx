import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import { PageHeader, Panel, QueryState } from "../../shared/components";
import { Button, Field, FilterBar, Select, TextInput } from "../../shared/components/forms";
import { useSession } from "../auth";

type Source = { state: string; source: string };
type Gate = "MAPPING_COVERAGE_PCT" | "ERROR_RATE_PCT" | "UNMAPPED_CODE_COUNT" | "CHANGE_VOLUME_PCT" | "MATCH_RATE_CHANGE_PCT";

const GATES: { type: Gate; label: string; hint: string }[] = [
  { type: "MAPPING_COVERAGE_PCT", label: "Mapping coverage (min %)", hint: "Records that became canonical" },
  { type: "ERROR_RATE_PCT", label: "Error rate (max %)", hint: "Records with a data-quality finding" },
  { type: "UNMAPPED_CODE_COUNT", label: "Unmapped codes (max)", hint: "Crosswalk values the pack lacks" },
  { type: "CHANGE_VOLUME_PCT", label: "Change volume (max %)", hint: "New, changed or removed vs the previous delivery" },
  { type: "MATCH_RATE_CHANGE_PCT", label: "Match-rate change (max %)", hint: "Only when matching reports it" },
];

/** Connector settings by type; secrets are write-only and never shown again. */
const EXCEL = { fields: ["path", "sheet", "headerRow"], secret: "" };
const CONNECTORS: Record<string, { fields: string[]; secret: string }> = {
  excel: EXCEL,
  jdbc: { fields: ["dbType", "host", "port", "database", "user", "table", "query"], secret: "password" },
  sftp: { fields: ["host", "port", "user", "directory", "pattern", "archiveDirectory", "hostKeySha256"], secret: "password" },
  rest: { fields: ["url", "recordsPath", "pagination", "pageSize", "authHeaderName"], secret: "authValue" },
};

function SchedulePanel({ state, source }: Source) {
  const client = useQueryClient();
  const path = { state, source };
  const schedule = useQuery({
    queryKey: ["schedule", state, source],
    queryFn: () => unwrap(api.GET("/api/operations/sources/{state}/{source}/schedule", { params: { path } })),
  });
  const [cron, setCron] = useState<string | undefined>();
  const [timezone, setTimezone] = useState<string | undefined>();
  const [enabled, setEnabled] = useState<boolean | undefined>();
  const [catchUp, setCatchUp] = useState<string | undefined>();
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT("/api/operations/sources/{state}/{source}/schedule", {
          params: { path },
          body: {
            cron: cron ?? schedule.data?.cron ?? "",
            timezone: timezone ?? schedule.data?.timezone ?? "UTC",
            enabled: enabled ?? schedule.data?.enabled ?? true,
            catchUp: (catchUp ?? schedule.data?.catchUp ?? "RUN_ONCE") as "RUN_ONCE" | "SKIP",
          },
        }),
      ),
    onSuccess: () => client.invalidateQueries({ queryKey: ["schedule", state, source] }),
  });
  return (
    <Panel title="Schedule">
      <QueryState query={schedule}>
        {(s) => (
          <FilterBar label="Schedule" onSubmit={() => save.mutate()}>
            <Field id="sched-cron" label="Cron expression" hint="For example 0 2 * * * for 02:00 every day">
              <TextInput id="sched-cron" value={cron ?? s.cron ?? ""} onChange={(e) => setCron(e.target.value)} />
            </Field>
            <Field id="sched-tz" label="Time zone">
              <TextInput id="sched-tz" value={timezone ?? s.timezone ?? "UTC"} onChange={(e) => setTimezone(e.target.value)} />
            </Field>
            <Field id="sched-catchup" label="After downtime">
              <Select
                id="sched-catchup"
                value={catchUp ?? s.catchUp ?? "RUN_ONCE"}
                options={[
                  { value: "RUN_ONCE", label: "Run once to catch up" },
                  { value: "SKIP", label: "Skip missed runs" },
                ]}
                onChange={(e) => setCatchUp(e.target.value)}
              />
            </Field>
            <label>
              <input
                type="checkbox"
                checked={enabled ?? s.enabled}
                onChange={(e) => setEnabled(e.target.checked)}
              />{" "}
              Enabled
            </label>
            <Button type="submit">Save schedule</Button>
          </FilterBar>
        )}
      </QueryState>
      {save.isError && <p role="alert">The schedule was not saved: check the cron expression and time zone.</p>}
    </Panel>
  );
}

function DeliveryPolicyPanel({ state, source }: Source) {
  const path = { state, source };
  const client = useQueryClient();
  const policy = useQuery({
    queryKey: ["delivery-policy", state, source],
    queryFn: () => unwrap(api.GET("/api/operations/sources/{state}/{source}/delivery-policy", { params: { path } })),
  });
  const save = useMutation({
    mutationFn: (removalHandling: "MARK_ONLY" | "END_BENEFITS") =>
      unwrap(api.PUT("/api/operations/sources/{state}/{source}/delivery-policy", { params: { path }, body: { removalHandling } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ["delivery-policy", state, source] }),
  });
  return (
    <Panel title="Records that are no longer delivered">
      <QueryState query={policy}>
        {(p) => (
          <Field id="removal" label="When a record disappears from a delivery">
            <Select
              id="removal"
              value={p.removalHandling ?? "MARK_ONLY"}
              options={[
                { value: "MARK_ONLY", label: "Mark it as no longer delivered" },
                { value: "END_BENEFITS", label: "Mark it and end its linked benefits" },
              ]}
              onChange={(e) => save.mutate(e.target.value as "MARK_ONLY" | "END_BENEFITS")}
            />
          </Field>
        )}
      </QueryState>
    </Panel>
  );
}

function GatesPanel({ state, source }: Source) {
  const path = { state, source };
  const client = useQueryClient();
  const gates = useQuery({
    queryKey: ["gates", state, source],
    queryFn: () => unwrap(api.GET("/api/operations/sources/{state}/{source}/gates", { params: { path } })),
  });
  const [values, setValues] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: () => {
      const merged = new Map<string, string>();
      gates.data?.forEach((g) => g.type && merged.set(g.type, String(g.value)));
      Object.entries(values).forEach(([k, v]) => merged.set(k, v));
      return unwrap(
        api.PUT("/api/operations/sources/{state}/{source}/gates", {
          params: { path },
          body: {
            gates: [...merged.entries()]
              .filter(([, v]) => v.trim() !== "")
              .map(([type, v]) => ({ type: type as Gate, value: Number(v), enabled: true })),
          },
        }),
      );
    },
    onSuccess: () => client.invalidateQueries({ queryKey: ["gates", state, source] }),
  });
  const dryRun = useMutation({
    mutationFn: () => unwrap(api.POST("/api/operations/sources/{state}/{source}/gates/dry-run", { params: { path } })),
  });
  return (
    <Panel title="Quality gates">
      <QueryState query={gates}>
        {(current) => (
          <FilterBar label="Quality gates" onSubmit={() => save.mutate()}>
            {GATES.map((g) => (
              <Field key={g.type} id={`gate-${g.type}`} label={g.label} hint={g.hint}>
                <TextInput
                  id={`gate-${g.type}`}
                  inputMode="decimal"
                  value={values[g.type] ?? String(current.find((c) => c.type === g.type)?.value ?? "")}
                  onChange={(e) => setValues({ ...values, [g.type]: e.target.value })}
                />
              </Field>
            ))}
            <Button type="submit">Save gates</Button>
            <Button variant="secondary" onClick={() => dryRun.mutate()}>
              Dry run on the latest delivery
            </Button>
          </FilterBar>
        )}
      </QueryState>
      {dryRun.data && (
        <ul data-testid="dry-run">
          {dryRun.data.map((r) => (
            <li key={r.type}>
              {r.type}: {r.status} (measured {r.observed ?? "n/a"}, limit {r.threshold})
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function ConnectorPanel({ state, source }: Source) {
  const path = { state, source };
  const connector = useQuery({
    queryKey: ["connector", state, source],
    retry: false,
    queryFn: () => unwrap(api.GET("/api/operations/sources/{state}/{source}/connector", { params: { path } })),
  });
  const [type, setType] = useState("");
  const [settings, setSettings] = useState<Record<string, string>>({});
  const [credential, setCredential] = useState("");
  const kind = type || connector.data?.type || "excel";
  const spec = CONNECTORS[kind] ?? EXCEL;
  const current = { ...(connector.data?.type === kind ? connector.data.config : {}), ...settings };
  const save = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT("/api/operations/sources/{state}/{source}/connector", {
          params: { path },
          body: {
            type: kind,
            settings: Object.fromEntries(spec.fields.map((f) => [f, current[f] ?? ""]).filter(([, v]) => v !== "")),
            credentials: spec.secret && credential ? { [spec.secret]: credential } : {},
          },
        }),
      ),
    onSuccess: () => setCredential(""),
  });
  const test = useMutation({
    mutationFn: () => unwrap(api.POST("/api/operations/sources/{state}/{source}/connector/test", { params: { path } })),
  });
  const preview = useMutation({
    mutationFn: () => unwrap(api.GET("/api/operations/sources/{state}/{source}/connector/preview", { params: { path, query: { rows: 5 } } })),
  });
  const profile = useMutation({
    mutationFn: () => unwrap(api.GET("/api/operations/sources/{state}/{source}/connector/profile", { params: { path } })),
  });
  return (
    <Panel title="Connector">
      <FilterBar label="Connector" onSubmit={() => save.mutate()}>
        <Field id="conn-type" label="Source type">
          <Select
            id="conn-type"
            value={kind}
            options={[
              { value: "excel", label: "Excel workbook" },
              { value: "jdbc", label: "Database (PostgreSQL, MySQL, SQL Server, Oracle)" },
              { value: "sftp", label: "SFTP folder" },
              { value: "rest", label: "REST API" },
            ]}
            onChange={(e) => {
              setType(e.target.value);
              setSettings({});
            }}
          />
        </Field>
        {spec.fields.map((f) => (
          <Field key={f} id={`conn-${f}`} label={f}>
            <TextInput
              id={`conn-${f}`}
              value={current[f] ?? ""}
              onChange={(e) => setSettings({ ...settings, [f]: e.target.value })}
            />
          </Field>
        ))}
        {spec.secret && (
          <Field id="conn-secret" label={`${spec.secret} (stored encrypted, never shown again)`}>
            <TextInput
              id="conn-secret"
              type="password"
              autoComplete="off"
              value={credential}
              onChange={(e) => setCredential(e.target.value)}
            />
          </Field>
        )}
        <Button type="submit">Save connector</Button>
        <Button variant="secondary" onClick={() => test.mutate()}>Test connection</Button>
        <Button variant="secondary" onClick={() => preview.mutate()}>Preview rows</Button>
        <Button variant="secondary" onClick={() => profile.mutate()}>Profile</Button>
      </FilterBar>
      {test.isSuccess && <p role="status">The connection works.</p>}
      {test.isError && <p role="alert">The connection failed: {String((test.error as Error).message)}</p>}
      {preview.data && (
        <p data-testid="connector-preview">
          {preview.data.header.join(", ")} — {preview.data.rows.length} row(s) shown
        </p>
      )}
      {profile.data && <p data-testid="connector-profile">Profiled {profile.data.columns?.length ?? 0} column(s).</p>}
    </Panel>
  );
}

export function SourceOperationsPage() {
  const [state, setState] = useState("");
  const [source, setSource] = useState("");
  const [chosen, setChosen] = useState<Source | null>(null);
  return (
    <>
      <PageHeader
        title="Source operations"
        description="Schedule, connector, quality gates and change handling for a source."
      />
      <FilterBar label="Choose a source" onSubmit={() => state && source && setChosen({ state, source })}>
        <Field id="ops-state" label="Pack or state code">
          <TextInput id="ops-state" value={state} onChange={(e) => setState(e.target.value)} />
        </Field>
        <Field id="ops-source" label="Source ID">
          <TextInput id="ops-source" value={source} onChange={(e) => setSource(e.target.value)} />
        </Field>
        <Button type="submit">Open</Button>
      </FilterBar>
      {chosen && (
        <>
          <SchedulePanel {...chosen} />
          <ConnectorPanel {...chosen} />
          <GatesPanel {...chosen} />
          <DeliveryPolicyPanel {...chosen} />
        </>
      )}
    </>
  );
}

/** Held, rectified and rejected-final records of a batch, counted from the held-record list. */
function HeldCounts({ runId, batch, processingRejected }: { runId: string; batch: string; processingRejected: number }) {
  const session = useSession();
  const counts = useQuery({
    queryKey: ["held-records", "batch-counts", batch],
    enabled: session.can("STEWARD_HELD_VIEW"),
    queryFn: async () => {
      const total = async (status: string) =>
        (await unwrap(api.GET("/api/v1/held-records", { params: { query: { batch, status, size: 1 } } }))).totalItems;
      const [held, rectified, rejected] = await Promise.all([total("WAITING"), total("RECTIFIED"), total("REJECTED_FINAL")]);
      return { held, rectified, rejected };
    },
  });
  if (!counts.data) {
    return null;
  }
  return (
    <p data-testid="run-held-counts">
      Processing rejected {processingRejected} · Held {counts.data.held} · Rectified {counts.data.rectified} · Final rejected {counts.data.rejected}
      {" · "}
      <a href={`/api/onboarding/runs/${runId}/rejected-rows.csv`}>Processing rejected rows (CSV)</a>
      {session.can("AUDIT_VIEW") && (
        <>
          {" · "}
          <Link to={`/audit?category=final-rejected&batch=${batch}`}>Final rejected records</Link>
        </>
      )}
      {session.can("BATCH_VIEW") && (
        <>
          {" · "}
          <a href={`/api/v1/batches/${batch}/rejection-report`}>Stewardship rejection report (CSV)</a>
        </>
      )}
    </p>
  );
}

/** On a run: what the delivery changed, the quality gates, and the release decision (approve or reject). */
export function RunReleasePanel({ runId, state, source }: { runId: string; state: string; source: string }) {
  const session = useSession();
  const client = useQueryClient();
  const path = { state, source, runId };
  const run = useQuery({
    queryKey: ["run-ops", runId],
    queryFn: () => unwrap(api.GET("/api/onboarding/runs/{runId}", { params: { path: { runId } } })),
  });
  const release = useQuery({
    queryKey: ["release", runId],
    queryFn: () => unwrap(api.GET("/api/operations/sources/{state}/{source}/runs/{runId}/release", { params: { path } })),
  });
  const [reason, setReason] = useState("");
  const decide = useMutation({
    mutationFn: (approve: boolean) =>
      approve
        ? unwrap(api.POST("/api/operations/sources/{state}/{source}/runs/{runId}/release/approve", { params: { path }, body: { reason } }))
        : unwrap(api.POST("/api/operations/sources/{state}/{source}/runs/{runId}/release/reject", { params: { path }, body: { reason } })),
    onSuccess: () => client.invalidateQueries(),
  });
  const detail = run.data as Record<string, unknown> | undefined;
  const changes = detail?.deliveryChanges as Record<string, number> | null | undefined;
  const gates = (release.data?.gates ?? []) as Record<string, unknown>[];
  const decisions = (release.data?.decisions ?? []) as Record<string, unknown>[];
  const paused = detail?.status === "PAUSED";
  const processing = detail?.processing as { batchId?: string; rejectedRows?: number } | null | undefined;
  const batch = processing?.batchId;
  return (
    <Panel title="Delivery changes and release">
      {changes && (
        <p data-testid="delivery-changes">
          New {changes.new} · Changed {changes.changed} · Unchanged {changes.unchanged} · Removed {changes.removed}
        </p>
      )}
      {batch && <HeldCounts runId={runId} batch={batch} processingRejected={processing?.rejectedRows ?? 0} />}
      <ul data-testid="run-gates">
        {gates.map((g, i) => (
          <li key={i}>
            {String(g.gate)}: {String(g.status)} (measured {String(g.observed ?? "n/a")}, limit {String(g.threshold)})
          </li>
        ))}
      </ul>
      <ul data-testid="run-decisions">
        {decisions.map((d, i) => (
          <li key={i}>
            {String(d.decision)} by {String(d.decidedBy)}
            {d.reasons ? ` — ${String(d.reasons)}` : ""}
          </li>
        ))}
      </ul>
      {paused && session.can("ONBOARDING_APPROVE") && (
        <FilterBar label="Release decision">
          <Field id="release-reason" label="Reason">
            <TextInput id="release-reason" value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
          <Button onClick={() => decide.mutate(true)}>Approve release</Button>
          <Button variant="secondary" disabled={!reason.trim()} onClick={() => decide.mutate(false)}>
            Hold
          </Button>
        </FilterBar>
      )}
      {decide.isError && <p role="alert">The decision was not accepted: you cannot decide a run you started.</p>}
    </Panel>
  );
}
