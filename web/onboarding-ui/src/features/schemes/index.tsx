import { useState } from "react";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { sendJson } from "../../api/client";
import type { RuleSetSummary } from "../../api/types";
import { Button, DataTable, DescriptionList, PageHeader, Panel, QueryState, Select, StatCard, StatGrid, StatusBadge } from "../../shared/components";
import { useSession } from "../auth";
import styles from "./schemes.module.css";

export type SchemeVersion = { schemeId: string; id: string; version: number; status: string; name: string; beneficiaryLevel: string; owner: string; effectiveFrom: string; effectiveTo?: string | null; eligibilityRuleSetId?: string | null; implementingDepartments: string[]; sourcePacks: string[]; exclusivityGroups: string[]; configuration: string };

async function get<T>(path: string): Promise<T> {
  const response = await fetch(path);
  if (!response.ok) throw new Error("Unable to load Scheme Packs");
  return response.json() as Promise<T>;
}

const today = () => new Date().toISOString().slice(0, 10);
const inEffect = (v: SchemeVersion, on: string) => v.status === "APPROVED" && v.effectiveFrom <= on && (!v.effectiveTo || v.effectiveTo >= on);
const ruleName = (rule: RuleSetSummary | undefined, id: string | null | undefined) =>
  rule ? `${rule.ownerId ?? ""} v${rule.version} (${rule.status ?? ""})` : id ?? "Not configured";

/** Eligibility rule sets, for naming a scheme's linked rule; an empty list when the user may not read them. */
function useEligibilityRules() {
  return useQuery({ queryKey: ["rule-sets", "ELIGIBILITY"], queryFn: () => get<RuleSetSummary[]>("/api/rule-sets?type=ELIGIBILITY").catch(() => [] as RuleSetSummary[]) });
}

/** The catalogue: one row per scheme, with its newest version and the version in effect today. */
export function SchemeCataloguePage() {
  const schemes = useQuery({ queryKey: ["scheme-packs"], queryFn: () => get<SchemeVersion[]>("/api/scheme-packs") });
  const session = useCan();
  const [department, setDepartment] = useState("");
  const [status, setStatus] = useState("");
  const [level, setLevel] = useState("");
  return <QueryState query={schemes} label="Scheme catalogue">{(rows) => {
    const byScheme = new Map<string, SchemeVersion[]>();
    rows.forEach((v) => byScheme.set(v.schemeId, [...(byScheme.get(v.schemeId) ?? []), v]));
    const on = today();
    const catalogue = Array.from(byScheme.values()).map((versions) => {
      const sorted = [...versions].sort((a, b) => b.version - a.version);
      return { latest: sorted[0] as SchemeVersion, current: sorted.find((v) => inEffect(v, on)), count: sorted.length };
    }).sort((a, b) => a.latest.name.localeCompare(b.latest.name));
    const filtered = catalogue.filter(({ latest }) => (!department || latest.owner === department) && (!status || latest.status === status) && (!level || latest.beneficiaryLevel === level));
    return <div className={styles.page}>
      <PageHeader title="Scheme Studio" description="Every configured scheme, its newest version and the version in effect today. Open a scheme to change it, preview its impact or approve it."
        actions={session("PLATFORM_SETTINGS") ? <><Link className="buttonLink" to="/scheme-studio/new">+ Create scheme</Link><Link to="/scheme-studio/wizard/new">Guided wizard</Link></> : undefined} />
      <section className={styles.card}>
        <div className={styles.filters}>
          <Select aria-label="Department" value={department} onChange={(e) => setDepartment(e.target.value)} options={[{ value: "", label: "All departments" }, ...Array.from(new Set(rows.map((x) => x.owner))).map((x) => ({ value: x, label: x }))]} />
          <Select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)} options={[{ value: "", label: "All statuses" }, ...Array.from(new Set(rows.map((x) => x.status))).map((x) => ({ value: x, label: x }))]} />
          <Select aria-label="Beneficiary level" value={level} onChange={(e) => setLevel(e.target.value)} options={[{ value: "", label: "All beneficiaries" }, { value: "INDIVIDUAL", label: "Individual" }, { value: "HOUSEHOLD", label: "Household" }]} />
        </div>
        <DataTable rows={filtered} rowKey={(x) => x.latest.schemeId} caption="Scheme catalogue" emptyText="No schemes match these filters." columns={[
          { key: "name", header: "Scheme", render: (x) => <><Link to={`/scheme-studio/${encodeURIComponent(x.latest.schemeId)}/${x.latest.id}`}>{x.latest.name}</Link><div className={styles.hint}>{x.latest.schemeId}</div></> },
          { key: "owner", header: "Owning Department", render: (x) => x.latest.owner },
          { key: "level", header: "For", render: (x) => x.latest.beneficiaryLevel === "HOUSEHOLD" ? "Household" : "Individual" },
          { key: "latest", header: "Newest version", render: (x) => <span className={styles.actions}>v{x.latest.version} <StatusBadge status={x.latest.status} /></span> },
          { key: "current", header: "In effect today", render: (x) => x.current ? `v${x.current.version} from ${x.current.effectiveFrom}` : <span className="muted">None</span> },
          { key: "versions", header: "Versions", render: (x) => x.count, align: "end" },
        ]} />
      </section>
    </div>;
  }}</QueryState>;
}

