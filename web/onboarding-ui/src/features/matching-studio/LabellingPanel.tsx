import { useState } from "react";
import { Button } from "../../shared/components";
import { useSession } from "../auth";
import { useLabel, useLabelSample, useLabelSummary } from "./hooks";
import { evidenceValue, type SamplePair } from "./types";

type Choice = "SAME" | "DIFFERENT" | "UNSURE";
const CHOICES: { value: Choice; text: string }[] = [
  { value: "SAME", text: "Same person" },
  { value: "DIFFERENT", text: "Different people" },
  { value: "UNSURE", text: "Not sure" },
];

function PairCard({ pair, onLabel }: { pair: SamplePair; onLabel: (c: Choice) => void }) {
  const rows: [string, string][] = [
    ["Name", "name"],
    ["Date of birth", "dateOfBirth"],
    ["Mobile", "mobile"],
    ["District", "districtLGD"],
    ["Gender", "gender"],
  ];
  return (
    <article aria-label={`Pair scoring ${pair.matchProbability?.toFixed(2)}`} data-testid="label-pair">
      <h3>
        Score {pair.matchProbability?.toFixed(3)} · {pair.decision}
      </h3>
      <table>
        <tbody>
          {rows.map(([label, key]) => (
            <tr key={key}>
              <th scope="row">{label}</th>
              <td>{evidenceValue(pair.evidence, key)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="row">
        {CHOICES.map((c) => (
          <Button key={c.value} variant={c.value === "SAME" ? "primary" : "secondary"} onClick={() => onLabel(c.value)}>
            {c.text}
          </Button>
        ))}
      </div>
    </article>
  );
}

/** Stewards label pairs sampled across the score bands; the labels are the ground truth for threshold suggestions. */
export function LabellingPanel() {
  const session = useSession();
  const sample = useLabelSample();
  const label = useLabel();
  const summary = useLabelSummary();
  const [perBand, setPerBand] = useState(3);
  const [done, setDone] = useState<Set<string>>(new Set());
  const canLabel = session.can("MATCHING_MANAGE");
  const key = (p: SamplePair) => `${p.recordA}/${p.recordB}`;
  const pending = (sample.data ?? []).filter((p) => !done.has(key(p)));
  return (
    <section aria-labelledby="labelling-title">
      <h2 id="labelling-title">Label pairs</h2>
      <p>
        Pairs are drawn from every score band, not only the unclear ones, so the
        thresholds can be checked against what stewards actually decide.
      </p>
      {summary.data && (
        <p data-testid="label-summary">
          {summary.data.total} labelled so far
          {Object.entries(summary.data.byLabel ?? {})
            .map(([name, count]) => ` · ${name.toLowerCase()}: ${count}`)
            .join("")}
        </p>
      )}
      <div className="row">
        <label>
          Pairs per score band{" "}
          <input
            type="number"
            min={1}
            max={50}
            value={perBand}
            onChange={(e) => setPerBand(Number(e.target.value))}
          />
        </label>
        <Button onClick={() => { setDone(new Set()); sample.mutate(perBand); }} disabled={sample.isPending}>
          Get pairs to label
        </Button>
      </div>
      {sample.isError && <p role="alert">Could not load pairs: is there a completed matching run?</p>}
      {sample.data && pending.length === 0 && <p>No more pairs to label.</p>}
      {pending.map((pair) =>
        canLabel ? (
          <PairCard
            key={key(pair)}
            pair={pair}
            onLabel={(choice) =>
              label.mutate(
                { recordA: pair.recordA ?? "", recordB: pair.recordB ?? "", label: choice },
                { onSuccess: () => setDone((d) => new Set(d).add(key(pair))) },
              )
            }
          />
        ) : (
          <p key={key(pair)}>You may view but not label pairs.</p>
        ),
      )}
    </section>
  );
}
