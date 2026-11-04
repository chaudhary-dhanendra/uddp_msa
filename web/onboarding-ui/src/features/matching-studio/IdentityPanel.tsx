import { useState } from "react";
import type { IdentityCoverage, IdentityPolicy, IdentityRecommendation } from "../../api/types";
import { Button, QueryState } from "../../shared/components";
import { useSession } from "../auth";
import {
  useIdentityCoverage,
  useIdentityPreview,
  useIdentityRecommendation,
  useSubmitIdentityPolicy,
} from "./hooks";

function Stat({ label, value, testId }: { label: string; value: string | number; testId: string }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd data-testid={testId}>{value}</dd>
    </div>
  );
}

function list(values: string[]): string {
  return values.length === 0 ? "none" : values.join(", ");
}

function CoverageTable({ coverage }: { coverage: IdentityCoverage }) {
  const types = coverage.sources[0]?.types.map((t) => t.type) ?? [];
  return (
    <table>
      <caption>Identifier coverage per source (present / invalid / duplicated)</caption>
      <thead>
        <tr>
          <th scope="col">Source</th>
          <th scope="col">Records</th>
          {types.map((t) => (
            <th scope="col" key={t}>
              {t}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {coverage.sources.map((s) => (
          <tr key={`${s.stateCode}/${s.sourceSystem}`}>
            <th scope="row">
              {s.stateCode} / {s.sourceSystem}
            </th>
            <td>{s.records}</td>
            {s.types.map((t) => (
              <td key={t.type} data-testid="identity-coverage-cell">
                {t.presentPercent}% / {t.invalidPercent}% / {t.duplicatedPercent}%
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * The Identity step: identifier coverage per source, a policy recommended from it, a dry run of that policy through
 * the evaluator the Golden build uses, and submission for a second person's approval.
 */
export function IdentityPanel() {
  const session = useSession();
  const coverage = useIdentityCoverage();
  const recommendation = useIdentityRecommendation();
  const preview = useIdentityPreview();
  const submit = useSubmitIdentityPolicy();
  const [policy, setPolicy] = useState<IdentityPolicy | null>(null);
  const impact = preview.data;
  return (
    <section aria-labelledby="identity-title">
      <h2 id="identity-title">Identity</h2>
      <p>
        Which identifiers each source carries, and how they decide merges. A policy
        takes effect only after a second person approves it.
      </p>
      <QueryState query={coverage} isEmpty={(c) => c.sources.length === 0} emptyText="No canonical Residents yet.">
        {(c: IdentityCoverage) => <CoverageTable coverage={c} />}
      </QueryState>
      <QueryState query={recommendation}>
        {(r: IdentityRecommendation) => {
          const current = policy ?? r.policy;
          return (
            <>
              <h3>Recommended policy</h3>
              {current && (
                <dl>
                  <Stat label="Same value auto-matches" value={list(current.deterministicTypes)} testId="identity-deterministic" />
                  <Stat label="Different values never merge" value={list(current.neverMergeTypes)} testId="identity-never-merge" />
                  <Stat label="Matching evidence" value={list(current.evidenceTypes)} testId="identity-evidence" />
                  <Stat label="Family anchor" value={current.familyAnchorType ?? "none"} testId="identity-anchor" />
                  <Stat label="Base source" value={current.baseSource ?? "none"} testId="identity-base" />
                </dl>
              )}
              <ul>
                {r.reasons.map((reason) => (
                  <li key={reason}>{reason}</li>
                ))}
              </ul>
              {r.warnings.map((w) => (
                <p role="alert" key={w}>
                  {w}
                </p>
              ))}
              {current && (
                <div className="row">
                  <Button
                    disabled={preview.isPending}
                    onClick={() => {
                      setPolicy(current);
                      preview.mutate(current);
                    }}
                  >
                    Preview impact
                  </Button>
                  {session.can("IDENTITY_MANAGE") && (
                    <Button disabled={submit.isPending || !impact} onClick={() => submit.mutate(current)}>
                      Submit for approval
                    </Button>
                  )}
                </div>
              )}
            </>
          );
        }}
      </QueryState>
      {preview.isError && <p role="alert">The preview could not be computed.</p>}
      {impact && (
        <>
          <p role="status">Preview only: nothing was changed.</p>
          <dl>
            <Stat label="Deterministic merges" value={impact.autoMerges} testId="identity-merges" />
            <Stat label="Merges matching had not made" value={impact.newAutoMerges} testId="identity-new-merges" />
            <Stat label="Conflicts for review" value={impact.conflicts} testId="identity-conflicts" />
            <Stat label="Never-merge pairs" value={impact.neverMerge} testId="identity-never" />
            <Stat label="Automatic matches blocked" value={impact.blockedAutoMatches} testId="identity-blocked" />
            <Stat label="Probabilistic only" value={impact.probabilisticRemainder} testId="identity-probabilistic" />
          </dl>
          {impact.warnings.map((w) => (
            <p role="alert" key={w}>
              {w}
            </p>
          ))}
        </>
      )}
      {submit.isSuccess && (
        <p role="status" data-testid="identity-submitted">
          Version {submit.data.version} submitted for approval.
        </p>
      )}
      {submit.isError && <p role="alert">The policy could not be submitted.</p>}
    </section>
  );
}
