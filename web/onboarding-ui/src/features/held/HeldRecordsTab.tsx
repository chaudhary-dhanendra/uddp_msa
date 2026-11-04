import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import { Button, DataTable, Field, Panel, QueryState, Select, StatusBadge, TextInput } from "../../shared/components";
import { useSession } from "../auth";
import { PendingBulkRejects } from "./PendingBulkRejects";
import {
  groupAgeDays,
  heldKey,
  loadGroups,
  loadRecord,
  packFix,
  payloadValue,
  REJECT_REASONS,
  type GroupActionResult,
  type HeldGroup,
  type HeldReason,
  type HeldRecord,
} from "./heldApi";

/** Held rows grouped by batch and reason, with the actions that settle a whole group or one record. */
export function HeldRecordsTab() {
  const [group, setGroup] = useState<HeldGroup | null>(null);
  const [recordId, setRecordId] = useState<string | null>(null);
  const session = useSession();
  const groups = useQuery({ queryKey: [...heldKey, "groups"], queryFn: loadGroups });
  if (recordId) {
    return <HeldRecordDetail id={recordId} onBack={() => setRecordId(null)} />;
  }
  return (
    <div className="stack">
      <PendingBulkRejects />
      {session.can("AUDIT_VIEW") && (
        <p>
          <Link to="/audit?category=final-rejected">Final rejected records (audit report)</Link>
        </p>
      )}
      <QueryState query={groups} isEmpty={(d) => d.length === 0} emptyText="No records are held.">
        {(rows) => (
          <DataTable<HeldGroup>
            caption="Held records by batch and reason"
            testId="held-groups"
            rows={rows}
            rowKey={(g) => `${g.batchId}/${g.reasonCode}`}
            columns={[
              {
                key: "reason",
                header: "Batch and reason",
                render: (g) => (
                  <Button variant="secondary" onClick={() => setGroup(g)}>
                    {g.reasonCode} · batch {g.batchId.slice(0, 8)}
                  </Button>
                ),
              },
              { key: "count", header: "Records", render: (g) => g.count },
              { key: "share", header: "% of batch", render: (g) => `${g.shareOfBatchPercent.toFixed(1)}%` },
              { key: "age", header: "Age", render: (g) => `${groupAgeDays(g)} d` },
              {
                key: "priority",
                header: "Priority",
                render: (g) => (g.shareOfBatchPercent >= 5 ? "HIGH" : "NORMAL"),
              },
            ]}
          />
        )}
      </QueryState>
      {group && <GroupPanel group={group} onOpen={setRecordId} onDone={() => setGroup(null)} />}
    </div>
  );
}

