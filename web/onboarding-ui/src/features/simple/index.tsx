/* eslint-disable @typescript-eslint/no-explicit-any */
import { RevealButton } from "../resident/RevealButton";
import { formatEntitlement } from "../../shared/entitlement";
import type { GoldenResident360 } from "../../api/types";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import {
  DataTable,
  DescriptionList,
  FilterBar,
  PageHeader,
  Pagination,
  Panel,
  QueryState,
  TextInput,
} from "../../shared/components";
function List({ kind }: { kind: "canonical" | "matching" | "golden" | "dq" }) {
  const [page, setPage] = useState(0);
  const [term, setTerm] = useState("");
  const cfg = {
    canonical: {
      path: "/api/v1/canonical-residents",
      title: "Canonical explorer",
      detail: "canonical-residents",
      query: { page, size: 20, name: term || undefined },
    },
    matching: {
      path: "/api/v1/matching-runs",
      title: "Match evidence",
      detail: "matching-runs",
      query: { page, size: 20 },
    },
    golden: {
      path: "/api/v1/golden-residents",
      title: "Golden Resident 360",
      detail: "golden-residents",
      query: { page, size: 20, q: term || undefined },
    },
    dq: {
      path: "/api/v1/dq/findings",
      title: "Data quality",
      detail: "dq",
      query: { page, size: 20 },
    },
  }[kind];
  const q = useQuery({
    queryKey: [kind, page, term],
    queryFn: () =>
      unwrap(
        api.GET(
          cfg.path as never,
          { params: { query: cfg.query } as never } as never,
        ),
      ),
  });
  return (
    <>
      <PageHeader
        title={cfg.title}
        description="Read-only operational records."
      />
      {(kind === "canonical" || kind === "golden") && (
        <FilterBar label={`${cfg.title} filters`} onSubmit={() => setPage(0)}>
          <TextInput
            id={`${kind}-search`}
            aria-label="Search"
            value={term}
            onChange={(e) => setTerm(e.target.value)}
            placeholder="Search"
          />
        </FilterBar>
      )}
      <QueryState query={q as never} isEmpty={(d: any) => d.items.length === 0}>
        {(d: any) => (
          <>
            <DataTable
              caption={cfg.title}
              rows={d.items}
              rowKey={(r: any) =>
                r.canonicalResidentId ??
                r.goldenResidentId ??
                r.runId ??
                r.rawRecordId ??
                r.ruleId ??
                JSON.stringify(r)
              }
              columns={[
                {
                  key: "record",
                  header: "Record",
                  render: (r: any) => {
                    const id =
                      r.canonicalResidentId ??
                      r.goldenResidentId ??
                      r.runId ??
                      r.rawRecordId;
                    return id ? (
                      <Link to={`/${cfg.detail}/${id}`}>
                        {String(id).slice(0, 8)}
                      </Link>
                    ) : (
                      "—"
                    );
                  },
                },
                {
                  key: "name",
                  header: "Name / status",
                  render: (r: any) => r.name ?? r.status ?? r.ruleId ?? "—",
                },
              ]}
            />
            <Pagination
              page={d}
              onPage={setPage}
              label={`${cfg.title} pages`}
            />
          </>
        )}
      </QueryState>
    </>
  );
}
export const CanonicalPage = () => <List kind="canonical" />;
export const MatchingPage = () => <List kind="matching" />;
export const GoldenPage = () => <List kind="golden" />;
export const DqPage = () => <List kind="dq" />;
export function Resident360Page() {
  const { id = "" } = useParams();
  // Unmasked data from an audited reveal; shown for this view only.
  const [revealed, setRevealed] = useState<GoldenResident360 | null>(null);
  const resident = useQuery({
    queryKey: ["resident-360", id],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/golden-residents/{id}", { params: { path: { id } } }),
      ),
  });
  const families = useQuery({
    queryKey: ["resident-families", id],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/golden-residents/{id}/families", {
          params: { path: { id } },
        }),
      ),
  });
  const benefits = useQuery({
    queryKey: ["resident-benefits", id],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/golden-residents/{id}/benefits", {
          params: { path: { id } },
        }),
      ),
  });
  return (
    <>
      <PageHeader
        title="Golden Resident 360"
        description="Golden identity, source contributors, households, benefits and version evidence."
      />
      <QueryState query={resident as never}>
        {(masked: any) => {
          const data: any = revealed ?? masked;
          return (
            <>
              <Panel
                title={data.payload?.name ?? "Golden Resident"}
                actions={
                  revealed ? (
                    <span data-testid="revealed">Full view (audited)</span>
                  ) : (
                    <RevealButton id={id} onRevealed={setRevealed} />
                  )
                }
              >
                <DescriptionList
                  items={[
                    { label: "UDDP ID", value: data.uddpId },
                    ...(data.redirectToUddpId
                      ? [{ label: "Merged into", value: data.redirectToUddpId }]
                      : []),
                    { label: "Golden ID", value: data.goldenResidentId },
                    { label: "States", value: data.stateCodes?.join(", ") },
                    { label: "Current version", value: data.currentVersion },
                    { label: "Policy", value: data.survivorshipPolicyVersion },
                  ]}
                />
              </Panel>
              <Panel title="Field provenance">
                <DataTable
                  caption="Winning values and contributing sources"
                  rows={data.fields}
                  rowKey={(field: any) => field.fieldPath}
                  columns={[
                    {
                      key: "field",
                      header: "Field",
                      render: (field: any) => field.fieldPath,
                    },
                    {
                      key: "value",
                      header: "Golden value",
                      render: (field: any) => field.value,
                    },
                    {
                      key: "winner",
                      header: "Winning source",
                      render: (field: any) =>
                        `${field.selectedStateCode} / ${field.selectedSourceSystem}`,
                    },
                    {
                      key: "contributors",
                      header: "Contributors",
                      render: (field: any) =>
                        field.contributors
                          .map((c: any) => `${c.stateCode}/${c.sourceSystem}`)
                          .join(", "),
                    },
                  ]}
                />
              </Panel>
              <Panel title="Canonical source records">
                <DataTable
                  caption="Canonical records leading to this Golden identity"
                  rows={data.members}
                  rowKey={(member: any) => member.canonicalResidentId}
                  columns={[
                    {
                      key: "canonical",
                      header: "Canonical record",
                      render: (member: any) => (
                        <Link
                          to={`/canonical-residents/${member.canonicalResidentId}`}
                        >
                          {member.canonicalResidentId.slice(0, 8)}
                        </Link>
                      ),
                    },
                    {
                      key: "source",
                      header: "Source code / system",
                      render: (member: any) =>
                        `${member.stateCode} / ${member.sourceSystem}`,
                    },
                    {
                      key: "raw",
                      header: "Raw lineage",
                      render: (member: any) => member.rawRecordId.slice(0, 8),
                    },
                  ]}
                />
              </Panel>
              <Panel title="Version history">
                <DataTable
                  caption="Golden identity history"
                  rows={data.versions}
                  rowKey={(version: any) => version.versionNumber}
                  columns={[
                    {
                      key: "version",
                      header: "Version",
                      render: (version: any) => version.versionNumber,
                    },
                    {
                      key: "reasons",
                      header: "Change",
                      render: (version: any) =>
                        version.changeReasons.join(", "),
                    },
                    {
                      key: "from",
                      header: "Effective from",
                      render: (version: any) => version.effectiveFrom,
                    },
                  ]}
                />
              </Panel>
            </>
          );
        }}
      </QueryState>
      <QueryState query={families as never}>
        {(data: any) => (
          <Panel title="Golden Families">
            <DataTable
              caption="Families containing this resident"
              rows={data}
              rowKey={(family: any) => family.goldenFamilyId}
              columns={[
                {
                  key: "family",
                  header: "Golden Family",
                  render: (family: any) => (
                    <Link to={`/families/${family.goldenFamilyId}`}>
                      {family.goldenFamilyId.slice(0, 8)}
                    </Link>
                  ),
                },
                {
                  key: "source",
                  header: "Contributing source",
                  render: (family: any) =>
                    `${family.stateCode} / ${family.sourceSystem}`,
                },
                {
                  key: "members",
                  header: "Members",
                  render: (family: any) => family.memberCount,
                },
              ]}
            />
          </Panel>
        )}
      </QueryState>
      <QueryState query={benefits as never}>
        {(data: any) => (
          <Panel title={`Schemes and benefits (${data.benefitCount})`}>
            <DataTable
              caption="Benefit associations and lineage"
              rows={data.benefits}
              rowKey={(entry: any) => entry.benefit.benefitId}
              columns={[
                {
                  key: "scheme",
                  header: "Scheme",
                  render: (entry: any) => entry.benefit.schemeName,
                },
                {
                  key: "state",
                  header: "Source code",
                  render: (entry: any) => entry.benefit.stateCode,
                },
                {
                  key: "status",
                  header: "Status",
                  render: (entry: any) => entry.benefit.status,
                },
                {
                  key: "entitlement",
                  header: "Entitlement",
                  render: (entry: any) =>
                    formatEntitlement(entry.benefit.entitlement),
                },
                {
                  key: "lineage",
                  header: "Lineage",
                  render: (entry: any) =>
                    entry.lineage
                      .map((line: any) => line.rawRecordId.slice(0, 8))
                      .join(", "),
                },
              ]}
            />
          </Panel>
        )}
      </QueryState>
    </>
  );
}

