import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import {
  DataTable,
  PageHeader,
  Pagination,
  Panel,
  QueryState,
  StatusBadge,
} from "../../shared/components";
import { RunReleasePanel } from "../operations";
export function OnboardingRunsPage() {
  const [page, setPage] = useState(0);
  const q = useQuery({
    queryKey: ["runs", page],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/onboarding-runs", {
          params: { query: { page, size: 20 } },
        }),
      ),
  });
  return (
    <>
      <PageHeader
        title="Onboarding runs"
        description="Persisted onboarding execution history across every department source."
      />
      <QueryState query={q} isEmpty={(d) => d.items.length === 0}>
        {(d) => (
          <>
            <DataTable
              caption="Onboarding runs"
              rows={d.items}
              rowKey={(r) => r.runId ?? ""}
              columns={[
                {
                  key: "run",
                  header: "Run",
                  render: (r) => (
                    <Link to={`/onboarding-runs/${r.runId}`}>
                      {r.runId?.slice(0, 8)}
                    </Link>
                  ),
                },
                { key: "state", header: "State", render: (r) => r.stateCode },
                {
                  key: "status",
                  header: "Status",
                  render: (r) => <StatusBadge status={r.status} />,
                },
                {
                  key: "stage",
                  header: "Stage",
                  render: (r) => r.currentStage ?? "—",
                },
              ]}
            />
            <Pagination
              page={d}
              onPage={setPage}
              label="Onboarding runs pages"
            />
          </>
        )}
      </QueryState>
    </>
  );
}
export function OnboardingRunDetailPage() {
  const { id = "" } = useParams();
  const q = useQuery({
    queryKey: ["run", id],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/onboarding-runs/{runId}", {
          params: { path: { runId: id } },
        }),
      ),
  });
  return (
    <>
      <PageHeader title="Onboarding run detail" />
      <QueryState query={q}>
        {(d) => (
          <>
          <Panel title={d.run?.status ?? "Run"}>
            <p>
              State: {d.run?.stateCode} · Current stage: {d.run?.currentStage}
            </p>
            <ol>
              {d.timeline.map((x) => (
                <li key={x.stage}>
                  {x.stage}: {x.status}
                </li>
              ))}
            </ol>
          </Panel>
          {d.run?.stateCode && d.run.sourceSystem && (
            <RunReleasePanel runId={id} state={d.run.stateCode} source={d.run.sourceSystem} />
          )}
          </>
        )}
      </QueryState>
    </>
  );
}