function GroupPanel({ group, onOpen, onDone }: { group: HeldGroup; onOpen: (id: string) => void; onDone: () => void }) {
  const session = useSession();
  const canDecide = session.can("STEWARD_HELD_DECIDE");
  const client = useQueryClient();
  const sample = group.samples[0];
  const fix = packFix(
    group.reasonCode,
    sample?.reasons.find((r) => r.code === group.reasonCode)?.fix,
    sample?.draftId,
  );
  const [reasonCode, setReasonCode] = useState("SOURCE_ERROR");
  const [note, setNote] = useState("");
  const path = { batch: group.batchId, reason: group.reasonCode };
  const done = () => client.invalidateQueries({ queryKey: heldKey });
  const reprocess = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/held-records/groups/{batch}/{reason}/reprocess", { params: { path } })),
    onSuccess: done,
  });
  const reject = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/held-records/groups/{batch}/{reason}/reject", {
          params: { path },
          body: { reasonCode, note: note.trim() || null },
        }),
      ),
    onSuccess: done,
  });
  const rejected: GroupActionResult | undefined = reject.data;
  const reprocessed: GroupActionResult | undefined = reprocess.data;
  return (
    <Panel title={`${group.reasonCode} · ${group.count} records`}>
      <ul data-testid="held-samples" aria-label="Sample records">
        {group.samples.slice(0, 5).map((s) => (
          <li key={s.heldId ?? ""}>
            <Button variant="secondary" onClick={() => onOpen(s.heldId ?? "")}>
              {sampleLabel(s)}
            </Button>
          </li>
        ))}
      </ul>
      <p>
        <Link to={fix.to}>{fix.label}</Link>
      </p>
      {canDecide && (
        <div className="row">
          <Button variant="secondary" onClick={() => reprocess.mutate()} disabled={reprocess.isPending}>
            Reprocess group
          </Button>
          <Field id="bulk-reject-reason" label="Bulk reject reason">
            <Select
              id="bulk-reject-reason"
              value={reasonCode}
              options={REJECT_REASONS}
              onChange={(e) => setReasonCode(e.target.value)}
            />
          </Field>
          <Field id="bulk-reject-note" label="Bulk reject note">
            <TextInput id="bulk-reject-note" value={note} onChange={(e) => setNote(e.target.value)} />
          </Field>
          <Button
            variant="secondary"
            onClick={() => reject.mutate()}
            disabled={reject.isPending || note.trim() === ""}
          >
            Bulk reject
          </Button>
        </div>
      )}
      {reprocessed && (
        <p role="status">
          Reprocessed: {reprocessed.released ?? 0} released, {reprocessed.stillHeld ?? 0} still held.
        </p>
      )}
      {rejected?.outcome === "PENDING_APPROVAL" && (
        <p role="status" data-testid="needs-second-approver">
          Needs second approver: {rejected.records} records are rejected once another steward approves action{" "}
          {rejected.actionId?.slice(0, 8)}.
        </p>
      )}
      {rejected && rejected.outcome !== "PENDING_APPROVAL" && <p role="status">{rejected.records} records rejected.</p>}
      {(reject.isError || reprocess.isError) && <p role="alert">{(reject.error ?? reprocess.error)?.message}</p>}
      <Button variant="secondary" onClick={onDone}>
        Close group
      </Button>
    </Panel>
  );
}

function sampleLabel(record: HeldRecord): string {
  const payload = record.currentPayload ?? record.originalPayload;
  const name = payloadValue(payload, "name") || payloadValue(payload, "fullName");
  return `${name || "Record"} · ${record.heldId?.slice(0, 8)}`;
}

