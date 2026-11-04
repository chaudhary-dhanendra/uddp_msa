import { useEffect, useState } from "react";
import { sendJson } from "../../../api/client";
import type { DepartmentWithSources, RuleSetSummary, SchemeCodeCrosswalk, SchemeDataAvailability, SchemeImpact, SchemePackDraft, SchemePackVersion } from "../../../api/types";
import { RuleSetDraftForm, RuleTestPanel } from "../../rules";
import { GoDraftButton } from "../GoDraft";
import { emptyDetails, failure, getJson, saveDraft, toDraft } from "./model";

type Saved = (version: SchemePackVersion) => void;
const CLASSIFICATIONS = ["", "CENTRAL", "STATE", "CENTRALLY_SPONSORED"];

function ReadOnlyNote({ version }: { version: SchemePackVersion }) {
  return version.status === "DRAFT" ? null : <p role="note">Version {version.version} is {version.status}: create a new version to change it.</p>;
}

const numberOrNull = (value: string): number | null => value.trim() === "" || Number.isNaN(Number(value)) ? null : Number(value);

/** Step 1: who owns the scheme, who it is for, what it gives and when; creates the draft when there is none yet. */
export function ProfileStep({ version, onSaved }: { version?: SchemePackVersion | null; onSaved: Saved }) {
  const [draft, setDraft] = useState<SchemePackDraft>(() => version ? toDraft(version) : {
    schemeId: "", name: "", owner: "", beneficiaryLevel: "INDIVIDUAL", benefitType: "ENTITLEMENT", benefitForm: "CASH",
    effectiveFrom: "", effectiveTo: null, eligibilityRuleSetId: null, implementingDepartments: [], sourcePacks: [],
    exclusivityGroups: [], configuration: "{}", details: emptyDetails(),
  });
  const [departments, setDepartments] = useState<DepartmentWithSources[]>([]);
  const [error, setError] = useState("");
  useEffect(() => { void getJson<DepartmentWithSources[]>("/api/v1/departments").then(setDepartments).catch(() => setDepartments([])); }, []);
  const details = draft.details ?? emptyDetails();
  const setDetail = (change: Partial<typeof details>) => setDraft({ ...draft, details: { ...details, ...change } });
  const editable = !version || version.status === "DRAFT";
  const save = async () => {
    setError("");
    if (!draft.schemeId || !draft.name || !draft.owner || !draft.effectiveFrom) { setError("Scheme key, name, owning Department and effective date are required."); return; }
    try { onSaved(await saveDraft({ ...draft, implementingDepartments: draft.implementingDepartments.length ? draft.implementingDepartments : [draft.owner ?? ""] }, version)); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Unable to save"); }
  };
  return <section><h2>1 · Profile</h2>{version && <ReadOnlyNote version={version} />}{error && <p role="alert">{error}</p>}
    <label>Scheme key<input value={draft.schemeId ?? ""} disabled={Boolean(version)} onChange={(e) => setDraft({ ...draft, schemeId: e.target.value })} /></label>
    <label>Scheme name<input value={draft.name ?? ""} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label>
    <label>Owning Department<select value={draft.owner ?? ""} onChange={(e) => setDraft({ ...draft, owner: e.target.value })}><option value="">Select Department</option>{departments.map((d) => <option key={d.departmentCode} value={d.departmentCode ?? ""}>{d.name}</option>)}</select></label>
    <fieldset><legend>Implementing Departments</legend>{departments.map((d) => <label key={d.departmentCode}><input type="checkbox" checked={draft.implementingDepartments.includes(d.departmentCode ?? "")} onChange={() => setDraft({ ...draft, implementingDepartments: draft.implementingDepartments.includes(d.departmentCode ?? "") ? draft.implementingDepartments.filter((x) => x !== d.departmentCode) : [...draft.implementingDepartments, d.departmentCode ?? ""] })} />{d.name}</label>)}</fieldset>
    <label>Classification<select value={details.classification ?? ""} onChange={(e) => setDetail({ classification: e.target.value || null })}>{CLASSIFICATIONS.map((c) => <option key={c} value={c}>{c || "Not set"}</option>)}</select></label>
    <label>Beneficiary level<select value={draft.beneficiaryLevel ?? "INDIVIDUAL"} onChange={(e) => setDraft({ ...draft, beneficiaryLevel: e.target.value })}><option>INDIVIDUAL</option><option>HOUSEHOLD</option></select></label>
    <label>Benefit type<input value={details.benefitType ?? draft.benefitType ?? ""} onChange={(e) => { setDraft({ ...draft, benefitType: e.target.value, details: { ...details, benefitType: e.target.value } }); }} /></label>
    <label>Benefit form<input value={details.benefitForm ?? draft.benefitForm ?? ""} onChange={(e) => { setDraft({ ...draft, benefitForm: e.target.value, details: { ...details, benefitForm: e.target.value } }); }} /></label>
    <label>Amount<input value={details.amount ?? ""} onChange={(e) => setDetail({ amount: numberOrNull(e.target.value) })} /></label>
    <label>Quantity<input value={details.quantity ?? ""} onChange={(e) => setDetail({ quantity: numberOrNull(e.target.value) })} /></label>
    <label>Unit<input value={details.unit ?? ""} onChange={(e) => setDetail({ unit: e.target.value || null })} /></label>
    <label>Frequency<input value={details.frequency ?? ""} onChange={(e) => setDetail({ frequency: e.target.value || null })} /></label>
    <label>GO reference<input value={details.goReference ?? ""} onChange={(e) => setDetail({ goReference: e.target.value || null })} /></label>
    <label>Exclusivity groups<input value={draft.exclusivityGroups.join(", ")} onChange={(e) => setDraft({ ...draft, exclusivityGroups: e.target.value.split(",").map((x) => x.trim()).filter(Boolean) })} /></label>
    <label>Effective from<input placeholder="YYYY-MM-DD" value={draft.effectiveFrom ?? ""} onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })} /></label>
    <label>Effective to<input placeholder="YYYY-MM-DD" value={draft.effectiveTo ?? ""} onChange={(e) => setDraft({ ...draft, effectiveTo: e.target.value || null })} /></label>
    {editable && <button onClick={() => void save()}>Save profile</button>}
  </section>;
}

