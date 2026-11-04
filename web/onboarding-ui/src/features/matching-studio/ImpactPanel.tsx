import { useState } from "react";
import { Button, QueryState } from "../../shared/components";
import { useImpactPreview, useProfiles } from "./hooks";
import type { Profile } from "./types";

function Stat({ label, value, testId }: { label: string; value: string | number; testId: string }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd data-testid={testId}>{value}</dd>
    </div>
  );
}

/** What a trained profile would change compared with the model in use. Nothing is written. */
export function ImpactPanel({ thresholds }: { thresholds?: { auto: number; possible: number } | null }) {
  const profiles = useProfiles();
  const preview = useImpactPreview();
  const [selected, setSelected] = useState("");
  const impact = preview.data;
  return (
    <section aria-labelledby="impact-title">
      <h2 id="impact-title">Impact preview</h2>
      <p>
        A dry run against the last full matching run. Decisions stewards already
        made always override the model, and nothing here changes any data.
      </p>
      <QueryState query={profiles} isEmpty={(l) => l.length === 0} emptyText="No profiles to preview.">
        {(list: Profile[]) => (
          <div className="row">
            <label>
              Profile{" "}
              <select value={selected} onChange={(e) => setSelected(e.target.value)} aria-label="Profile to preview">
                <option value="">Choose a trained profile</option>
                {list
                  .filter((p) => p.trainedAt !== null)
                  .map((p) => (
                    <option key={p.profileId} value={p.profileId ?? ""}>
                      {p.name} v{p.version} ({p.status})
                    </option>
                  ))}
              </select>
            </label>
            <Button
              disabled={selected === "" || preview.isPending}
              onClick={() =>
                preview.mutate({
                  id: selected,
                  autoMatch: thresholds?.auto ?? null,
                  possibleMatch: thresholds?.possible ?? null,
                })
              }
            >
              Run preview
            </Button>
            {thresholds && (
              <span>
                using thresholds {thresholds.auto} / {thresholds.possible}
              </span>
            )}
          </div>
        )}
      </QueryState>
      {preview.isError && <p role="alert">The preview could not be computed (is there a completed full matching run?).</p>}
      {impact && (
        <>
          <p role="status">Preview only: nothing was changed. Compared with run using {impact.baselineModel}.</p>
          <dl>
            <Stat label="New merges" value={impact.newMerges} testId="impact-merges" />
            <Stat label="Splits" value={impact.splits} testId="impact-splits" />
            <Stat label="Golden IDs affected" value={impact.goldenIdsAffected} testId="impact-goldens" />
            <Stat
              label="Review queue"
              value={`${impact.baselineReviewQueue} → ${impact.candidateReviewQueue}`}
              testId="impact-queue"
            />
            <Stat
              label="Automatic matches"
              value={`${impact.baselineAutoPairs} → ${impact.candidateAutoPairs}`}
              testId="impact-auto"
            />
            <Stat label="Steward decisions respected" value={impact.constraintsApplied} testId="impact-constraints" />
          </dl>
          <table>
            <caption>
              Sample of changed pairs ({impact.changedPairs.length} of {impact.changedPairCount})
            </caption>
            <thead>
              <tr>
                <th scope="col">Pair</th>
                <th scope="col">Now</th>
                <th scope="col">With this profile</th>
              </tr>
            </thead>
            <tbody>
              {impact.changedPairs.map((p) => (
                <tr key={`${p.recordA}/${p.recordB}`}>
                  <td>
                    {p.nameA ?? "—"} / {p.nameB ?? "—"}
                  </td>
                  <td>
                    {p.baselineDecision} {p.baselineProbability?.toFixed(2) ?? ""}
                  </td>
                  <td>
                    {p.candidateDecision} {p.candidateProbability?.toFixed(2) ?? ""}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}
