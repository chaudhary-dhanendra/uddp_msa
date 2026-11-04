import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type { BenefitSummary } from "../../api/types";
import { Button, DataTable, PageHeader, Pagination, Panel, QueryState, Select, StatCard, StatGrid, StatusBadge } from "../../shared/components";

type SchemeOption = { schemeId?: string | null; name?: string | null };

/** The platform scheme catalogue, for the scheme filters; empty when it cannot be read. */
function useSchemeOptions() {
  return useQuery({
    queryKey: ["scheme-catalogue"],
    queryFn: () => unwrap(api.GET("/api/v1/schemes")).catch(() => ({ schemes: [] as SchemeOption[] })),
  });
}

const STATUSES = ["ALL", "ACTIVE", "SUSPENDED", "CLOSED", "ENDED"];

/** Where a benefit came from: the source state, system and scheme code, and the benefit ID in that source. */
export function sourceLineage(b: BenefitSummary) {
  return [b.stateCode, b.sourceSystem, b.sourceSchemeCode].filter(Boolean).join(" · ") + (b.sourceBenefitId ? ` #${b.sourceBenefitId}` : "");
}

/** Schemes and benefits: every benefit with its scheme, status and source lineage; filterable by scheme and status. */
export function BenefitsPage() {
  const [page, setPage] = useState(0);
  const [schemeId, setSchemeId] = useState("");
  const [status, setStatus] = useState("ALL");
  const schemes = useSchemeOptions();
  const q = useQuery({
    queryKey: ["benefits", page, schemeId, status],
    queryFn: () => unwrap(api.GET("/api/v1/benefits", { params: { query: { page, size: 50, schemeId: schemeId || undefined, status } } })),
  });
  const options = (schemes.data?.schemes ?? []) as SchemeOption[];
  return <>
    <PageHeader title="Schemes and benefits" description="Every benefit the sources report: its scheme, status, beneficiary level and source lineage. Open a benefit for its beneficiaries, household and disbursements." />
    <Panel>
      <div className="row" style={{ marginBottom: "var(--space-4)", flexWrap: "wrap" }}>
        <Select aria-label="Scheme" value={schemeId} onChange={(e) => { setSchemeId(e.target.value); setPage(0); }}
          options={[{ value: "", label: "All schemes" }, ...options.map((s) => ({ value: s.schemeId ?? "", label: `${s.name ?? s.schemeId} (${s.schemeId})` }))]} />
        <Select aria-label="Benefit status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}
          options={STATUSES.map((s) => ({ value: s, label: s === "ALL" ? "All statuses" : s }))} />
      </div>
      <QueryState query={q as never} isEmpty={(d: { items: BenefitSummary[] }) => d.items.length === 0}>
        {(d: { items: BenefitSummary[]; page: number; size: number; totalElements: number; totalPages: number }) => <>
          <p className="muted">{d.totalElements} benefit{d.totalElements === 1 ? "" : "s"}</p>
          <DataTable caption="Benefits" rows={d.items} rowKey={(r) => r.benefitId ?? ""} columns={[
            { key: "benefit", header: "Benefit", render: (r) => <Link to={`/benefits/${r.benefitId}`}>{r.benefitRecordKey ?? r.sourceBenefitId ?? r.benefitId?.slice(0, 8)}</Link> },
            { key: "scheme", header: "Scheme", render: (r) => <>{r.schemeName ?? r.schemeId ?? "—"}<div className="muted">{r.schemeId}</div></> },
            { key: "status", header: "Status", render: (r) => <StatusBadge status={r.status} /> },
            { key: "for", header: "For", render: (r) => r.beneficiaryLevel === "HOUSEHOLD" ? "Household" : r.beneficiaryLevel === "INDIVIDUAL" ? "Individual" : r.beneficiaryLevel ?? "—" },
            { key: "department", header: "Department", render: (r) => r.departmentName ?? r.departmentCode ?? "—" },
            { key: "lineage", header: "Source lineage", render: (r) => sourceLineage(r) || "—" },
            { key: "start", header: "Since", render: (r) => r.startDate ?? "—" },
          ]} />
          <Pagination page={{ page: d.page, size: d.size, totalElements: d.totalElements, totalPages: d.totalPages } as never} onPage={setPage} label="Benefit pages" />
        </>}
      </QueryState>
    </Panel>
  </>;
}

type Finding = { goldenResidentId?: string | null; name?: string | null; exclusivityGroup?: string | null; schemeIds: string[]; stateCodes: string[]; benefitCount: number };
type NotEnrolled = { goldenResidentId?: string | null; name?: string | null; dateOfBirth?: string | null; ageYears?: number | null; gender?: string | null; stateCodes: string[] };
type SchemeResult = { schemeId?: string | null; schemeName?: string | null; exclusivityGroup?: string | null; residentsEvaluated: number; eligible: number; enrolled: number; undetermined: number; notEnrolledCount: number; notEnrolled: NotEnrolled[] };