/** Step 2: the Source Packs the scheme reads and which of their source codes mean this scheme. */
export function SourcesStep({ version, onSaved }: { version: SchemePackVersion; onSaved: Saved }) {
  const [departments, setDepartments] = useState<DepartmentWithSources[]>([]);
  const [packs, setPacks] = useState<string[]>(version.sourcePacks);
  const [codes, setCodes] = useState<SchemeCodeCrosswalk[]>(version.details?.codeCrosswalks ?? []);
  const [error, setError] = useState("");
  useEffect(() => { void getJson<DepartmentWithSources[]>("/api/v1/departments").then(setDepartments).catch(() => setDepartments([])); }, []);
  const allPacks = departments.flatMap((d) => d.sourcePacks.map((p) => ({ packId: p.packId ?? "", department: d.name ?? "" })));
  const save = async () => {
    setError("");
    const draft = toDraft(version);
    try { onSaved(await saveDraft({ ...draft, sourcePacks: packs, details: { ...(draft.details ?? emptyDetails()), codeCrosswalks: codes.filter((c) => c.packId && c.sourceCode) } }, version)); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Unable to save"); }
  };
  return <section><h2>2 · Sources and code crosswalks</h2><ReadOnlyNote version={version} />{error && <p role="alert">{error}</p>}
    <fieldset><legend>Source Packs</legend>{allPacks.map((p) => <label key={p.packId}><input type="checkbox" checked={packs.includes(p.packId)} onChange={() => setPacks(packs.includes(p.packId) ? packs.filter((x) => x !== p.packId) : [...packs, p.packId])} />{p.packId} ({p.department})</label>)}</fieldset>
    <h3>Code crosswalks</h3>
    {codes.map((code, index) => <div key={index}>
      <select aria-label={`Crosswalk pack ${index + 1}`} value={code.packId ?? ""} onChange={(e) => setCodes(codes.map((c, i) => i === index ? { ...c, packId: e.target.value } : c))}><option value="">Source Pack</option>{allPacks.map((p) => <option key={p.packId} value={p.packId}>{p.packId}</option>)}</select>
      <input aria-label={`Source code ${index + 1}`} value={code.sourceCode ?? ""} onChange={(e) => setCodes(codes.map((c, i) => i === index ? { ...c, sourceCode: e.target.value } : c))} />
      <button onClick={() => setCodes(codes.filter((_, i) => i !== index))}>Remove code</button>
    </div>)}
    <button onClick={() => setCodes([...codes, { packId: "", sourceCode: "" }])}>Add code</button>
    {version.status === "DRAFT" && <button onClick={() => void save()}>Save sources</button>}
  </section>;
}

/** Step 3: the eligibility rule set, written in the Rules Studio editor or drafted from the GO; linking needs a DRAFT. */
export function EligibilityStep({ version, onSaved }: { version: SchemePackVersion; onSaved: Saved }) {
  const [rules, setRules] = useState<RuleSetSummary[]>([]);
  const [error, setError] = useState("");
  const schemeId = version.schemeId ?? "";
  const load = () => void getJson<RuleSetSummary[]>("/api/rule-sets?type=ELIGIBILITY").then((all) => setRules(all.filter((r) => r.ownerId === schemeId))).catch(() => setRules([]));
  useEffect(load, [schemeId]);
  const link = async (ruleSetId: string | null) => {
    setError("");
    try { onSaved(await saveDraft({ ...toDraft(version), eligibilityRuleSetId: ruleSetId }, version)); load(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Unable to link the rule set"); }
  };
  const linked = rules.find((r) => r.id === version.eligibilityRuleSetId);
  const draft = version.status === "DRAFT";
  return <section><h2>3 · Eligibility</h2><ReadOnlyNote version={version} />{error && <p role="alert">{error}</p>}
    <p>Linked rule set: {linked ? `v${linked.version} (${linked.status ?? ""})` : version.eligibilityRuleSetId ?? "none"}</p>
    {linked && linked.requiredAttributes.length > 0 && <p>Reads: {linked.requiredAttributes.join(", ")}</p>}
    <label>Eligibility rule set<select disabled={!draft} value={version.eligibilityRuleSetId ?? ""} onChange={(e) => void link(e.target.value || null)}><option value="">Not configured</option>{rules.map((r) => <option key={r.id} value={r.id ?? ""}>v{r.version} ({r.status})</option>)}</select></label>
    {draft && <><RuleSetDraftForm fields={[]} initial={{ type: "ELIGIBILITY", ownerType: "SCHEME", ownerId: schemeId }} onSaved={(rule) => void link(rule.id)} />
      <GoDraftButton schemeId={schemeId} versionId={version.id ?? ""} onDrafted={() => { load(); void getJson<SchemePackVersion[]>(`/api/scheme-packs/${encodeURIComponent(schemeId)}/versions`).then((all) => { const fresh = all.find((v) => v.id === version.id); if (fresh) onSaved(fresh); }); }} /></>}
  </section>;
}

/** Step 4: for each attribute the rules read, which sources supply it and how many Golden entities have it. */
export function AvailabilityStep({ version }: { version: SchemePackVersion }) {
  const [data, setData] = useState<SchemeDataAvailability | null>(null);
  const [error, setError] = useState("");
  useEffect(() => { void getJson<SchemeDataAvailability>(`/api/scheme-packs/${encodeURIComponent(version.schemeId ?? "")}/versions/${version.id ?? ""}/data-availability`).then(setData).catch((e: unknown) => setError(e instanceof Error ? e.message : "Unable to load")); }, [version.schemeId, version.id]);
  if (error) return <p role="alert">{error}</p>;
  if (!data) return <p>Loading data availability…</p>;
  return <section><h2>4 · Data availability</h2><p>Population: {data.population} ({data.level})</p>
    {data.attributes.length === 0 ? <p>No eligibility rule set is linked, so no attribute is required yet.</p> :
      <table><thead><tr><th>Attribute</th><th>Supplied by</th><th>With a value</th></tr></thead><tbody>
        {data.attributes.map((a) => <tr key={a.attribute}><td>{a.attribute}</td><td>{a.suppliedBy.length ? a.suppliedBy.join(", ") : "No source maps it"}</td><td>{a.withValue} of {a.population} ({a.percent}%)</td></tr>)}
      </tbody></table>}
  </section>;
}

/** Step 5: try the linked rule set (drafts included) on generated boundary and missing-data cases or own records. */
export function TestStep({ version }: { version: SchemePackVersion }) {
  if (!version.eligibilityRuleSetId) return <section><h2>5 · Test</h2><p>Link an eligibility rule set first.</p></section>;
  return <section><h2>5 · Test</h2><RuleTestPanel type="ELIGIBILITY" ownerType="SCHEME" ownerId={version.schemeId ?? ""} ruleSetId={version.eligibilityRuleSetId} /></section>;
}

/** Step 6: the whole Golden population against this version, by district; it changes nothing. */
export function ImpactStep({ version }: { version: SchemePackVersion }) {
  const [data, setData] = useState<SchemeImpact | null>(null);
  const [error, setError] = useState("");
  const run = async () => { setError(""); try { setData(await getJson<SchemeImpact>(`/api/scheme-packs/${encodeURIComponent(version.schemeId ?? "")}/versions/${version.id ?? ""}/impact`)); } catch (e) { setError(e instanceof Error ? e.message : "Unable to run"); } };
  const totals = data?.totals;
  return <section><h2>6 · Impact preview</h2><button onClick={() => void run()}>Run impact preview</button>{error && <p role="alert">{error}</p>}
    {data && totals && <>
      <p>Evaluated {data.evaluated}: eligible {totals.eligible}, enrolled {totals.enrolled}, eligible not enrolled {totals.eligibleNotEnrolled}, enrolled not eligible {totals.enrolledNotEligible}, cannot determine {totals.indeterminate}, exclusivity overlaps {data.exclusivityOverlaps}</p>
      <table><thead><tr><th>District</th><th>Eligible</th><th>Enrolled</th><th>Eligible not enrolled</th><th>Enrolled not eligible</th></tr></thead><tbody>
        {Object.entries(data.byDistrict).map(([district, c]) => <tr key={district}><td>{district}</td><td>{c.eligible}</td><td>{c.enrolled}</td><td>{c.eligibleNotEnrolled}</td><td>{c.enrolledNotEligible}</td></tr>)}
      </tbody></table></>}
  </section>;
}

/** Step 7: maker-checker. The linked rule set must be approved before the version; the approver is someone else. */
export function SubmitStep({ version, canApprove, onSaved }: { version: SchemePackVersion; canApprove: boolean; onSaved: Saved }) {
  const [rule, setRule] = useState<RuleSetSummary | null>(null);
  const [error, setError] = useState("");
  useEffect(() => { if (version.eligibilityRuleSetId) void getJson<RuleSetSummary[]>("/api/rule-sets?type=ELIGIBILITY").then((all) => setRule(all.find((r) => r.id === version.eligibilityRuleSetId) ?? null)).catch(() => setRule(null)); }, [version.eligibilityRuleSetId]);
  const act = async (action: "submit" | "approve" | "reject", body?: unknown) => {
    setError("");
    const response = await sendJson(`/api/scheme-packs/${encodeURIComponent(version.schemeId ?? "")}/versions/${version.id ?? ""}/${action}`, "POST", body);
    if (!response.ok) { setError((await failure(response, `Unable to ${action}`)).message); return; }
    onSaved(await response.json() as SchemePackVersion);
  };
  const reject = () => { const reason = window.prompt("Reason for rejecting this Scheme Pack version"); if (reason) void act("reject", { reason }); };
  return <section><h2>7 · Submit for approval</h2>
    <p>Version {version.version}: {version.status}</p>
    <p>Eligibility rule set: {version.eligibilityRuleSetId ? (rule ? `v${rule.version} ${rule.status ?? ""}` : "linked") : "none"}{rule && rule.status !== "APPROVED" && " (it must be approved in the Rules Studio first)"}</p>
    {error && <p role="alert">{error}</p>}
    {version.status === "DRAFT" && <button onClick={() => void act("submit")}>Submit for approval</button>}
    {version.status === "PENDING_APPROVAL" && canApprove && <><button onClick={() => void act("approve", {})}>Approve</button><button onClick={reject}>Reject</button></>}
  </section>;
}

/** Step 8: every version with its status and dates; a new draft version starts from any of them. */
export function VersionsStep({ versions, onCopied }: { versions: SchemePackVersion[]; onCopied: Saved }) {
  const [error, setError] = useState("");
  const copy = async (version: SchemePackVersion) => {
    setError("");
    const response = await sendJson(`/api/scheme-packs/${encodeURIComponent(version.schemeId ?? "")}/versions/${version.id ?? ""}/copy`, "POST");
    if (!response.ok) { setError((await failure(response, "Unable to create a new version")).message); return; }
    onCopied(await response.json() as SchemePackVersion);
  };
  return <section><h2>8 · Published versions</h2>{error && <p role="alert">{error}</p>}
    <table><thead><tr><th>Version</th><th>Status</th><th>Effective</th><th>GO reference</th><th /></tr></thead><tbody>
      {versions.map((v) => <tr key={v.id}><td>v{v.version}</td><td>{v.status}</td><td>{v.effectiveFrom} – {v.effectiveTo ?? "open"}</td><td>{v.details?.goReference ?? ""}</td><td><button onClick={() => void copy(v)}>New version from v{v.version}</button></td></tr>)}
    </tbody></table>
  </section>;
}
