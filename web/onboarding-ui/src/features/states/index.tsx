import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import {
  DataTable,
  PageHeader,
  Panel,
  QueryState,
} from "../../shared/components";
import { departmentLabel, usePackDepartments } from "../departments";

/** Source Packs: each department source system with its version and lifecycle. */
export function StatesPage() {
  const departments = usePackDepartments();
  const q = useQuery({
    queryKey: ["states"],
    queryFn: () => unwrap(api.GET("/api/v1/states")),
  });
  return (
    <>
      <PageHeader
        title="Source packs"
        description="Each department source system: configured source, mapping and lifecycle metadata."
      />
      <QueryState query={q} isEmpty={(d) => d.length === 0}>
        {(d) => (
          <DataTable
            caption="Source packs"
            rows={d}
            rowKey={(r) => r.stateCode ?? ""}
            columns={[
              {
                key: "department",
                header: "Department",
                render: (r) =>
                  departmentLabel(departments.get(r.packId ?? ""), "—"),
              },
              {
                key: "state",
                header: "Source code",
                render: (r) => (
                  <Link to={`/states/${r.stateCode}`}>{r.stateCode}</Link>
                ),
              },
              {
                key: "version",
                header: "Version",
                render: (r) => r.version ?? "—",
              },
              {
                key: "status",
                header: "Lifecycle",
                render: (r) => r.lifecycleStatus ?? "—",
              },
            ]}
          />
        )}
      </QueryState>
    </>
  );
}
export function StateDetailPage() {
  const { state = "" } = useParams();
  const q = useQuery({
    queryKey: ["state", state],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/states/{state}", { params: { path: { state } } }),
      ),
  });
  return (
    <>
      <PageHeader title={`Source pack ${state}`} />
      <QueryState query={q}>
        {(d) => (
          <Panel title={state}>
            <p>Status: {d.lifecycleStatus}</p>
            <p>
              Sources: {d.sources.length}; mappings: {d.mappings.length};
              crosswalks: {d.crosswalks.length}
            </p>
          </Panel>
        )}
      </QueryState>
    </>
  );
}