/** `can` from the signed-in session. */
function useCan(): (permission: string) => boolean {
  const session = useSession();
  return (permission) => session.can(permission as never);
}

type EligibilityResult = { outcome: string; missingAttributes: string[]; clauseReferences: string[]; explanation: string };
type ImpactPreviewData = { evaluated: number; eligible: number; notEligible: number; indeterminate: number; missingAttributeCounts: Record<string, number>; sample: (EligibilityResult & { entityId: string; exclusivityConflicts: string[]; ageYears?: number | null })[] };
type GoldenSummary = { goldenResidentId: string; name?: string | null; dateOfBirth?: string | null; gender?: string | null };

const OUTCOMES: Record<string, string> = { ELIGIBLE: "Eligible", NOT_ELIGIBLE: "Not eligible", INDETERMINATE: "Cannot determine", UNCONFIGURED: "Not configured" };
const outcomeTone: Record<string, string> = { ELIGIBLE: "success", NOT_ELIGIBLE: "neutral", INDETERMINATE: "warning" };

/** Completed years on `on` from a full ISO date of birth, the way the backend works out age.years; null otherwise. */
export function ageOn(dateOfBirth: string | null | undefined, on: Date = new Date()): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateOfBirth ?? "");
  if (!m) return null;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  let age = on.getFullYear() - y;
  if (on.getMonth() + 1 < mo || (on.getMonth() + 1 === mo && on.getDate() < d)) age--;
  return age;
}

function OutcomeBadge({ outcome }: { outcome: string }) {
  return <span className={styles.outcome} data-tone={outcomeTone[outcome] ?? "neutral"} title={outcome}>{OUTCOMES[outcome] ?? outcome}</span>;
}

function EntityTest({ scheme }: { scheme: SchemeVersion }) {
  const [id, setId] = useState("");
  const [result, setResult] = useState<EligibilityResult | null>(null);
  const [error, setError] = useState("");
  const run = async () => {
    setError("");
    try { setResult(await get<EligibilityResult>(`/api/scheme-packs/${scheme.schemeId}/versions/${scheme.id}/eligibility/${id.trim()}`)); }
    catch (e) { setError(e instanceof Error ? e.message : "Unable to test eligibility"); }
  };
  const entity = scheme.beneficiaryLevel === "HOUSEHOLD" ? "Golden Family ID" : "Golden Resident ID";
  return <Panel title="Test one person">
    <div className={styles.filters}>
      <div className={styles.label}><label htmlFor="entity-id">{entity}</label><input id="entity-id" aria-label="Golden entity ID" className={styles.input} value={id} onChange={(e) => setId(e.target.value)} /></div>
      <Button onClick={() => void run()} disabled={!id.trim()}>Test eligibility</Button>
    </div>
    {error && <p className={styles.alert} role="alert">{error}</p>}
    {result && <DescriptionList items={[
      { label: "Outcome", value: <OutcomeBadge outcome={result.outcome} /> },
      { label: "Explanation", value: result.explanation || "Not configured" },
      { label: "Missing data", value: result.missingAttributes.join(", ") || "None" },
      { label: "Fired clauses", value: result.clauseReferences.join(", ") || "None" },
    ]} />}
  </Panel>;
}

