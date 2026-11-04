import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { PageHeader } from "../../shared/components";
import { useActiveModel } from "./hooks";
import { IdentityPanel } from "./IdentityPanel";
import { ImpactPanel } from "./ImpactPanel";
import { LabellingPanel } from "./LabellingPanel";
import { MonitoringPanel } from "./MonitoringPanel";
import { ProfilesPanel } from "./ProfilesPanel";
import { ReadinessPanel } from "./ReadinessPanel";
import { ThresholdPanel } from "./ThresholdPanel";

const TABS = [
  { id: "readiness", label: "Data readiness" },
  { id: "identity", label: "Identity" },
  { id: "profiles", label: "Profiles" },
  { id: "labels", label: "Label pairs" },
  { id: "thresholds", label: "Thresholds" },
  { id: "impact", label: "Impact preview" },
  { id: "monitoring", label: "Monitoring" },
] as const;
type TabId = (typeof TABS)[number]["id"];

function isTab(value: string | null): value is TabId {
  return TABS.some((t) => t.id === value);
}

/** Matching Studio: prepare data, tune a profile, check it against steward labels, preview its impact, monitor it. */
export function MatchingStudioPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get("tab");
  const tab: TabId = isTab(requested) ? requested : "readiness";
  const [thresholds, setThresholds] = useState<{ auto: number; possible: number } | null>(null);
  const active = useActiveModel();
  return (
    <>
      <PageHeader
        title="Matching Studio"
        description={`Model in use: ${active.data?.modelVersion ?? "…"}. Changes need a second person's approval.`}
      />
      <nav aria-label="Matching Studio sections">
        <ul className="row" style={{ listStyle: "none", padding: 0 }}>
          {TABS.map((t) => (
            <li key={t.id}>
              <button
                type="button"
                aria-current={t.id === tab ? "page" : undefined}
                onClick={() => setParams({ tab: t.id })}
              >
                {t.label}
              </button>
            </li>
          ))}
        </ul>
      </nav>
      {tab === "readiness" && <ReadinessPanel />}
      {tab === "identity" && <IdentityPanel />}
      {tab === "profiles" && <ProfilesPanel />}
      {tab === "labels" && <LabellingPanel />}
      {tab === "thresholds" && (
        <ThresholdPanel
          onUse={(auto, possible) => {
            setThresholds({ auto, possible });
            setParams({ tab: "impact" });
          }}
        />
      )}
      {tab === "impact" && <ImpactPanel thresholds={thresholds} />}
      {tab === "monitoring" && <MonitoringPanel />}
    </>
  );
}

export { WhyMatchedPage } from "./WhyMatchedPage";
