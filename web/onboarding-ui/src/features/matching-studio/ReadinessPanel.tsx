import { QueryState } from "../../shared/components";
import { useReadiness } from "./hooks";
import type { SourceReadiness } from "./types";

const FIELD_LABELS: Record<string, string> = {
  name: "Name",
  date_of_birth: "Date of birth",
  gender: "Gender",
  mobile: "Mobile",
  district_lgd: "District",
  relation_name: "Relation name",
  national_id: "National ID",
};
const LOW = 0.5;

function percent(value: number): string {
  return `${Math.round(value * 100)}%`;
}

function Cell({ value }: { value: number }) {
  const low = value < LOW;
  return (
    <td data-testid="readiness-cell" data-low={low}>
      {percent(value)}
      {low ? " — low" : ""}
    </td>
  );
}

/** How complete each matching field is, per source: a profile cannot learn from a field that is mostly empty. */
export function ReadinessPanel() {
  const readiness = useReadiness();
  return (
    <section aria-labelledby="readiness-title">
      <h2 id="readiness-title">Data readiness</h2>
      <p>
        Share of records that have a value for each field, per source. A field
        that is mostly empty adds little to matching.
      </p>
      <QueryState
        query={readiness}
        isEmpty={(rows) => rows.length === 0}
        emptyText="No canonical Residents yet: onboard a source first."
      >
        {(rows: SourceReadiness[]) => {
          const fields = Object.keys(rows[0]?.completeness ?? {});
          return (
            <table>
              <caption>Field completeness per source</caption>
              <thead>
                <tr>
                  <th scope="col">Source</th>
                  <th scope="col">Records</th>
                  {fields.map((f) => (
                    <th scope="col" key={f}>
                      {FIELD_LABELS[f] ?? f}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={`${row.stateCode}/${row.sourceSystem}`}>
                    <th scope="row">
                      {row.stateCode} / {row.sourceSystem}
                    </th>
                    <td>{row.records}</td>
                    {fields.map((f) => (
                      <Cell key={f} value={row.completeness[f] ?? 0} />
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          );
        }}
      </QueryState>
    </section>
  );
}