function DuplicateBenefits() {
  const q = useQuery({ queryKey: ["duplicate-benefits"], queryFn: () => unwrap(api.GET("/api/v1/reports/duplicate-benefits")) });
  return <Panel title="Duplicate benefits">
    <p className="muted">Golden Residents holding more than one active benefit in one exclusivity group.</p>
    <QueryState query={q as never}>
      {(d: { generatedAt?: string | null; findingCount: number; findings: Finding[] }) => <>
        <p><strong>{d.findingCount} finding{d.findingCount === 1 ? "" : "s"}</strong>{d.generatedAt ? <span className="muted"> · generated {new Date(d.generatedAt).toLocaleString()}</span> : null}</p>
        <DataTable caption="Duplicate benefit findings" rows={d.findings} rowKey={(f) => `${f.goldenResidentId}-${f.exclusivityGroup}`} emptyText="No resident holds two active benefits in one exclusivity group." columns={[
          { key: "who", header: "Resident", render: (f) => <Link to={`/golden-residents/${f.goldenResidentId}`}>{f.name ?? f.goldenResidentId?.slice(0, 8)}</Link> },
          { key: "group", header: "Exclusivity group", render: (f) => f.exclusivityGroup ?? "—" },
          { key: "schemes", header: "Schemes", render: (f) => f.schemeIds.join(", ") },
          { key: "states", header: "States", render: (f) => f.stateCodes.join(", ") },
          { key: "count", header: "Benefits", align: "end", render: (f) => f.benefitCount },
        ]} />
      </>}
    </QueryState>
  </Panel>;
}

function EligibilityReport() {
  const schemes = useSchemeOptions();
  const [asOf, setAsOf] = useState(() => new Date().toISOString().slice(0, 10));
  const [schemeId, setSchemeId] = useState("");
  const [request, setRequest] = useState<{ asOf: string; schemeId: string } | null>(null);
  const q = useQuery({
    queryKey: ["eligible-not-enrolled", request],
    enabled: request !== null,
    queryFn: () => unwrap(api.GET("/api/v1/reports/eligible-not-enrolled", { params: { query: { asOf: request?.asOf || undefined, schemeId: request?.schemeId || undefined } } })),
  });
  const options = (schemes.data?.schemes ?? []) as SchemeOption[];
  return <Panel title="Eligibility report">
    <p className="muted">For each scheme with eligibility: who is eligible on the as-of date, who already holds a benefit in its exclusivity group, and the eligible residents not enrolled.</p>
    <form className="row" style={{ alignItems: "flex-end", flexWrap: "wrap", marginBottom: "var(--space-4)" }} onSubmit={(e) => { e.preventDefault(); setRequest({ asOf, schemeId }); }}>
      <label style={{ display: "flex", flexDirection: "column", gap: 6, fontWeight: 600 }}>As-of date
        <input type="date" aria-label="As-of date" value={asOf} onChange={(e) => setAsOf(e.target.value)} style={{ font: "inherit", padding: "var(--space-2) var(--space-3)", border: "1px solid var(--color-border)", borderRadius: "var(--radius)" }} />
      </label>
      <label style={{ display: "flex", flexDirection: "column", gap: 6, fontWeight: 600 }}>Scheme code
        <Select aria-label="Scheme code" value={schemeId} onChange={(e) => setSchemeId(e.target.value)}
          options={[{ value: "", label: "Every scheme with eligibility" }, ...options.map((s) => ({ value: s.schemeId ?? "", label: `${s.schemeId} · ${s.name ?? ""}` }))]} />
      </label>
      <Button type="submit">Run report</Button>
    </form>
    {request === null ? <p className="muted">Choose an as-of date and a scheme, then run the report.</p> :
      <QueryState query={q as never}>
        {(d: { asOf?: string | null; schemes: SchemeResult[] }) => <>
          <p className="muted">Ages on {d.asOf}.</p>
          {d.schemes.length === 0 && <p>No scheme with eligibility matches.</p>}
          {d.schemes.map((s) => <section key={s.schemeId} style={{ marginBottom: "var(--space-5)" }}>
            <h3>{s.schemeName} ({s.schemeId}){s.exclusivityGroup ? <span className="muted"> · group {s.exclusivityGroup}</span> : null}</h3>
            <StatGrid label={`${s.schemeId} totals`}>
              <StatCard label="Residents evaluated" value={s.residentsEvaluated} />
              <StatCard label="Eligible" value={s.eligible} />
              <StatCard label="Enrolled" value={s.enrolled} />
              <StatCard label="Eligible, not enrolled" value={s.notEnrolledCount} />
              <StatCard label="Cannot determine" value={s.undetermined} />
            </StatGrid>
            <DataTable caption={`${s.schemeId}: eligible but not enrolled`} rows={s.notEnrolled} rowKey={(r) => r.goldenResidentId ?? ""} emptyText="Every eligible resident is enrolled." columns={[
              { key: "who", header: "Resident", render: (r) => <Link to={`/golden-residents/${r.goldenResidentId}`}>{r.name ?? r.goldenResidentId?.slice(0, 8)}</Link> },
              { key: "age", header: "Age", align: "end", render: (r) => r.ageYears ?? "—" },
              { key: "gender", header: "Gender", render: (r) => r.gender ?? "—" },
              { key: "states", header: "States", render: (r) => r.stateCodes.join(", ") },
            ]} />
          </section>)}
        </>}
      </QueryState>}
  </Panel>;
}

/** Operational reports: duplicate benefits, and eligibility by as-of date and scheme code. */
export function ReportsPage() {
  return <>
    <PageHeader title="Operational reports" description="Duplicate benefits across exclusivity groups, and eligible residents who are not enrolled." />
    <DuplicateBenefits />
    <EligibilityReport />
  </>;
}