/** One held record: the original, the failing fields with an edit form, and the decisions. */
export function HeldRecordDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const session = useSession();
  const canDecide = session.can("STEWARD_HELD_DECIDE");
  const client = useQueryClient();
  const detail = useQuery({ queryKey: [...heldKey, id], queryFn: () => loadRecord(id) });
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [note, setNote] = useState("");
  const [justification, setJustification] = useState("");
  const [rejectCode, setRejectCode] = useState("SOURCE_ERROR");
  const [rejectNote, setRejectNote] = useState("");
  const refresh = () => client.invalidateQueries({ queryKey: heldKey });
  const rectify = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/held-records/{id}/rectify", {
          params: { path: { id } },
          body: { fields: edits as unknown as Record<string, Record<string, never>>, note: note.trim() || null },
        }),
      ),
    onSuccess: () => client.invalidateQueries({ queryKey: [...heldKey, "groups"] }),
  });
  const release = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/held-records/{id}/release-override", {
          params: { path: { id } },
          body: { justification },
        }),
      ),
    onSuccess: refresh,
  });
  const reject = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/held-records/{id}/reject", {
          params: { path: { id } },
          body: { reasonCode: rejectCode, note: rejectNote.trim() || null },
        }),
      ),
    onSuccess: refresh,
  });
  return (
    <QueryState query={detail}>
      {(d) => {
        const record = d.record;
        // A rectify that still fails answers with the reasons found now; they replace the stored ones on screen.
        const reasons: HeldReason[] = rectify.data?.reasons ?? record?.reasons ?? [];
        const fields = [...new Set(reasons.map((r) => r.field).filter((f): f is string => !!f))];
        const status = rectify.data?.status ?? record?.status ?? "";
        const open = status === "OPEN" || status === "IN_REVIEW";
        const stewardOnly = reasons.length > 0 && reasons.every((r) => r.severity === "SEND_TO_STEWARD");
        return (
          <Panel
            title={`Held record ${id.slice(0, 8)}`}
            actions={
              <Button variant="secondary" onClick={onBack}>
                Back to groups
              </Button>
            }
          >
            <p>
              <StatusBadge status={status} /> {record?.sourceSystem} · {record?.stateCode}
            </p>
            <h3>Original payload</h3>
            <pre data-testid="held-original">{JSON.stringify(record?.originalPayload, null, 2)}</pre>
            <h3>Why it is held</h3>
            <ul data-testid="held-reasons">
              {reasons.map((r, i) => (
                <li key={i}>
                  <strong>{r.field ?? r.code}</strong>: {r.message} ({r.severity})
                  {r.code && d.suggestions?.[r.code] && (
                    <div data-testid="held-suggestion">Suggested fix: {d.suggestions[r.code]}</div>
                  )}
                </li>
              ))}
            </ul>
            {rectify.data && (
              <p role="status" data-testid="rectify-result">
                {rectify.data.status === "RECTIFIED"
                  ? "Valid now: the record was released."
                  : `Still failing: ${rectify.data.reasons.length} problem(s) remain; the record stays open.`}
              </p>
            )}
            {canDecide && open && (
              <>
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    rectify.mutate();
                  }}
                >
                  {fields.map((f) => (
                    <Field key={f} id={`fix-${f}`} label={`Correct ${f}`}>
                      <TextInput
                        id={`fix-${f}`}
                        value={edits[f] ?? payloadValue(record?.currentPayload, f)}
                        onChange={(e) => setEdits({ ...edits, [f]: e.target.value })}
                      />
                    </Field>
                  ))}
                  <Field id="fix-note" label="Note">
                    <TextInput id="fix-note" value={note} onChange={(e) => setNote(e.target.value)} />
                  </Field>
                  <Button type="submit" disabled={rectify.isPending || Object.keys(edits).length === 0}>
                    Rectify
                  </Button>
                </form>
                {stewardOnly && (
                  <div className="row">
                    <Field id="release-justification" label="Justification for releasing as it is">
                      <TextInput
                        id="release-justification"
                        value={justification}
                        onChange={(e) => setJustification(e.target.value)}
                      />
                    </Field>
                    <Button
                      variant="secondary"
                      onClick={() => release.mutate()}
                      disabled={release.isPending || justification.trim() === ""}
                    >
                      Release as-is
                    </Button>
                  </div>
                )}
                <div className="row">
                  <Field id="reject-reason" label="Reject reason">
                    <Select
                      id="reject-reason"
                      value={rejectCode}
                      options={REJECT_REASONS}
                      onChange={(e) => setRejectCode(e.target.value)}
                    />
                  </Field>
                  <Field id="reject-note" label="Reject note">
                    <TextInput id="reject-note" value={rejectNote} onChange={(e) => setRejectNote(e.target.value)} />
                  </Field>
                  <Button
                    variant="secondary"
                    onClick={() => reject.mutate()}
                    disabled={reject.isPending || (rejectCode === "OTHER" && rejectNote.trim() === "")}
                  >
                    Reject permanently
                  </Button>
                </div>
              </>
            )}
            {(rectify.isError || release.isError || reject.isError) && (
              <p role="alert">{(rectify.error ?? release.error ?? reject.error)?.message}</p>
            )}
            <h3>History</h3>
            <ol data-testid="held-history" aria-label="History">
              {d.history.map((e, i) => (
                <li key={e.eventId ?? i}>
                  {e.at ? new Date(e.at).toLocaleString() : "—"} · {e.action} by {e.actor}
                  {e.note ? ` — ${e.note}` : ""}
                </li>
              ))}
            </ol>
          </Panel>
        );
      }}
    </QueryState>
  );
}
