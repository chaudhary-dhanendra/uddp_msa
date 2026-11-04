import { Link, useNavigate, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import type { SchemePackVersion } from "../../../api/types";
import { useSession } from "../../auth";
import { STEPS, useSchemeVersions } from "./model";
import { AvailabilityStep, EligibilityStep, ImpactStep, ProfileStep, SourcesStep, SubmitStep, TestStep, VersionsStep } from "./steps";

const stepPath = (version: SchemePackVersion, step: number) =>
  `/scheme-studio/${encodeURIComponent(version.schemeId ?? "")}/${version.id ?? ""}/wizard/${step}`;

/** Scheme Studio (A7), step 1 for a scheme that has no version yet. */
export function SchemeWizardNewPage() {
  const nav = useNavigate();
  return <main><h1>New scheme</h1><ProfileStep onSaved={(saved) => nav(stepPath(saved, 2))} /></main>;
}

/** Scheme Studio (A7): eight steps over one Scheme Pack version. */
export function SchemeWizardPage() {
  const { schemeId = "", versionId = "", step = "1" } = useParams();
  const nav = useNavigate();
  const queries = useQueryClient();
  const session = useSession();
  const versions = useSchemeVersions(schemeId);
  const version = versions.data?.find((v) => v.id === versionId);
  const current = Math.min(Math.max(Number(step) || 1, 1), STEPS.length);
  const saved = (next: SchemePackVersion) => {
    void queries.invalidateQueries({ queryKey: ["scheme-pack", schemeId] });
    if (next.id !== versionId) nav(stepPath(next, 1));
  };
  if (versions.isError) return <main><p role="alert">Unable to load the scheme.</p></main>;
  if (!version) return <main><p>Loading scheme…</p></main>;
  return <main><h1>{version.name} · v{version.version} ({version.status})</h1>
    <nav aria-label="Scheme Studio steps"><ol>{STEPS.map((label, index) => <li key={label}>{index + 1 === current ? <strong>{index + 1}. {label}</strong> : <Link to={stepPath(version, index + 1)}>{index + 1}. {label}</Link>}</li>)}</ol></nav>
    {current === 1 && <ProfileStep key={version.id} version={version} onSaved={saved} />}
    {current === 2 && <SourcesStep key={version.id} version={version} onSaved={saved} />}
    {current === 3 && <EligibilityStep version={version} onSaved={saved} />}
    {current === 4 && <AvailabilityStep version={version} />}
    {current === 5 && <TestStep version={version} />}
    {current === 6 && <ImpactStep version={version} />}
    {current === 7 && <SubmitStep version={version} canApprove={session.can("RULES_APPROVE")} onSaved={saved} />}
    {current === 8 && <VersionsStep versions={versions.data ?? []} onCopied={saved} />}
    <p>{current > 1 && <Link to={stepPath(version, current - 1)}>Back</Link>} {current < STEPS.length && <Link to={stepPath(version, current + 1)}>Next</Link>}</p>
  </main>;
}
