import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import type { RuleSetSummary, SchemePackDraft, SchemePackVersion } from "../../api/types";
import { Button, StatusBadge } from "../../shared/components";
import { GoDraftButton } from "./GoDraft";
import { emptyDetails, getJson, saveDraft, toDraft } from "./wizard/model";
import styles from "./schemes.module.css";

type Department = { departmentCode: string; name: string };
type SourcePack = { packId: string; departmentCode?: string | null; status?: string | null };
type FieldErrors = Partial<Record<"schemeId" | "name" | "owner" | "effectiveFrom" | "effectiveTo", string>>;

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

const newDraft = (): SchemePackDraft => ({
  schemeId: "", name: "", owner: "", beneficiaryLevel: "INDIVIDUAL", benefitType: "ENTITLEMENT", benefitForm: "CASH",
  effectiveFrom: "", effectiveTo: null, eligibilityRuleSetId: null, implementingDepartments: [], sourcePacks: [],
  exclusivityGroups: [], configuration: "{}", details: emptyDetails(),
});

/** How a rule set is named everywhere in the Studio: its owner, version and status, e.g. "WIDOW-PENSION v3 (APPROVED)". */
export const ruleLabel = (rule: RuleSetSummary) => `${rule.ownerId ?? ""} v${rule.version} (${rule.status ?? ""})`;

/** Rule sets owned by this scheme first (newest version first), then every other eligibility rule set. */
function orderRules(rules: RuleSetSummary[], schemeId: string) {
  const own = rules.filter((r) => r.ownerId === schemeId).sort((a, b) => b.version - a.version);
  const other = rules.filter((r) => r.ownerId !== schemeId)
    .sort((a, b) => (a.ownerId ?? "").localeCompare(b.ownerId ?? "") || b.version - a.version);
  return { own, other };
}

function validate(draft: SchemePackDraft): FieldErrors {
  const errors: FieldErrors = {};
  if (!draft.schemeId?.trim()) errors.schemeId = "Enter a scheme key, for example WIDOW-PENSION.";
  if (!draft.name?.trim()) errors.name = "Enter the scheme name.";
  if (!draft.owner) errors.owner = "Choose the owning Department.";
  if (!draft.effectiveFrom) errors.effectiveFrom = "Choose the date this version takes effect.";
  else if (!ISO_DATE.test(draft.effectiveFrom)) errors.effectiveFrom = "Use the format YYYY-MM-DD.";
  if (draft.effectiveTo && !ISO_DATE.test(draft.effectiveTo)) errors.effectiveTo = "Use the format YYYY-MM-DD.";
  else if (draft.effectiveTo && draft.effectiveFrom && draft.effectiveTo < draft.effectiveFrom) errors.effectiveTo = "Must be on or after the effective-from date.";
  return errors;
}

function Labelled({ id, label, optional, hint, error, children }: { id: string; label: string; optional?: boolean; hint?: string; error?: string; children: ReactNode }) {
  return <div className={styles.label}>
    <label htmlFor={id}>{label}{optional && <span className={styles.optional}> (optional)</span>}</label>
    {children}
    {hint && <span className={styles.hint} id={`${id}-hint`}>{hint}</span>}
    {error && <span className={styles.fieldError} id={`${id}-error`}>{error}</span>}
  </div>;
}

/**
 * Create Scheme Draft (no version yet) or edit a DRAFT version, e.g. the one "Create New Version" just made. The draft
 * keeps the version's details (GO reference, amount, code crosswalks) it does not show, so saving never drops them.
 */
