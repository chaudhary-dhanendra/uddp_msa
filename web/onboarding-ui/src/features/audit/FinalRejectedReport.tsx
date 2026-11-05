import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type { RejectedRow, WeekCount } from "../../api/types";
import { optional, useListParams } from "../../shared/hooks";
import { DataTable, Field, FilterBar, Panel, Pagination, QueryState, TextInput } from "../../shared/components";
import { useSession } from "../auth";

type Row = RejectedRow;

const FILTERS = ["batch", "source", "department", "state", "reason", "decidedBy", "from", "to"] as const;

/** Counts of final-rejected records and their trend per source per week; shown at the top of the audit page. */
export function FinalRejectedSummary() {
  const summary = useQuery({
    queryKey: ["audit-reports", "final-rejected", "summary"],
    queryFn: () => unwrap(api.GET("/api/v1/audit-reports/final-rejected/summary")),
  });
  const params = useListParams();
  return (
    <Panel title="Final rejected records">
      <QueryState query={summary} label="Final rejected records">
        {(data) => (
          <div className="stack" data-testid="final-rejected-summary">
            <p>
              {data.total} rejected for good · {data.reopened} reopened afterwards ·{" "}
              <button type="button" className="link" onClick={() => params.set({ category: "final-rejected" })}>
                Open the report
              </button>
            </p>
            <DataTable<WeekCount>
              caption="Final rejected per source per week"
              testId="final-rejected-trend"
              rows={data.perSourcePerWeek}
              rowKey={(w) => `${w.sourceSystem}/${w.weekStart}`}
              emptyText="Nothing was rejected in the last twelve weeks."
              columns={[
                { key: "week", header: "Week starting", render: (w) => w.weekStart },
                { key: "source", header: "Source", render: (w) => w.sourceSystem },
                { key: "count", header: "Rejected", render: (w) => w.count },
              ]}
            />
          </div>
        )}
      </QueryState>
    </Panel>
  );
}

/** The audit report category "Final rejected records": filterable list and a CSV export (masked by default). */
export function FinalRejectedReport() {
  const params = useListParams();
  const session = useSession();
  const filters = Object.fromEntries(FILTERS.map((name) => [name, optional(params.get(name))]));
  const report = useQuery({
    queryKey: ["audit-reports", "final-rejected", filters, params.page],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/audit-reports/final-rejected", {
          params: { query: { ...filters, page: params.page, size: 50 } },
        }),
      ),
  });
  const exportQuery = new URLSearchParams(
    Object.entries(filters).filter((entry): entry is [string, string] => entry[1] !== undefined),
  ).toString();
  return (
    <>
      <FilterBar label="Final rejected filters">
        {FILTERS.map((name) => (
          <Field key={name} id={`fr-${name}`} label={name}>
            <TextInput
              id={`fr-${name}`}
              type={name === "from" || name === "to" ? "date" : "text"}
              defaultValue={params.get(name)}
              onBlur={(e) => params.set({ [name]: e.target.value })}
            />
          </Field>
        ))}
      </FilterBar>
      {session.can("REPORT_EXPORT") && (
        <p>
          <a
            className="button-link"
            href={`/api/v1/audit-reports/final-rejected/export${exportQuery ? `?${exportQuery}` : ""}`}
            data-testid="final-rejected-export"
          >
            Export CSV (identifiers masked)
          </a>
        </p>
      )}
      <QueryState query={report} label="Final rejected records">
        {(page) => (
          <>
            <DataTable<Row>
              caption="Final rejected records"
              testId="final-rejected-table"
              rows={page.items}
              rowKey={(row) => row.heldId ?? ""}
              emptyText="No record was rejected for good."
              columns={[
                { key: "reference", header: "Record", render: (r) => r.reference },
                { key: "source", header: "Source", render: (r) => r.sourceSystem },
                { key: "batch", header: "Batch", render: (r) => r.batchId?.slice(0, 8) ?? "—" },
                { key: "reason", header: "Reason code", render: (r) => r.reasonCode ?? "—" },
                { key: "note", header: "Note", render: (r) => r.note ?? "—" },
                {
                  key: "original",
                  header: "Original failures",
                  render: (r) => r.originalReasons.map((o) => o.code).join(", ") || "—",
                },
                { key: "by", header: "Decided by", render: (r) => r.decidedBy ?? "—" },
                {
                  key: "at",
                  header: "Decided at",
                  render: (r) => (r.decidedAt ? new Date(r.decidedAt).toLocaleString() : "—"),
                },
                { key: "reopened", header: "Reopened", render: (r) => (r.reopened ? "yes" : "no") },
              ]}
            />
            <Pagination page={page} onPage={params.setPage} label="Final rejected records" />
          </>
        )}
      </QueryState>
    </>
  );
}
