import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import {
  DataTable,
  PageHeader,
  Panel,
  QueryState,
} from "../../shared/components";
import { catalogs, translate } from "../../i18n/i18n";
import { Button, Field, FilterBar, Select, TextInput } from "../../shared/components/forms";

/** What each item type means, in the words an operator uses. */
export const TYPE_LABELS: Record<string, string> = {
  PAUSED_RUN: "Runs waiting for release",
  GATE_FAILURE: "Quality gate failures",
  FAILED_RUN: "Failed runs",
  FAILED_SCHEDULED_RUN: "Failed scheduled runs",
  UNMAPPED_CODE: "Unmapped codes",
  REVIEW_TASK: "Match review tasks",
  PACK_APPROVAL: "Pack approvals",
  PROPOSAL_APPROVAL: "Stewardship approvals",
  RULE_DRAFT: "Rule drafts",
  RULE_APPROVAL: "Rule approvals",
  ELIGIBILITY_APPROVAL: "Eligibility rule approvals",
  SCHEME_APPROVAL: "Scheme Pack approvals",
  UNCERTAIN_MATCH: "Uncertain matches",
  MATCHING_DRIFT: "Matching drift alerts",
  MATCHING_APPROVAL: "Matching approvals",
  HELD_RECORDS: "Held records",
  HELD_BULK_PENDING: translate(catalogs.en ?? {}, "inbox.types.HELD_BULK_PENDING"),
};

const PRIORITIES = [
  { value: "", label: "Any priority" },
  { value: "HIGH", label: "High" },
  { value: "MEDIUM", label: "Medium" },
  { value: "LOW", label: "Low" },
];

function label(type: string | null | undefined): string {
  return (type && TYPE_LABELS[type]) || type || "—";
}

/** Everything that needs a person, with the screen that resolves each item one click away. */
export function InboxPage() {
  const [params, setParams] = useSearchParams();
  const [department, setDepartment] = useState(params.get("department") ?? "");
  const [type, setType] = useState(params.get("type") ?? "");
  const [priority, setPriority] = useState(params.get("priority") ?? "");
  const [minAge, setMinAge] = useState(params.get("minAgeDays") ?? "");
  const applied = {
    department: params.get("department") ?? "",
    type: params.get("type") ?? "",
    priority: params.get("priority") ?? "",
    minAgeDays: params.get("minAgeDays") ?? "",
  };
  const items = useQuery({
    queryKey: ["inbox", applied],
    queryFn: () =>
      unwrap(
        api.GET("/api/inbox/items", {
          params: {
            query: {
              department: applied.department || undefined,
              type: applied.type || undefined,
              priority: applied.priority || undefined,
              minAgeDays: applied.minAgeDays
                ? Number(applied.minAgeDays)
                : undefined,
            },
          },
        }),
      ),
  });
  const counts = useQuery({
    queryKey: ["inbox-counts"],
    queryFn: () => unwrap(api.GET("/api/inbox/counts")),
  });
  const apply = () => {
    const next = new URLSearchParams();
    if (department) next.set("department", department);
    if (type) next.set("type", type);
    if (priority) next.set("priority", priority);
    if (minAge) next.set("minAgeDays", minAge);
    setParams(next);
  };
  const typeOptions = [
    { value: "", label: "Any type" },
    ...Object.keys(counts.data ?? TYPE_LABELS).map((value) => ({
      value,
      label: label(value),
    })),
  ];
  return (
    <>
      <PageHeader
        title="Exception inbox"
        description="Everything that needs a person, across the departments you can see."
      />
      <FilterBar label="Inbox filters" onSubmit={apply}>
        <Field id="inbox-department" label="Department">
          <TextInput
            id="inbox-department"
            value={department}
            onChange={(e) => setDepartment(e.target.value)}
          />
        </Field>
        <Field id="inbox-type" label="Type">
          <Select
            id="inbox-type"
            value={type}
            options={typeOptions}
            onChange={(e) => setType(e.target.value)}
          />
        </Field>
        <Field id="inbox-priority" label="Priority">
          <Select
            id="inbox-priority"
            value={priority}
            options={PRIORITIES}
            onChange={(e) => setPriority(e.target.value)}
          />
        </Field>
        <Field id="inbox-age" label="At least this many days old">
          <TextInput
            id="inbox-age"
            inputMode="numeric"
            value={minAge}
            onChange={(e) => setMinAge(e.target.value.replace(/\D/g, ""))}
          />
        </Field>
        <Button type="submit">Apply filters</Button>
      </FilterBar>
      <QueryState
        query={items}
        isEmpty={(d) => d.length === 0}
        emptyText="Nothing needs attention."
      >
        {(rows) => (
          <DataTable
            caption="Items that need a person"
            testId="inbox-items"
            rows={rows}
            rowKey={(r) => r.item?.id ?? ""}
            columns={[
              {
                key: "priority",
                header: "Priority",
                render: (r) => r.item?.priority ?? "—",
              },
              {
                key: "type",
                header: "Type",
                render: (r) => label(r.item?.type),
              },
              {
                key: "title",
                header: "What",
                render: (r) => (
                  <>
                    <strong>{r.item?.title}</strong>
                    <div>{r.item?.detail}</div>
                  </>
                ),
              },
              {
                key: "source",
                header: "Source",
                render: (r) =>
                  `${r.department ?? "—"} · ${r.item?.stateCode ?? ""}/${r.item?.sourceSystem ?? "—"}`,
              },
              {
                key: "age",
                header: "Raised",
                render: (r) =>
                  r.item?.createdAt
                    ? new Date(r.item.createdAt).toLocaleDateString()
                    : "—",
              },
              {
                key: "open",
                header: "Resolve",
                render: (r) =>
                  r.item?.link ? <Link to={r.item.link}>Open</Link> : "—",
              },
            ]}
          />
        )}
      </QueryState>
    </>
  );
}

/** Counts per item type, for the Overview; each count links to the inbox filtered to that type. */
export function InboxCountsPanel() {
  const counts = useQuery({
    queryKey: ["inbox-counts"],
    queryFn: () => unwrap(api.GET("/api/inbox/counts")),
  });
  return (
    <Panel title="Needs attention">
      <QueryState query={counts}>
        {(data) => (
          <ul data-testid="inbox-counts">
            {Object.entries(data).map(([type, count]) => (
              <li key={type}>
                <Link to={`/inbox?type=${type}`}>{label(type)}</Link>: {count}
              </li>
            ))}
          </ul>
        )}
      </QueryState>
    </Panel>
  );
}