export function SchemeAuthoringPage() {
  const nav = useNavigate();
  const { schemeId, versionId } = useParams();
  const editMode = Boolean(schemeId && versionId);
  const [draft, setDraft] = useState<SchemePackDraft>(newDraft);
  const [version, setVersion] = useState<SchemePackVersion | null>(null);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [sources, setSources] = useState<SourcePack[]>([]);
  const [rules, setRules] = useState<RuleSetSummary[]>([]);
  const [loading, setLoading] = useState(editMode);
  const [error, setError] = useState("");
  const [errors, setErrors] = useState<FieldErrors>({});
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    void Promise.all([
      getJson<Department[]>("/api/v1/departments").catch(() => []),
      getJson<SourcePack[]>("/api/v1/source-packs").catch(() => []),
      getJson<RuleSetSummary[]>("/api/rule-sets?type=ELIGIBILITY").catch(() => []),
    ]).then(([d, s, r]) => { setDepartments(d); setSources(s); setRules(r); });
  }, []);

  useEffect(() => {
    if (!editMode) return;
    setLoading(true);
    getJson<SchemePackVersion[]>(`/api/scheme-packs/${encodeURIComponent(schemeId ?? "")}/versions`)
      .then((versions) => {
        const found = versions.find((v) => v.id === versionId);
        if (!found) throw new Error("Scheme version not found.");
        setVersion(found);
        setDraft(toDraft(found));
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : "Unable to load the draft."))
      .finally(() => setLoading(false));
  }, [editMode, schemeId, versionId]);

  const key = (draft.schemeId ?? "").trim();
  const { own, other } = useMemo(() => orderRules(rules, key), [rules, key]);
  const selected = rules.find((r) => r.id === draft.eligibilityRuleSetId);
  const editable = !version || version.status === "DRAFT";
  const set = (change: Partial<SchemePackDraft>) => setDraft((d) => ({ ...d, ...change }));
  const toggle = (field: "implementingDepartments" | "sourcePacks", value: string) =>
    setDraft((d) => ({ ...d, [field]: d[field].includes(value) ? d[field].filter((v) => v !== value) : [...d[field], value] }));
  const details = draft.details ?? emptyDetails();

  const save = async () => {
    setError("");
    const found = validate(draft);
    setErrors(found);
    if (Object.keys(found).length) return;
    setSaving(true);
    try {
      const body: SchemePackDraft = {
        ...draft,
        schemeId: key,
        implementingDepartments: draft.implementingDepartments.length ? draft.implementingDepartments : [draft.owner ?? ""],
        details: { ...details, benefitType: draft.benefitType, benefitForm: draft.benefitForm },
      };
      const saved = await saveDraft(body, version);
      nav(`/scheme-studio/${encodeURIComponent(saved.schemeId ?? key)}/${saved.id ?? ""}`, { state: { saved: true } });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Unable to save the draft.");
    } finally {
      setSaving(false);
    }
  };

  const title = editMode ? `Edit scheme draft${version ? ` · ${version.name ?? version.schemeId} v${version.version}` : ""}` : "Create Scheme Draft";
  const back = editMode ? `/scheme-studio/${encodeURIComponent(schemeId ?? "")}/${versionId ?? ""}` : "/scheme-studio";
  const invalid = (k: keyof FieldErrors) => (errors[k] ? { "aria-invalid": true, "aria-describedby": `${k}-error` } : {});

  if (loading) return <main className={styles.page}><h1>{title}</h1><p className={styles.muted}>Loading the draft…</p></main>;
  if (editMode && version && !editable) {
    return <main className={styles.page}><h1>{title}</h1>
      <p className={styles.note} role="note">Version {version.version} is {version.status}, so it cannot be edited. Use Create New Version on the scheme to start a new draft from it.</p>
      <p><Link to={back}>Back to the scheme</Link></p></main>;
  }

  return <main className={styles.page}>
    <div>
      <p className={styles.muted}><Link to={back}>← {editMode ? "Back to the scheme" : "Scheme catalogue"}</Link></p>
      <div className={styles.titleRow}><h1>{title}</h1>{version && <StatusBadge status={version.status} />}</div>
      <p className={styles.muted}>A draft changes nothing until it is submitted and another user with approval rights approves it.</p>
    </div>
    {error && <p className={styles.alert} role="alert">{error}</p>}
    {Object.keys(errors).length > 0 && <p className={styles.alert} role="alert">Check the highlighted fields.</p>}

    <section className={styles.card} aria-labelledby="basic-heading">
      <h2 id="basic-heading">Basic information</h2>
      <p className={styles.muted}>The scheme key stays the same for every version of the scheme.</p>
      <div className={styles.grid2}>
        <Labelled id="schemeId" label="Scheme key" hint={editMode ? "Fixed for this scheme." : "Capitals and dashes, e.g. WIDOW-PENSION."} error={errors.schemeId}>
          <input id="schemeId" className={styles.input} value={draft.schemeId ?? ""} disabled={editMode} onChange={(e) => set({ schemeId: e.target.value.toUpperCase() })} {...invalid("schemeId")} />
        </Labelled>
        <Labelled id="name" label="Scheme name" error={errors.name}>
          <input id="name" className={styles.input} value={draft.name ?? ""} onChange={(e) => set({ name: e.target.value })} {...invalid("name")} />
        </Labelled>
      </div>
    </section>

    <section className={styles.card} aria-labelledby="ownership-heading">
      <h2 id="ownership-heading">Ownership</h2>
      <p className={styles.muted}>The owning Department is responsible for the scheme; implementing Departments deliver it.</p>
      <div className={styles.grid2}>
        <Labelled id="owner" label="Owning Department" error={errors.owner}>
          <select id="owner" className={styles.input} value={draft.owner ?? ""} onChange={(e) => set({ owner: e.target.value })} {...invalid("owner")}>
            <option value="">Select Department</option>
            {draft.owner && !departments.some((d) => d.departmentCode === draft.owner) && <option value={draft.owner}>{draft.owner}</option>}
            {departments.map((d) => <option key={d.departmentCode} value={d.departmentCode}>{d.name}</option>)}
          </select>
        </Labelled>
        <fieldset className={`${styles.checks} ${styles.full}`}>
          <legend>Implementing Departments <span className={styles.optional}>(the owning Department when none is ticked)</span></legend>
          <div className={styles.checkGrid}>
            {departments.length === 0 && <span className={styles.hint}>No Departments are configured.</span>}
            {departments.map((d) => <label key={d.departmentCode} className={styles.check}>
              <input type="checkbox" checked={draft.implementingDepartments.includes(d.departmentCode)} onChange={() => toggle("implementingDepartments", d.departmentCode)} />{d.name}
            </label>)}
          </div>
        </fieldset>
      </div>
    </section>

    <section className={styles.card} aria-labelledby="benefit-heading">
      <h2 id="benefit-heading">Beneficiary and benefit</h2>
      <p className={styles.muted}>Who receives the benefit and what it is.</p>
      <div className={styles.grid2}>
        <Labelled id="beneficiaryLevel" label="Beneficiary level">
          <select id="beneficiaryLevel" className={styles.input} value={draft.beneficiaryLevel ?? "INDIVIDUAL"} onChange={(e) => set({ beneficiaryLevel: e.target.value })}>
            <option value="INDIVIDUAL">Individual (Golden Resident)</option>
            <option value="HOUSEHOLD">Household (Golden Family)</option>
          </select>
        </Labelled>
        <Labelled id="benefitType" label="Benefit type" hint="For example ENTITLEMENT or SUBSIDY.">
          <input id="benefitType" className={styles.input} value={draft.benefitType ?? ""} onChange={(e) => set({ benefitType: e.target.value })} />
        </Labelled>
        <Labelled id="benefitForm" label="Benefit form" hint="For example CASH or IN_KIND.">
          <input id="benefitForm" className={styles.input} value={draft.benefitForm ?? ""} onChange={(e) => set({ benefitForm: e.target.value })} />
        </Labelled>
      </div>
    </section>

    <section className={styles.card} aria-labelledby="eligibility-heading">
      <h2 id="eligibility-heading">Eligibility</h2>
      <p className={styles.muted}>The eligibility rule set from Rules Studio that decides who qualifies. It must be approved before this version can be approved.</p>
      <Labelled id="eligibilityRule" label="Eligibility rule" optional>
        <select id="eligibilityRule" className={styles.input} value={draft.eligibilityRuleSetId ?? ""} onChange={(e) => set({ eligibilityRuleSetId: e.target.value || null })}>
          <option value="">Not configured</option>
          {own.length > 0 && <optgroup label={`Rules for ${key}`}>{own.map((r) => <option key={r.id} value={r.id ?? ""}>{ruleLabel(r)}</option>)}</optgroup>}
          {other.length > 0 && <optgroup label="Other eligibility rules">{other.map((r) => <option key={r.id} value={r.id ?? ""}>{ruleLabel(r)}</option>)}</optgroup>}
          {draft.eligibilityRuleSetId && !selected && <option value={draft.eligibilityRuleSetId}>{draft.eligibilityRuleSetId}</option>}
        </select>
      </Labelled>
      {selected && <div className={styles.ruleSummary} data-testid="selected-rule">
        <span><strong>{ruleLabel(selected)}</strong> <StatusBadge status={selected.status} /></span>
        <span className={styles.hint}>Reads: {selected.requiredAttributes.join(", ") || "no attributes"}{selected.effectiveFrom ? ` · rule effective from ${selected.effectiveFrom}` : ""}</span>
        {selected.status !== "APPROVED" && <p className={styles.warn}>This rule is {selected.status}: approve it in <Link to="/rules">Rules Studio</Link> before submitting the scheme.</p>}
      </div>}
      {editMode && editable && schemeId && versionId && <GoDraftButton schemeId={schemeId} versionId={versionId} onDrafted={(d) => {
        if (!d.ruleSetId) return;
        const id = d.ruleSetId;
        setRules((r) => [...r, { id, type: "ELIGIBILITY", ownerType: "SCHEME", ownerId: schemeId, version: d.version, status: d.status ?? "DRAFT", effectiveFrom: null, effectiveTo: null, requiredAttributes: d.requiredAttributes } as RuleSetSummary]);
        set({ eligibilityRuleSetId: id });
      }} />}
    </section>

    <section className={styles.card} aria-labelledby="data-heading">
      <h2 id="data-heading">Data dependencies and coordination</h2>
      <p className={styles.muted}>Source Packs the scheme reads, and exclusivity groups it shares with other schemes (a resident should hold only one benefit per group).</p>
      <div className={styles.grid2}>
        <fieldset className={`${styles.checks} ${styles.full}`}>
          <legend>Source Packs <span className={styles.optional}>(optional)</span></legend>
          <div className={styles.checkGrid}>
            {sources.length === 0 && <span className={styles.hint}>No Source Packs are registered.</span>}
            {sources.map((s) => <label key={s.packId} className={styles.check}>
              <input type="checkbox" checked={draft.sourcePacks.includes(s.packId)} onChange={() => toggle("sourcePacks", s.packId)} />{s.packId}{s.status ? <span className={styles.hint}> ({s.status})</span> : null}
            </label>)}
          </div>
        </fieldset>
        <Labelled id="exclusivityGroups" label="Exclusivity groups" optional hint="Comma-separated, e.g. SOCIAL-PENSION.">
          <input id="exclusivityGroups" className={styles.input} value={draft.exclusivityGroups.join(", ")} onChange={(e) => set({ exclusivityGroups: e.target.value.split(",").map((x) => x.trim()).filter(Boolean) })} />
        </Labelled>
      </div>
    </section>

    <section className={styles.card} aria-labelledby="validity-heading">
      <h2 id="validity-heading">Validity</h2>
      <p className={styles.muted}>When this version applies. Leave the end date empty for an open-ended version.</p>
      <div className={styles.grid2}>
        <Labelled id="effectiveFrom" label="Effective from" error={errors.effectiveFrom}>
          <input id="effectiveFrom" type="date" className={styles.input} value={draft.effectiveFrom ?? ""} onChange={(e) => set({ effectiveFrom: e.target.value })} {...invalid("effectiveFrom")} />
        </Labelled>
        <Labelled id="effectiveTo" label="Effective to" optional error={errors.effectiveTo}>
          <input id="effectiveTo" type="date" className={styles.input} value={draft.effectiveTo ?? ""} onChange={(e) => set({ effectiveTo: e.target.value || null })} {...invalid("effectiveTo")} />
        </Labelled>
      </div>
    </section>

    <div className={styles.footer}>
      <span className={styles.muted}>{editMode ? "Saving keeps this version as a DRAFT." : "Saving creates version 1 as a DRAFT."}</span>
      <Button variant="secondary" onClick={() => nav(back)}>Cancel</Button>
      <Button onClick={() => void save()} disabled={saving}>{saving ? "Saving…" : "Save Draft"}</Button>
    </div>
  </main>;
}
