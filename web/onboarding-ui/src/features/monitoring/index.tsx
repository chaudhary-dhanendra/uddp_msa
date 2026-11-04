import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, unwrap, unwrapEmpty } from "../../api/client";
import {
  DataTable,
  PageHeader,
  Panel,
  QueryState,
} from "../../shared/components";
import { Button, Field, FilterBar, Select, TextInput } from "../../shared/components/forms";
import { useSession } from "../auth";

type Metric = "DQ_SCORE" | "LOAD_FAILURES" | "GATE_FAILURES" | "SCHEDULE_MISSES" | "MATCH_DRIFT" | "PENDING_APPROVALS";

const METRICS: { value: Metric; label: string }[] = [
  { value: "DQ_SCORE", label: "Data-quality score (%)" },
  { value: "LOAD_FAILURES", label: "Load failures (7 days)" },
  { value: "GATE_FAILURES", label: "Gate failures (7 days)" },
  { value: "SCHEDULE_MISSES", label: "Schedule misses (7 days)" },
  { value: "MATCH_DRIFT", label: "Matching drift (%)" },
  { value: "PENDING_APPROVALS", label: "Rule and scheme approvals waiting" },
];

function show(value: unknown): string {
  return typeof value === "number" ? String(Math.round(value * 10) / 10) : "—";
}

/** Unread alert count in the header; links to the monitoring screen. */
export function NotificationBell() {
  const session = useSession();
  const allowed = session.can("ONBOARDING_VIEW");
  const unread = useQuery({
    queryKey: ["notifications", "unread"],
    enabled: allowed,
    refetchInterval: 60_000,
    queryFn: () =>
      unwrap(
        api.GET("/api/monitoring/notifications", {
          params: { query: { unreadOnly: true, limit: 50 } },
        }),
      ),
  });
  if (!allowed) {
    return null;
  }
  const count = unread.data?.length ?? 0;
  return (
    <Link
      to="/monitoring"
      data-testid="notification-bell"
      aria-label={`Alerts, ${count} unread`}
    >
      Alerts{count > 0 ? ` (${count})` : ""}
    </Link>
  );
}

