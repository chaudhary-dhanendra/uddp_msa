import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import { Button, Field, Panel, Select, TextInput } from "../../shared/components";
import { useSession } from "../auth";

const LEVELS = ["state", "district", "subDistrict", "block", "gramPanchayat", "localBody", "village", "ward"];
const typeFor = (level: string) => level.replace(/([a-z])([A-Z])/g, "$1_$2").toUpperCase();
const PARENT: Record<string, string | undefined> = { district: "state", subDistrict: "district", block: "district", gramPanchayat: "block", localBody: "district", village: "subDistrict", ward: "localBody" };

/** Typed, server-side LGD lookup and the F4 states; choosing a result is always an explicit action. */
export function GeographyPanel({ stateCode }: { stateCode?: string | null }) {
  const session = useSession();
  const [level, setLevel] = useState("district");
  const [query, setQuery] = useState("");
  const [parentCode, setParentCode] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const search = useQuery({
    queryKey: ["geography-search", level, query, parentCode], enabled: query.trim().length > 0,
    queryFn: () => unwrap(api.GET("/api/geography/entities", { params: { query: {
      type: typeFor(level), q: query, parentType: parentCode ? typeFor(PARENT[level] ?? "") : undefined,
      parentCode: parentCode || undefined, status: "ACTIVE", page: 0, size: 10,
    } } })),
  });
  const coverage = useQuery({ queryKey: ["geography-coverage", stateCode], queryFn: () => unwrap(api.GET("/api/geography/coverage", { params: { query: { stateCode: stateCode ?? undefined } } })) });
  const status = useQuery({ queryKey: ["lgd-status"], queryFn: () => unwrap(api.GET("/api/geography/sync/status")) });
  const runs = useQuery({ queryKey: ["lgd-runs"], queryFn: () => unwrap(api.GET("/api/geography/sync/runs", { params: { query: { page: 0, size: 10 } } })) });
  const changes = useQuery({ queryKey: ["lgd-changes"], queryFn: () => unwrap(api.GET("/api/geography/changes", { params: { query: { page: 0, size: 10 } } })) });
  const trigger = useMutation({ mutationFn: () => unwrap(api.POST("/api/geography/sync/runs")) });
  return <Panel title="Geography mapping and coverage">
    <p className="muted">Search is server-side. A result is only used after you explicitly select it; unmatched source text remains Unknown for review.</p>
    <div className="grid-2"><Field id="geo-level" label="Geography level"><Select id="geo-level" value={level} onChange={(e) => setLevel(e.target.value)} options={LEVELS.map((v) => ({ value: v, label: v }))} /></Field>
      <Field id="geo-query" label="Code or name"><TextInput id="geo-query" value={query} onChange={(e) => { setQuery(e.target.value); setSelected(null); }} /></Field>
      <Field id="geo-parent" label="Known parent LGD code (optional)"><TextInput id="geo-parent" value={parentCode} onChange={(e) => setParentCode(e.target.value)} /></Field></div>
    {search.data?.items.map((item) => <div key={`${item.entityType}-${item.code}`} className="row"><span>{item.name} ({item.code})</span><Button variant="secondary" onClick={() => setSelected(`${item.name} (${item.code})`)}>Select</Button></div>)}
    {query && !search.isPending && search.data?.items.length === 0 && <p data-testid="geo-unknown">Unknown — raw source value is preserved; no LGD code is invented.</p>}
    {selected && <p data-testid="geo-resolved">Resolved: {selected}; ancestors will be derived by canonical processing.</p>}
    <h3>Geography coverage</h3><table data-testid="geo-coverage"><thead><tr><th>Source</th><th>Level</th><th>Resolved</th><th>Unknown</th><th>Resolved %</th><th>Unknown %</th></tr></thead><tbody>{coverage.data?.map((row) => <tr key={`${row.stateCode}-${row.sourceSystem}-${row.level}`}><td>{row.stateCode}/{row.sourceSystem}</td><td>{row.level}</td><td>{row.resolved}</td><td>{row.unknown}</td><td>{row.resolvedPercent.toFixed(1)}</td><td>{row.unknownPercent.toFixed(1)}</td></tr>)}</tbody></table>
    <h3>LGD operations</h3>
    <p className="muted">{status.data?.activeSnapshotId ? `Active snapshot: ${status.data.activeSnapshotId}; last successful sync: ${status.data.lastSuccessAt ?? "not recorded"}; ${status.data.stale ? "stale" : "current"}.` : "No active LGD snapshot."}</p>
    {session.can("GEOGRAPHY_SYNC") && <Button onClick={() => trigger.mutate()}>Trigger LGD sync</Button>}
    <h4>Recent syncs</h4><ul data-testid="lgd-sync-history">{runs.data?.items.map((run) => <li key={run.runId}>{run.status} · {run.startedAt} → {run.finishedAt ?? "running"} · snapshot {run.snapshotId ?? "—"}{run.error ? ` · ${run.error}` : ""}</li>)}</ul>
    <h4>Recent LGD changes</h4><ul data-testid="lgd-change-history">{changes.data?.items.map((change) => <li key={change.changeId}>{change.changeType} · {change.entityType} {change.code} · {change.oldValue ?? ""} → {change.newValue ?? ""}</li>)}</ul>
  </Panel>;
}

/**
 * Crosswalk target picker for an LGD code set (lgd-district, lgd-village, ...): a server-side search of the LGD
 * master. The chosen code is written to the source pack's crosswalk, never to the master.
 */
export function LgdCodePicker({ referenceSet, value, label, onChange }: { referenceSet: string; value: string; label: string; onChange: (code: string) => void }) {
  const [query, setQuery] = useState("");
  const results = useQuery({
    queryKey: ["lgd-values", referenceSet, query], enabled: query.trim().length > 1,
    queryFn: () => unwrap(api.GET("/api/reference-data/{referenceSet}/values", { params: { path: { referenceSet }, query: { q: query, limit: 10 } } })),
  });
  return <div data-testid={`lgd-picker-${referenceSet}`}>
    <TextInput aria-label={label} placeholder={value || "Search LGD code or name"} value={query} onChange={(e) => setQuery(e.target.value)} />
    {value && <span className="muted"> selected {value}</span>}
    {results.data?.map((item) => <Button key={item.code} variant="secondary" onClick={() => { onChange(item.code ?? ""); setQuery(""); }}>{item.displayName} ({item.code})</Button>)}
  </div>;
}
