import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type { AuditEvent } from "../../api/types";
import { useI18n } from "../../i18n/i18n";
import { optional, useListParams } from "../../shared/hooks";
import {
  Button,
  DataTable,
  Field,
  FilterBar,
  JsonValue,
  PageHeader,
  Pagination,
  QueryState,
  Select,
  StatusBadge,
  TextInput,
} from "../../shared/components";
import { useSession } from "../auth";
import { FinalRejectedReport, FinalRejectedSummary } from "./FinalRejectedReport";

const RESULTS = ["", "SUCCESS", "DENIED", "FAILURE"];

/**
 * The audit trail for auditors and platform administrators: who did what, where and with which result, filterable by
 * user, action, result, state and date, with a CSV export of the filtered events (itself audited).
 */
export function AuditLogPage() {
  const { t } = useI18n();
  const session = useSession();
  const params = useListParams();
  const finalRejected = params.get("category") === "final-rejected";
  const filters = {
    actor: optional(params.get("actor")),
    action: optional(params.get("action")),
    result: optional(params.get("result")),
    state: optional(params.get("state")),
    from: optional(params.get("from")),
    to: optional(params.get("to")),
  };
  const actions = useQuery({
    queryKey: ["audit", "actions"],
    queryFn: () => unwrap(api.GET("/api/v1/audit-events/actions")),
  });
  const events = useQuery({
    queryKey: ["audit", "events", filters, params.page],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/audit-events", {
          params: { query: { ...filters, page: params.page, size: 50 } },
        }),
      ),
  });
  const exportQuery = new URLSearchParams(
    Object.entries(filters).filter((entry): entry is [string, string] => entry[1] !== undefined),
  ).toString();
  return (
    <>
      <PageHeader
        title={t("audit.title")}
        description={t("audit.description")}
        actions={
          session.can("REPORT_EXPORT") ? (
            <a
              className="button-link"
              href={`/api/v1/audit-events/export${exportQuery ? `?${exportQuery}` : ""}`}
              data-testid="audit-export"
            >
              {t("audit.export")}
            </a>
          ) : undefined
        }
      />
      <FilterBar label="Report category">
        <Field id="audit-category" label="Category">
          <Select
            id="audit-category"
            value={params.get("category")}
            onChange={(e) => params.set({ category: e.target.value })}
            options={[
              { value: "", label: "Audit events" },
              { value: "final-rejected", label: "Final rejected records" },
            ]}
          />
        </Field>
      </FilterBar>
      {finalRejected ? <FinalRejectedReport /> : <><FinalRejectedSummary />
      <FilterBar label={t("audit.filters")}>
        <Field id="audit-actor" label={t("audit.actor")}>
          <TextInput
            id="audit-actor"
            defaultValue={params.get("actor")}
            onBlur={(e) => params.set({ actor: e.target.value })}
          />
        </Field>
        <Field id="audit-action" label={t("audit.action")}>
          <Select
            id="audit-action"
            value={params.get("action")}
            onChange={(e) => params.set({ action: e.target.value })}
            options={[
              { value: "", label: t("audit.any") },
              ...(actions.data ?? []).map((action) => ({ value: action, label: action })),
            ]}
          />
        </Field>
        <Field id="audit-result" label={t("audit.result")}>
          <Select
            id="audit-result"
            value={params.get("result")}
            onChange={(e) => params.set({ result: e.target.value })}
            options={RESULTS.map((result) => ({
              value: result,
              label: result === "" ? t("audit.any") : result,
            }))}
          />
        </Field>
        <Field id="audit-state" label={t("audit.state")}>
          <TextInput
            id="audit-state"
            defaultValue={params.get("state")}
            onBlur={(e) => params.set({ state: e.target.value })}
          />
        </Field>
        <Field id="audit-from" label={t("audit.from")}>
          <TextInput
            id="audit-from"
            type="date"
            value={params.get("from")}
            onChange={(e) => params.set({ from: e.target.value })}
          />
        </Field>
        <Field id="audit-to" label={t("audit.to")}>
          <TextInput
            id="audit-to"
            type="date"
            value={params.get("to")}
            onChange={(e) => params.set({ to: e.target.value })}
          />
        </Field>
        <Button
          variant="secondary"
          onClick={() =>
            params.set({ actor: "", action: "", result: "", state: "", from: "", to: "" })
          }
        >
          {t("audit.clear")}
        </Button>
      </FilterBar>
      <QueryState query={events} label={t("audit.title")}>
        {(page) => (
          <>
            <DataTable<AuditEvent>
              caption={t("audit.title")}
              testId="audit-table"
              rows={page.items}
              rowKey={(event) => event.eventId ?? String(event.sequence)}
              emptyText={t("audit.empty")}
              columns={[
                {
                  key: "time",
                  header: t("audit.time"),
                  render: (event) => (event.occurredAt ? new Date(event.occurredAt).toLocaleString() : "—"),
                },
                { key: "actor", header: t("audit.actor"), render: (event) => event.actor ?? "—" },
                { key: "action", header: t("audit.action"), render: (event) => event.action },
                {
                  key: "resource",
                  header: t("audit.resource"),
                  render: (event) =>
                    event.resourceType
                      ? `${event.resourceType}${event.resourceId ? ` ${event.resourceId.slice(0, 12)}` : ""}`
                      : "—",
                },
                { key: "state", header: t("audit.state"), render: (event) => event.stateCode ?? "—" },
                {
                  key: "result",
                  header: t("audit.result"),
                  render: (event) => <StatusBadge status={event.result} />,
                },
                {
                  key: "details",
                  header: t("audit.details"),
                  render: (event) => <JsonValue value={event.metadata} />,
                },
              ]}
            />
            <Pagination page={page} onPage={params.setPage} label={t("audit.title")} />
          </>
        )}
      </QueryState></>}
    </>
  );
}