export function MonitoringPage() {
  const session = useSession();
  const client = useQueryClient();
  const [department, setDepartment] = useState("");
  const [source, setSource] = useState("");
  const [applied, setApplied] = useState({ department: "", source: "" });
  const overview = useQuery({
    queryKey: ["monitoring", applied],
    queryFn: () =>
      unwrap(
        api.GET("/api/monitoring/overview", {
          params: {
            query: {
              department: applied.department || undefined,
              source: applied.source || undefined,
            },
          },
        }),
      ),
  });
  const notifications = useQuery({
    queryKey: ["notifications", "all"],
    queryFn: () =>
      unwrap(api.GET("/api/monitoring/notifications", { params: { query: { limit: 20 } } })),
  });
  const rules = useQuery({
    queryKey: ["alert-rules"],
    queryFn: () => unwrap(api.GET("/api/monitoring/alert-rules")),
  });
  const read = useMutation({
    mutationFn: (id: string) =>
      unwrapEmpty(
        api.POST("/api/monitoring/notifications/{id}/read", { params: { path: { id } } }),
      ),
    onSuccess: () => client.invalidateQueries({ queryKey: ["notifications"] }),
  });
  const remove = useMutation({
    mutationFn: (id: string) =>
      unwrapEmpty(api.DELETE("/api/monitoring/alert-rules/{id}", { params: { path: { id } } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ["alert-rules"] }),
  });
  const [name, setName] = useState("");
  const [metric, setMetric] = useState<Metric>("LOAD_FAILURES");
  const [comparison, setComparison] = useState("GT");
  const [threshold, setThreshold] = useState("1");
  const [severity, setSeverity] = useState("WARNING");
  const create = useMutation({
    mutationFn: () =>
      unwrap(
        api.PUT("/api/monitoring/alert-rules", {
          body: {
            ruleId: null,
            name,
            metric,
            comparison,
            threshold: Number(threshold),
            severity,
            stateCode: null,
            sourceSystem: null,
            enabled: true,
          },
        }),
      ),
    onSuccess: () => {
      setName("");
      return client.invalidateQueries({ queryKey: ["alert-rules"] });
    },
  });
  const canEdit = session.can("ONBOARDING_RUN");
  return (
    <>
      <PageHeader
        title="Monitoring"
        description="Data quality, load failures, gate failures and schedule misses per source."
      />
      <FilterBar
        label="Monitoring filters"
        onSubmit={() => setApplied({ department, source })}
      >
        <Field id="mon-department" label="Department">
          <TextInput
            id="mon-department"
            value={department}
            onChange={(e) => setDepartment(e.target.value)}
          />
        </Field>
        <Field id="mon-source" label="Source">
          <TextInput
            id="mon-source"
            value={source}
            onChange={(e) => setSource(e.target.value)}
          />
        </Field>
        <Button type="submit">Apply filters</Button>
      </FilterBar>
      <QueryState query={overview} isEmpty={(d) => d.length === 0} emptyText="No source has delivered yet.">
        {(rows) => (
          <DataTable
            caption="Source health"
            testId="monitoring-overview"
            rows={rows}
            rowKey={(r) => `${r.stateCode}/${r.sourceSystem}`}
            columns={[
              {
                key: "source",
                header: "Source",
                render: (r) => `${String(r.department ?? "—")} · ${String(r.stateCode)}/${String(r.sourceSystem)}`,
              },
              ...METRICS.map((m) => ({
                key: m.value,
                header: m.label,
                render: (r: Record<string, unknown>) => show(r[m.value]),
              })),
            ]}
          />
        )}
      </QueryState>
      <Panel title="Alerts">
        <QueryState query={notifications} isEmpty={(d) => d.length === 0} emptyText="No alerts.">
          {(rows) => (
            <ul data-testid="alerts">
              {rows.map((n) => (
                <li key={String(n.notificationId)} data-testid="alert">
                  [{String(n.severity)}] {String(n.message)}
                  {n.readAt ? " (read)" : (
                    <>
                      {" "}
                      <Button
                        variant="secondary"
                        onClick={() => read.mutate(String(n.notificationId))}
                      >
                        Mark read
                      </Button>
                    </>
                  )}
                </li>
              ))}
            </ul>
          )}
        </QueryState>
      </Panel>
      <Panel title="Alert rules">
        <QueryState query={rules} isEmpty={(d) => d.length === 0} emptyText="No alert rules yet.">
          {(rows) => (
            <ul data-testid="alert-rules">
              {rows.map((r) => (
                <li key={r.ruleId}>
                  {r.name}: {r.metric} {r.comparison === "GT" ? ">" : "<"} {r.threshold} ({r.severity})
                  {canEdit && r.ruleId && (
                    <>
                      {" "}
                      <Button variant="secondary" onClick={() => remove.mutate(r.ruleId ?? "")}>
                        Delete
                      </Button>
                    </>
                  )}
                </li>
              ))}
            </ul>
          )}
        </QueryState>
        {canEdit && (
          <FilterBar label="New alert rule" onSubmit={() => name.trim() && create.mutate()}>
            <Field id="rule-name" label="Rule name">
              <TextInput id="rule-name" value={name} onChange={(e) => setName(e.target.value)} />
            </Field>
            <Field id="rule-metric" label="Metric">
              <Select
                id="rule-metric"
                value={metric}
                options={METRICS}
                onChange={(e) => setMetric(e.target.value as Metric)}
              />
            </Field>
            <Field id="rule-comparison" label="Fires when the value is">
              <Select
                id="rule-comparison"
                value={comparison}
                options={[
                  { value: "GT", label: "above" },
                  { value: "LT", label: "below" },
                ]}
                onChange={(e) => setComparison(e.target.value)}
              />
            </Field>
            <Field id="rule-threshold" label="Threshold">
              <TextInput
                id="rule-threshold"
                value={threshold}
                onChange={(e) => setThreshold(e.target.value)}
              />
            </Field>
            <Field id="rule-severity" label="Severity">
              <Select
                id="rule-severity"
                value={severity}
                options={[
                  { value: "INFO", label: "Info" },
                  { value: "WARNING", label: "Warning" },
                  { value: "CRITICAL", label: "Critical" },
                ]}
                onChange={(e) => setSeverity(e.target.value)}
              />
            </Field>
            <Button type="submit">Add rule</Button>
          </FilterBar>
        )}
      </Panel>
    </>
  );
}
