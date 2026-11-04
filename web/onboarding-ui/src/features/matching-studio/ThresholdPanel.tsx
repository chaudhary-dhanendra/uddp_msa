import { useState } from "react";
import { useThresholdSuggestion } from "./hooks";
import { Button } from "../../shared/components";
import { readSuggestion, type Suggestion } from "./types";

const TARGETS = [0.95, 0.98, 0.99, 0.995];
const W = 480;
const H = 220;
const PAD = 36;

function x(threshold: number): number {
  return PAD + threshold * (W - 2 * PAD);
}
function y(value: number): number {
  return H - PAD - value * (H - 2 * PAD);
}

function line(points: Suggestion["curve"], pick: (p: Suggestion["curve"][number]) => number): string {
  return points.map((p, i) => `${i === 0 ? "M" : "L"}${x(p.threshold).toFixed(1)},${y(pick(p)).toFixed(1)}`).join(" ");
}

/** Precision and recall against the threshold, with the suggested AUTO_MATCH and review thresholds marked. */
export function ThresholdChart({ suggestion }: { suggestion: Suggestion }) {
  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      role="img"
      aria-label={`Precision and recall by threshold. At ${suggestion.autoMatch} precision is ${(suggestion.precisionAtAutoMatch * 100).toFixed(1)}%.`}
      style={{ maxWidth: "100%" }}
    >
      <line x1={PAD} y1={H - PAD} x2={W - PAD} y2={H - PAD} stroke="currentColor" />
      <line x1={PAD} y1={PAD} x2={PAD} y2={H - PAD} stroke="currentColor" />
      <path d={line(suggestion.curve, (p) => p.precision)} fill="none" stroke="var(--accent, #1d4ed8)" strokeWidth="2" />
      <path
        d={line(suggestion.curve, (p) => p.recall)}
        fill="none"
        stroke="var(--warn, #b45309)"
        strokeWidth="2"
        strokeDasharray="5 3"
      />
      <line x1={x(suggestion.autoMatch)} y1={PAD} x2={x(suggestion.autoMatch)} y2={H - PAD} stroke="var(--ok, #1a7f4b)" />
      <line x1={x(suggestion.possibleMatch)} y1={PAD} x2={x(suggestion.possibleMatch)} y2={H - PAD} stroke="var(--muted, #6b7280)" />
      <text x={PAD} y={H - 8} fontSize="11">0</text>
      <text x={W - PAD - 6} y={H - 8} fontSize="11">1</text>
      <text x={x(suggestion.autoMatch) + 3} y={PAD + 10} fontSize="11">auto</text>
      <text x={x(suggestion.possibleMatch) + 3} y={PAD + 22} fontSize="11">review</text>
      <text x={W - PAD - 150} y={PAD - 12} fontSize="11">solid: precision · dashed: recall</text>
    </svg>
  );
}

/** Suggests thresholds for a target precision from the steward labels. */
export function ThresholdPanel({ onUse }: { onUse?: (auto: number, possible: number) => void }) {
  const [target, setTarget] = useState(0.99);
  const [asked, setAsked] = useState(false);
  const query = useThresholdSuggestion(target, asked);
  const suggestion = query.data === undefined ? null : readSuggestion(query.data);
  return (
    <section aria-labelledby="thresholds-title">
      <h2 id="thresholds-title">Thresholds</h2>
      <p>Choose how sure the system must be before it merges two records without a person looking.</p>
      <div className="row">
        <label>
          Target precision{" "}
          <select
            value={target}
            onChange={(e) => setTarget(Number(e.target.value))}
            aria-label="Target precision"
          >
            {TARGETS.map((t) => (
              <option key={t} value={t}>
                {(t * 100).toFixed(1)}% of automatic merges correct
              </option>
            ))}
          </select>
        </label>
        <Button onClick={() => setAsked(true)}>Suggest thresholds</Button>
      </div>
      {query.isError && <p role="alert">No suggestion yet: label some pairs first.</p>}
      {suggestion && (
        <>
          <ThresholdChart suggestion={suggestion} />
          <ul data-testid="threshold-summary">
            <li>
              AUTO_MATCH from <strong>{suggestion.autoMatch}</strong> (about{" "}
              {suggestion.expectedAutoMatchPairs.toLocaleString()} pairs, estimated precision{" "}
              {(suggestion.precisionAtAutoMatch * 100).toFixed(1)}%)
            </li>
            <li>
              Review band <strong>{suggestion.possibleMatch}</strong> to {suggestion.autoMatch}: expected review queue{" "}
              <strong>{suggestion.expectedReviewQueue.toLocaleString()}</strong> pairs
            </li>
            <li>
              Estimated missed matches below the review band:{" "}
              <strong>{Math.round(suggestion.estimatedMissedMatches).toLocaleString()}</strong>
            </li>
            <li>Based on {suggestion.labelsUsed} labels.</li>
          </ul>
          {onUse && (
            <Button variant="secondary" onClick={() => onUse(suggestion.autoMatch, suggestion.possibleMatch)}>
              Preview these thresholds
            </Button>
          )}
        </>
      )}
    </section>
  );
}
