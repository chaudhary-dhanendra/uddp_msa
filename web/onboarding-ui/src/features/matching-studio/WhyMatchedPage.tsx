import { Link, useParams } from "react-router-dom";
import { PageHeader, QueryState } from "../../shared/components";
import { useExplanation } from "./hooks";
import type { Explanation } from "./types";

const W = 520;
const ROW = 30;
const LABEL = 150;

/** Running total of match weight: the start, then each field's contribution, ending at the pair's total. */
export function Waterfall({ explanation }: { explanation: Explanation }) {
  const steps = explanation.steps;
  let running = 0;
  const bars = steps.map((s) => {
    const from = running;
    running += s.weight;
    return { step: s, from, to: running };
  });
  const all = bars.flatMap((b) => [b.from, b.to, 0]);
  const min = Math.min(...all);
  const max = Math.max(...all);
  const span = Math.max(max - min, 1);
  const scale = (v: number) => LABEL + ((v - min) / span) * (W - LABEL - 20);
  return (
    <svg
      viewBox={`0 0 ${W} ${(bars.length + 1) * ROW + 10}`}
      role="img"
      aria-label={`Match weight builds from ${bars[0]?.to.toFixed(1)} to ${explanation.matchWeight.toFixed(1)}`}
      style={{ maxWidth: "100%" }}
    >
      <line x1={scale(0)} x2={scale(0)} y1={0} y2={(bars.length + 1) * ROW} stroke="currentColor" strokeDasharray="3 3" />
      {bars.map((b, i) => {
        const left = Math.min(scale(b.from), scale(b.to));
        const width = Math.max(2, Math.abs(scale(b.to) - scale(b.from)));
        return (
          <g key={b.step.label} transform={`translate(0 ${i * ROW})`}>
            <text x={0} y={18} fontSize="12">
              {b.step.label}
            </text>
            <rect
              x={left}
              y={6}
              width={width}
              height={16}
              fill={b.step.weight >= 0 ? "var(--ok, #1a7f4b)" : "var(--danger, #b3261e)"}
            />
            <text x={left + width + 4} y={18} fontSize="11">
              {b.step.weight >= 0 ? "+" : ""}
              {b.step.weight.toFixed(1)}
            </text>
          </g>
        );
      })}
      <g transform={`translate(0 ${bars.length * ROW})`}>
        <text x={0} y={18} fontSize="12" fontWeight="bold">
          Total
        </text>
        <rect x={Math.min(scale(0), scale(explanation.matchWeight))} y={6} height={16}
          width={Math.max(2, Math.abs(scale(explanation.matchWeight) - scale(0)))} fill="var(--accent, #1d4ed8)" />
        <text x={scale(Math.max(0, explanation.matchWeight)) + 4} y={18} fontSize="11">
          {explanation.matchWeight.toFixed(1)}
        </text>
      </g>
    </svg>
  );
}

/** Why a scored pair matched (or did not), field by field, in plain language. */
export function WhyMatchedPage() {
  const { runId = "", a = "", b = "" } = useParams();
  const explanation = useExplanation(runId, a, b);
  return (
    <>
      <PageHeader
        title="Why this pair matched"
        description="Each field adds to or takes away from the evidence that two records are the same person."
      />
      <p>
        <Link to={`/matching-runs/${runId}`}>Back to the matching run</Link>
      </p>
      <QueryState query={explanation}>
        {(e: Explanation) => (
          <>
            <p data-testid="why-summary">
              {e.decision}: probability {e.matchProbability.toFixed(3)} (auto-match from {e.autoMatchThreshold}, review from{" "}
              {e.possibleMatchThreshold}) · model {e.modelVersion}
            </p>
            {e.hasBreakdown ? (
              <>
                <Waterfall explanation={e} />
                <ol>
                  {e.steps.map((s) => (
                    <li key={s.label}>
                      <strong>{s.label}</strong>
                      {s.valueA !== null || s.valueB !== null ? `: ${s.valueA ?? "—"} / ${s.valueB ?? "—"}` : ""} —{" "}
                      {s.plainLanguage}
                    </li>
                  ))}
                </ol>
              </>
            ) : (
              <p>
                This run used the built-in model, which does not record a per-field breakdown. Total match weight:{" "}
                {e.matchWeight.toFixed(1)}.
              </p>
            )}
          </>
        )}
      </QueryState>
    </>
  );
}
