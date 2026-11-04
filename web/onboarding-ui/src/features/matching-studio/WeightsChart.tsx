import type { LevelWeight } from "./types";

const MAX_BAR = 15;

/**
 * Match weight per field, in plain language. A bar to the right makes "same person" more likely, to the left less
 * likely; each doubling of the odds is one point of weight.
 */
export function WeightsChart({ weights }: { weights: LevelWeight[] }) {
  if (weights.length === 0) {
    return <p>No weights yet: train the profile first.</p>;
  }
  const fields = [...new Set(weights.map((w) => w.field))];
  return (
    <div aria-label="Match weight per field">
      {fields.map((field) => (
        <figure key={field} data-testid={`weights-${field}`}>
          <figcaption>
            <strong>{field.replace(/_/g, " ")}</strong>
          </figcaption>
          <ul style={{ listStyle: "none", padding: 0 }}>
            {weights
              .filter((w) => w.field === field)
              .map((w) => {
                const width = Math.min(50, (Math.abs(w.matchWeight) / MAX_BAR) * 50);
                const positive = w.matchWeight >= 0;
                return (
                  <li key={w.level} style={{ margin: "4px 0" }}>
                    <div
                      aria-hidden="true"
                      style={{ display: "flex", height: 10, background: "var(--surface-2, #eee)" }}
                    >
                      <div style={{ width: `${positive ? 50 : 50 - width}%` }} />
                      <div
                        style={{
                          width: `${width}%`,
                          background: positive ? "var(--ok, #1a7f4b)" : "var(--danger, #b3261e)",
                        }}
                      />
                    </div>
                    <span>
                      {w.plainLanguage} ({w.matchWeight >= 0 ? "+" : ""}
                      {w.matchWeight.toFixed(1)})
                    </span>
                  </li>
                );
              })}
          </ul>
        </figure>
      ))}
    </div>
  );
}
