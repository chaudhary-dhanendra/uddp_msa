import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import { useI18n } from "../../i18n/i18n";
import { Button, DataTable, Field, Panel, QueryState, TextInput } from "../../shared/components";
import { useSession } from "../auth";
import { ageDays, heldKey, type BulkAction, type HeldRecord } from "./heldApi";

export const bulkKey = [...heldKey, "bulk-actions", "PENDING_APPROVAL"] as const;

/** The pending bulk rejects in the caller's scope (shared by the list and the tab header count). */
export function usePendingBulkRejects(enabled = true) {
  return useQuery({
    queryKey: bulkKey,
    queryFn: () =>
      unwrap(api.GET("/api/v1/held-records/bulk-actions", { params: { query: { status: "PENDING_APPROVAL" } } })),
    enabled,
  });
}

function sample(record: HeldRecord): string {
  return `${record.sourceSystem ?? ""} ${record.heldId?.slice(0, 8) ?? ""}`.trim();
}

/**
 * Bulk rejects that wait for a second steward: group, size, reason, requester, age and five masked records. Another
 * steward approves (the group is rejected) or rejects with a reason (the records stay held); the requester only sees
 * that someone else must decide.
 */
export function PendingBulkRejects() {
  const { t } = useI18n();
  const session = useSession();
  const pending = usePendingBulkRejects();
  return (
    <Panel title={t("held.bulk.title")}>
      <QueryState query={pending} isEmpty={(d) => d.length === 0} emptyText={t("held.bulk.none")}>
        {(rows) => (
          <DataTable<BulkAction>
            caption={t("held.bulk.caption")}
            testId="held-bulk-pending"
            rows={rows}
            rowKey={(a) => a.actionId ?? ""}
            columns={[
              {
                key: "group",
                header: t("held.bulk.group"),
                render: (a) =>
                  t("held.bulk.groupValue", { reason: a.reasonCode, batch: a.batchId?.slice(0, 8) }),
              },
              { key: "count", header: t("held.bulk.count"), render: (a) => a.recordCount },
              { key: "reason", header: t("held.bulk.reason"), render: (a) => a.rejectReasonCode },
              { key: "requester", header: t("held.bulk.requester"), render: (a) => a.requestedBy },
              {
                key: "age",
                header: t("held.bulk.age"),
                render: (a) => t("held.bulk.ageDays", { days: ageDays(a.requestedAt) }),
              },
              {
                key: "samples",
                header: t("held.bulk.samples"),
                render: (a) => (
                  <ul>
                    {a.samples.slice(0, 5).map((s) => (
                      <li key={s.heldId}>{sample(s)}</li>
                    ))}
                  </ul>
                ),
              },
              {
                key: "decide",
                header: t("held.bulk.decide"),
                render: (a) =>
                  a.ownRequest ? (
                    <span data-testid="bulk-own">{t("held.bulk.own")}</span>
                  ) : session.can("STEWARD_HELD_DECIDE") ? (
                    <BulkDecision action={a} />
                  ) : null,
              },
            ]}
          />
        )}
      </QueryState>
    </Panel>
  );
}

function BulkDecision({ action }: { action: BulkAction }) {
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const [reason, setReason] = useState("");
  const [done, setDone] = useState<string | null>(null);
  const actionId = action.actionId ?? "";
  const refresh = () => queryClient.invalidateQueries({ queryKey: heldKey });
  const approve = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/held-records/bulk-actions/{actionId}/approve", {
          params: { path: { actionId } },
          body: { reason: null },
        }),
      ),
    onSuccess: (result) => {
      setDone(t("held.bulk.approved", { count: result.records }));
      void refresh();
    },
  });
  const reject = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/held-records/bulk-actions/{actionId}/decline", {
          params: { path: { actionId } },
          body: { reason },
        }),
      ),
    onSuccess: () => {
      setDone(t("held.bulk.rejected"));
      void refresh();
    },
  });
  if (done) {
    return <p role="status">{done}</p>;
  }
  const error = approve.error ?? reject.error;
  return (
    <div className="stack">
      <Button onClick={() => approve.mutate()} disabled={approve.isPending || reject.isPending}>
        {t("held.bulk.approve")}
      </Button>
      <Field id={`bulk-reason-${actionId}`} label={t("held.bulk.rejectReason")}>
        <TextInput id={`bulk-reason-${actionId}`} value={reason} onChange={(e) => setReason(e.target.value)} />
      </Field>
      <Button
        variant="secondary"
        onClick={() => reject.mutate()}
        disabled={!reason.trim() || approve.isPending || reject.isPending}
        title={reason.trim() ? undefined : t("held.bulk.rejectReasonNeeded")}
      >
        {t("held.bulk.reject")}
      </Button>
      {error instanceof Error && <p role="alert">{error.message}</p>}
    </div>
  );
}