export function Family360Page() {
  const { id = "" } = useParams();
  const family = useQuery({
    queryKey: ["family-360", id],
    queryFn: () =>
      unwrap(api.GET("/api/v1/families/{id}", { params: { path: { id } } })),
  });
  return (
    <>
      <PageHeader
        title="Golden Family 360"
        description="Household members, relationships and source-family provenance."
      />
      <QueryState query={family as never}>
        {(data: any) => (
          <>
            <Panel title="Golden Family">
              <DescriptionList
                items={[
                  { label: "UDDP ID", value: data.uddpId },
                  { label: "Golden ID", value: data.goldenFamilyId },
                  { label: "Status", value: data.status },
                  {
                    label: "Source family",
                    value: data.sourceFamilyId ?? data.familyRecordKey,
                  },
                ]}
              />
            </Panel>
            <Panel title="Members and relationships">
              <DataTable
                caption="Golden family members"
                rows={data.members ?? []}
                rowKey={(member: any) =>
                  member.goldenResidentId ?? member.canonicalResidentId
                }
                columns={[
                  {
                    key: "resident",
                    header: "Golden Resident",
                    render: (member: any) =>
                      member.goldenResidentId ? (
                        <Link
                          to={`/golden-residents/${member.goldenResidentId}`}
                        >
                          {member.name ?? member.goldenResidentId.slice(0, 8)}
                        </Link>
                      ) : (
                        (member.name ?? "—")
                      ),
                  },
                  {
                    key: "relationship",
                    header: "Relationship",
                    render: (member: any) => member.relationship ?? "—",
                  },
                  {
                    key: "source",
                    header: "Source",
                    render: (member: any) =>
                      `${member.stateCode ?? "—"} / ${member.sourceSystem ?? "—"}`,
                  },
                ]}
              />
            </Panel>
            <Panel title="Source and provenance">
              <DataTable
                caption="Family source records"
                rows={data.observations ?? data.sources ?? []}
                rowKey={(source: any) =>
                  source.canonicalFamilyId ??
                  source.familyRecordKey ??
                  source.rawRecordId
                }
                columns={[
                  {
                    key: "state",
                    header: "Source code",
                    render: (source: any) => source.stateCode ?? "—",
                  },
                  {
                    key: "source",
                    header: "Source",
                    render: (source: any) => source.sourceSystem ?? "—",
                  },
                  {
                    key: "raw",
                    header: "Raw lineage",
                    render: (source: any) =>
                      source.rawRecordId?.slice(0, 8) ?? "—",
                  },
                ]}
              />
            </Panel>
          </>
        )}
      </QueryState>
    </>
  );
}

export function FamiliesPage() {
  const q = useQuery({
    queryKey: ["families"],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/families", {
          params: { query: { page: 0, size: 20 } },
        }),
      ),
  });
  return (
    <>
      <PageHeader
        title="Golden families"
        description="Households and members built from configured Family rules."
      />
      <QueryState query={q as never} isEmpty={(d: any) => d.items.length === 0}>
        {(d: any) => (
          <DataTable
            caption="Golden families"
            rows={d.items}
            rowKey={(r: any) => r.goldenFamilyId}
            columns={[
              {
                key: "family",
                header: "Family",
                render: (r: any) => (
                  <Link to={`/families/${r.goldenFamilyId}`}>
                    {r.goldenFamilyId?.slice(0, 8)}
                  </Link>
                ),
              },
              {
                key: "status",
                header: "Status",
                render: (r: any) => r.status ?? "—",
              },
              {
                key: "members",
                header: "Members",
                render: (r: any) => r.memberCount ?? "—",
              },
            ]}
          />
        )}
      </QueryState>
    </>
  );
}

export { BenefitsPage, ReportsPage } from "../benefit/lists";