function ImpactPreview({ scheme, canSeeResidents }: { scheme: SchemeVersion; canSeeResidents: boolean }) {
  const [data, setData] = useState<ImpactPreviewData | null>(null);
  const [people, setPeople] = useState<Map<string, GoldenSummary>>(new Map());
  const [error, setError] = useState("");
  const [running, setRunning] = useState(false);
  const [filter, setFilter] = useState("");
  const household = scheme.beneficiaryLevel === "HOUSEHOLD";
  const run = async () => {
    setError(""); setRunning(true);
    try {
      const preview = await get<ImpactPreviewData>(`/api/scheme-packs/${scheme.schemeId}/versions/${scheme.id}/impact-preview?sampleLimit=100`);
      setData(preview);
      if (!household && canSeeResidents) {
        const page = await get<{ items: GoldenSummary[] }>("/api/v1/golden-residents?size=200").catch(() => ({ items: [] as GoldenSummary[] }));
        setPeople(new Map((page.items ?? []).map((p) => [p.goldenResidentId, p])));
      }
    } catch (e) { setError(e instanceof Error ? e.message : "Unable to run impact preview"); }
    finally { setRunning(false); }
  };
  if (!scheme.eligibilityRuleSetId) return <Panel title="Impact Preview"><p className={styles.note}>Impact cannot be determined until an eligibility rule is configured. Edit the draft and choose an eligibility rule.</p></Panel>;
  const rows = (data?.sample ?? []).filter((x) => !filter || x.outcome === filter);
  const percent = (n: number) => data && data.evaluated ? `${Math.round((n * 100) / data.evaluated)}%` : "—";
  const conflicts = (data?.sample ?? []).filter((x) => x.exclusivityConflicts.length).length;
  return <Panel title={`Impact Preview · v${scheme.version}`} actions={<Button onClick={() => void run()} disabled={running}>{running ? "Running…" : data ? "Run again" : "Run impact preview"}</Button>}>
    <p className={styles.muted}>Evaluates every active Golden {household ? "Family" : "Resident"} against this version's eligibility rule. Ages are worked out from each Golden Resident's date of birth on today's date. Nothing is saved.</p>
    {error && <p className={styles.alert} role="alert">{error}</p>}
    {!data ? <p className={styles.muted}>Preview not yet run.</p> : <>
      <StatGrid label="Impact totals">
        <StatCard label="Total evaluated" value={data.evaluated} testId="impact-evaluated" />
        <StatCard label={`Eligible (${percent(data.eligible)})`} value={data.eligible} testId="impact-eligible" />
        <StatCard label={`Not eligible (${percent(data.notEligible)})`} value={data.notEligible} testId="impact-not-eligible" />
        <StatCard label={`Cannot determine (${percent(data.indeterminate)})`} value={data.indeterminate} testId="impact-indeterminate" />
      </StatGrid>
      <p className="visually-hidden">Total evaluated: {data.evaluated}. Eligible: {data.eligible} ({percent(data.eligible)}).</p>
      <h3>Missing data</h3>
      {Object.keys(data.missingAttributeCounts).length
        ? <ul>{Object.entries(data.missingAttributeCounts).map(([key, count]) => <li key={key}>{key}: {count}</li>)}</ul>
        : <p className={styles.muted}>No one is missing data the rule reads.</p>}
      <h3>Potential conflicts</h3>
      <p className={styles.muted}>{conflicts ? `${conflicts} ${household ? "families" : "residents"} already hold a benefit of another scheme in the same exclusivity group (${scheme.exclusivityGroups.join(", ")}).` : "No potential conflicts identified."}</p>
      <div className={styles.filters}>
        <Select aria-label="Sample outcome" value={filter} onChange={(e) => setFilter(e.target.value)} options={[{ value: "", label: "All outcomes" }, { value: "ELIGIBLE", label: "Eligible" }, { value: "NOT_ELIGIBLE", label: "Not eligible" }, { value: "INDETERMINATE", label: "Cannot determine" }]} />
      </div>
      <DataTable caption={`Evaluated ${household ? "families" : "residents"}${data.sample.length < data.evaluated ? ` (first ${data.sample.length} of ${data.evaluated})` : ""}`} rows={rows} rowKey={(x) => x.entityId}
        emptyText="Empty population or no matching sample results."
        columns={[
          { key: "who", header: household ? "Family" : "Resident", render: (x) => { const p = people.get(x.entityId); return <Link to={household ? `/families/${x.entityId}` : `/golden-residents/${x.entityId}`}>{p?.name || `${household ? "Family" : "Resident"} ${x.entityId.slice(0, 8)}`}</Link>; } },
          ...(household ? [] : [
            { key: "age", header: "Age", align: "end" as const, // The backend sends the age the rule evaluated; the date of birth is only a fallback, since a masked one is just the year.
            render: (x: ImpactPreviewData["sample"][number]) => x.ageYears ?? ageOn(people.get(x.entityId)?.dateOfBirth) ?? "—" },
            { key: "gender", header: "Gender", render: (x: ImpactPreviewData["sample"][number]) => people.get(x.entityId)?.gender ?? "—" },
          ]),
          { key: "outcome", header: "Outcome", render: (x) => <OutcomeBadge outcome={x.outcome} /> },
          { key: "why", header: "Why", render: (x) => x.missingAttributes.length ? `Missing: ${x.missingAttributes.join(", ")}` : x.clauseReferences.join(", ") || x.explanation },
          { key: "conflicts", header: "Potential conflicts", render: (x) => x.exclusivityConflicts.length ? <span className={styles.conflict}>Potential conflicts: {x.exclusivityConflicts.join(", ")}</span> : <span className="muted">None</span> },
        ]} />
    </>}
  </Panel>;
}

