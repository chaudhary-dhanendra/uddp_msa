import { Button, QueryState } from "../../shared/components";
import { useSession } from "../auth";
import {
  useAcknowledge,
  useAlerts,
  useBounds,
  useDecideProposal,
  useMetrics,
  useProposals,
  useRefreshProposals,
} from "./hooks";
import type { Alert, Bound, Proposal, RunMetrics } from "./types";

function percent(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}

function Alerts() {
  const alerts = useAlerts();
  const acknowledge = useAcknowledge();
  const session = useSession();
  return (
    <div aria-labelledby="alerts-title" role="region">
      <h3 id="alerts-title">Alerts</h3>
      <QueryState query={alerts} isEmpty={(l) => l.length === 0} emptyText="No open alerts: metrics are within bounds.">
        {(list: Alert[]) => (
          <ul>
            {list.map((a) => (
              <li key={a.alertId} data-testid="alert">
                <strong>{a.kind ?? ""}</strong> {a.message}{" "}
                {session.can("MATCHING_MANAGE") && (
                  <Button variant="secondary" onClick={() => acknowledge.mutate(a.alertId ?? "")}>
                    Acknowledge
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </QueryState>
    </div>
  );
}

function Suggestions() {
  const session = useSession();
  const proposals = useProposals();
  const refresh = useRefreshProposals();
  const approve = useDecideProposal("approve");
  const reject = useDecideProposal("reject");
  const username = session.user?.username;
  return (
    <div role="region" aria-labelledby="suggestions-title">
      <h3 id="suggestions-title">Threshold suggestions from steward decisions</h3>
      <p>
        Suggestions are proposals: nothing changes until a different user approves them.
      </p>
      {session.can("MATCHING_MANAGE") && (
        <Button variant="secondary" onClick={() => refresh.mutate()} disabled={refresh.isPending}>
          Look for suggestions
        </Button>
      )}
      <QueryState query={proposals} isEmpty={(l) => l.length === 0} emptyText="No suggestions right now.">
        {(list: Proposal[]) => (
          <ul>
            {list.map((p) => (
              <li key={p.proposalId} data-testid="proposal">
                {p.rationale}
                {session.can("MATCHING_APPROVE") && p.proposedBy !== username ? (
                  <>
                    {" "}
                    <Button onClick={() => approve.mutate(p.proposalId ?? "")}>Approve</Button>{" "}
                    <Button variant="secondary" onClick={() => reject.mutate(p.proposalId ?? "")}>
                      Dismiss
                    </Button>
                  </>
                ) : (
                  <em> (needs approval by someone other than {p.proposedBy})</em>
                )}
              </li>
            ))}
          </ul>
        )}
      </QueryState>
      {(approve.isError || reject.isError) && (
        <p role="alert">
          That change could not be applied: approve a matching profile first, and a proposal cannot be approved by its proposer.
        </p>
      )}
    </div>
  );
}

function bandRate(metrics: RunMetrics): string {
  const entries = Object.entries(metrics.acceptanceByBand ?? {}).sort();
  if (entries.length === 0) {
    return "—";
  }
  return entries
    .map(([band, c]) => {
      const total = (c.accepted ?? 0) + (c.rejected ?? 0);
      return `${band}: ${total === 0 ? "—" : Math.round(((c.accepted ?? 0) / total) * 100) + "%"}`;
    })
    .join(" · ");
}

/** Run metrics, alerts when they drift beyond the bounds, and threshold suggestions from steward behaviour. */
export function MonitoringPanel() {
  const metrics = useMetrics();
  const bounds = useBounds();
  return (
    <section aria-labelledby="monitoring-title">
      <h2 id="monitoring-title">Monitoring</h2>
      <Alerts />
      <Suggestions />
      <h3>Recent runs</h3>
      <QueryState query={metrics} isEmpty={(l) => l.length === 0} emptyText="No matching runs recorded yet.">
        {(list: RunMetrics[]) => (
          <table>
            <caption>Matching metrics per run</caption>
            <thead>
              <tr>
                <th scope="col">Run</th>
                <th scope="col">Match rate</th>
                <th scope="col">Automatic pairs</th>
                <th scope="col">Review volume</th>
                <th scope="col">Steward acceptance by score band</th>
              </tr>
            </thead>
            <tbody>
              {list.map((m) => (
                <tr key={m.runId}>
                  <th scope="row">{m.modelVersion}</th>
                  <td>{percent(m.matchRate)}</td>
                  <td>{m.autoPairs}</td>
                  <td>{m.possiblePairs}</td>
                  <td>{bandRate(m)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </QueryState>
      <QueryState query={bounds}>
        {(list: Bound[]) => (
          <details>
            <summary>Alert bounds</summary>
            <ul>
              {list.map((b) => (
                <li key={b.metric}>
                  {b.metric}: min {b.minValue ?? "none"}, max {b.maxValue ?? "none"}, change vs previous run{" "}
                  {b.maxRelativeChange === null ? "unchecked" : percent(b.maxRelativeChange)}
                </li>
              ))}
            </ul>
          </details>
        )}
      </QueryState>
    </section>
  );
}