const TABS = ["Overview", "Eligibility", "Data Requirements", "Impact Preview", "Versions"] as const;
type Tab = (typeof TABS)[number];
const tabSlug = (t: Tab) => t.toLowerCase().replace(/ /g, "-");

/**
 * One scheme version: its actions (edit, submit, approve or reject, create a new version) and tabs. Another user with
 * approval rights approves a submitted version; nothing here changes a benefit.
 */
export function SchemeWorkspacePage() {
  const { schemeId = "", versionId = "" } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const [params, setParams] = useSearchParams();
  const queries = useQueryClient();
  const can = useCan();
  const history = useQuery({ queryKey: ["scheme", schemeId], queryFn: () => get<SchemeVersion[]>(`/api/scheme-packs/${schemeId}/versions`) });
  const rules = useEligibilityRules();
  const tab: Tab = TABS.find((t) => tabSlug(t) === params.get("tab")) ?? "Overview";
  const setTab = (t: Tab) => setParams((p) => { const next = new URLSearchParams(p); next.set("tab", tabSlug(t)); return next; }, { replace: true });
  const [notice, setNotice] = useState<string>((location.state as { saved?: boolean } | null)?.saved ? "Draft saved." : "");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const act = async (path: string, done: string, body?: unknown, then?: (r: Response) => Promise<void>) => {
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await sendJson(`/api/scheme-packs/${encodeURIComponent(schemeId)}/versions/${versionId}/${path}`, "POST", body);
      if (!response.ok) {
        const problem = await response.json().catch(() => null) as { message?: string } | null;
        throw new Error(problem?.message ?? `Unable to ${path}`);
      }
      if (then) await then(response);
      else setNotice(done);
      await queries.invalidateQueries({ queryKey: ["scheme", schemeId] });
      await queries.invalidateQueries({ queryKey: ["scheme-packs"] });
    } catch (cause) { setError(cause instanceof Error ? cause.message : `Unable to ${path}`); }
    finally { setBusy(false); }
  };
  const copy = () => act("copy", "", undefined, async (response) => {
    const draft = await response.json() as { schemeId: string; id: string };
    navigate(`/scheme-studio/${encodeURIComponent(draft.schemeId)}/${draft.id}/edit`);
  });
  const reject = () => {
    const reason = window.prompt("Reason for rejecting this version");
    if (reason) void act("reject", "Rejected: the version stays out of effect.", { reason });
  };

  return <QueryState query={history} label="Scheme workspace">{(versions) => {
    const scheme = versions.find((x) => x.id === versionId) ?? versions[0];
    if (!scheme) return <p>Scheme not found.</p>;
    const rule = (rules.data ?? []).find((r) => r.id === scheme.eligibilityRuleSetId);
    const requirements = rule?.requiredAttributes ?? (scheme as SchemeVersion & { requiredAttributes?: string[] }).requiredAttributes ?? [];
    const author = can("PLATFORM_SETTINGS");
    const actions = <>
      {scheme.status === "DRAFT" && author && <Button variant="secondary" onClick={() => navigate(`/scheme-studio/${encodeURIComponent(schemeId)}/${scheme.id}/edit`)}>Edit Draft</Button>}
      {scheme.status === "DRAFT" && author && <Button onClick={() => void act("submit", "Submitted for approval. Another user with approval rights must approve it.")} disabled={busy}>Submit for approval</Button>}
      {scheme.status === "PENDING_APPROVAL" && can("RULES_APPROVE") && <>
        <Button onClick={() => void act("approve", "Approved: this version takes effect from its effective date.", {})} disabled={busy}>Approve</Button>
        <Button variant="secondary" onClick={reject} disabled={busy}>Reject</Button></>}
      {author && <Button variant="secondary" onClick={() => void copy()} disabled={busy}>Create New Version</Button>}
    </>;
    return <div className={styles.page}>
      <p className={styles.muted}><Link to="/scheme-studio">← Scheme catalogue</Link></p>
      <PageHeader title={scheme.name} description={`${scheme.schemeId} · version ${scheme.version} · effective ${scheme.effectiveFrom} – ${scheme.effectiveTo ?? "ongoing"}`} actions={actions} />
      <div className={styles.actions}>
        <span>Status:</span> <StatusBadge status={scheme.status} />
        {versions.length > 1 && <Select aria-label="Version" value={scheme.id} onChange={(e) => navigate(`/scheme-studio/${encodeURIComponent(schemeId)}/${e.target.value}?tab=${tabSlug(tab)}`)}
          options={versions.map((v) => ({ value: v.id, label: `v${v.version} · ${v.status}` }))} />}
        {author && <Link to={`/scheme-studio/${encodeURIComponent(schemeId)}/${scheme.id}/wizard/1`}>Open in guided wizard</Link>}
      </div>
      {notice && <p className={styles.note} role="status">{notice}</p>}
      {error && <p className={styles.alert} role="alert">{error}</p>}
      <div className={styles.tabs} role="tablist" aria-label="Scheme workspace">
        {TABS.map((t) => <button key={t} role="tab" aria-selected={t === tab} className={styles.tab} onClick={() => setTab(t)}>{t}</button>)}
      </div>
      {tab === "Overview" && <Panel title="Overview"><DescriptionList items={[
        { label: "Version / status", value: <span className={styles.actions}>v{scheme.version} <StatusBadge status={scheme.status} /></span> },
        { label: "Owning Department", value: scheme.owner },
        { label: "Implementing Departments", value: scheme.implementingDepartments.join(", ") || "Not configured" },
        { label: "Beneficiary", value: scheme.beneficiaryLevel === "HOUSEHOLD" ? "Household (Golden Family)" : "Individual (Golden Resident)" },
        { label: "Effective", value: `${scheme.effectiveFrom} – ${scheme.effectiveTo ?? "Ongoing"}` },
        { label: "Source Packs", value: scheme.sourcePacks.join(", ") || "Not configured" },
        { label: "Exclusivity groups", value: scheme.exclusivityGroups.join(", ") || "Not configured" },
        { label: "Eligibility rule", value: ruleName(rule, scheme.eligibilityRuleSetId) },
      ]} /></Panel>}
      {tab === "Eligibility" && <>
        <Panel title="Eligibility rule">{scheme.eligibilityRuleSetId
          ? <DescriptionList items={[
            { label: "Rule", value: <span className={styles.actions}>{ruleName(rule, scheme.eligibilityRuleSetId)} {rule && <StatusBadge status={rule.status} />}</span> },
            { label: "Reads", value: requirements.join(", ") || "—" },
            { label: "Edit or test it", value: <Link to="/rules">Open Rules Studio</Link> },
          ]} />
          : <p className={styles.muted}>Eligibility rule not configured.</p>}</Panel>
        {author && <EntityTest scheme={scheme} />}
      </>}
      {tab === "Data Requirements" && <>
        <Panel title="Required data">{requirements.length ? <ul>{requirements.map((x) => <li key={x}>{x} — required by the eligibility rule</li>)}</ul> : <p className={styles.muted}>Not configured</p>}</Panel>
        <Panel title="Source dependencies">{scheme.sourcePacks.length ? <ul>{scheme.sourcePacks.map((x) => <li key={x}>{x} — linked Source Pack</li>)}</ul> : <p className={styles.muted}>No source dependencies configured</p>}<p className={styles.muted}>A linked Source Pack does not guarantee a citizen value is available.</p></Panel>
      </>}
      {tab === "Impact Preview" && <ImpactPreview key={scheme.id} scheme={scheme} canSeeResidents={can("RESIDENT_VIEW")} />}
      {tab === "Versions" && <Panel title="Versions"><DataTable caption="Versions" rows={versions} rowKey={(v) => v.id} columns={[
        { key: "v", header: "Version", render: (v) => v.id === scheme.id ? <strong>v{v.version} (open)</strong> : <Link to={`/scheme-studio/${encodeURIComponent(schemeId)}/${v.id}`}>v{v.version}</Link> },
        { key: "s", header: "Status", render: (v) => <StatusBadge status={v.status} /> },
        { key: "e", header: "Effective", render: (v) => `${v.effectiveFrom} – ${v.effectiveTo ?? "ongoing"}` },
        { key: "r", header: "Eligibility rule", render: (v) => ruleName((rules.data ?? []).find((r) => r.id === v.eligibilityRuleSetId), v.eligibilityRuleSetId) },
      ]} /></Panel>}
    </div>;
  }}</QueryState>;
}

