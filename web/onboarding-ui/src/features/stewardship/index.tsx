import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type {
  ReviewTaskSummary,
  StewardshipProposal,
  StewardshipProposalRequest,
} from "../../api/types";
import { useI18n } from "../../i18n/i18n";
import {
  Button,
  DataTable,
  Field,
  PageHeader,
  Panel,
  QueryState,
  StatusBadge,
  TextInput,
} from "../../shared/components";
import { useSession } from "../auth";
import { HeldRecordsTab } from "../held/HeldRecordsTab";
import { usePendingBulkRejects } from "../held/PendingBulkRejects";

export const PROPOSALS_KEY = ["stewardship", "proposals"];

function useSubmitProposal() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: StewardshipProposalRequest) =>
      unwrap(api.POST("/api/stewardship/proposals", { body })),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: PROPOSALS_KEY });
      await queryClient.invalidateQueries({ queryKey: ["reviews"] });
    },
  });
}

/**
 * The review queue and manual merge/split. With maker-checker every action is submitted for approval: nothing
 * changes Golden data until a different user approves it on the Pending approvals page.
 */
export function StewardshipPage() {
  const { t } = useI18n();
  const session = useSession();
  const [tab, setTab] = useState<"reviews" | "held">(useSearchParams()[0].get("tab") === "held" ? "held" : "reviews");
  const canSeeHeld = session.can("STEWARD_HELD_VIEW");
  const pendingBulk = usePendingBulkRejects(canSeeHeld);
  const canDecide = session.can("STEWARDSHIP_DECIDE");
  const reviews = useQuery({
    queryKey: ["reviews"],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/stewardship/reviews", {
          params: { query: { page: 0, size: 50 } },
        }),
      ),
  });
  return (
    <>
      <PageHeader
        title={t("stewardship.title")}
        description={t("stewardship.description")}
      />
      {canSeeHeld && (
        <div role="tablist" aria-label="Stewardship views" className="row">
          <Button
            role="tab"
            aria-selected={tab === "reviews"}
            variant={tab === "reviews" ? "primary" : "secondary"}
            onClick={() => setTab("reviews")}
          >
            Match review
          </Button>
          <Button
            role="tab"
            aria-selected={tab === "held"}
            variant={tab === "held" ? "primary" : "secondary"}
            onClick={() => setTab("held")}
            data-testid="held-tab"
          >
            {pendingBulk.data && pendingBulk.data.length > 0
              ? t("stewardship.heldTabPending", { count: pendingBulk.data.length })
              : t("stewardship.heldTab")}
          </Button>
        </div>
      )}
      {canSeeHeld && tab === "held" ? (
        <HeldRecordsTab />
      ) : (
      <>
      <QueryState query={reviews}>
        {(page) => (
          <DataTable<ReviewTaskSummary>
            caption={t("stewardship.queue")}
            testId="review-queue"
            rows={page.items}
            rowKey={(task) => task.reviewTaskId ?? ""}
            emptyText={t("stewardship.noTasks")}
            columns={[
              {
                key: "pair",
                header: t("stewardship.pair"),
                render: (task) =>
                  `${task.nameA ?? "—"} (${task.stateCodeA ?? "—"}) / ${task.nameB ?? "—"} (${task.stateCodeB ?? "—"})`,
              },
              {
                key: "probability",
                header: t("stewardship.probability"),
                render: (task) => task.matchProbability?.toFixed(3) ?? "—",
              },
              {
                key: "status",
                header: t("stewardship.status"),
                render: (task) => <StatusBadge status={task.status ?? ""} />,
              },
              {
                key: "action",
                header: t("stewardship.action"),
                render: (task) =>
                  canDecide && task.status === "OPEN" ? (
                    <ReviewActions taskId={task.reviewTaskId ?? ""} />
                  ) : (
                    "—"
                  ),
              },
            ]}
          />
        )}
      </QueryState>
      {canDecide && (
        <>
          <MergeForm />
          <SplitForm />
        </>
      )}
      </>
      )}
    </>
  );
}

function ReviewActions({ taskId }: { taskId: string }) {
  const { t } = useI18n();
  const [reason, setReason] = useState("");
  const submit = useSubmitProposal();
  const propose = (action: "ACCEPT_MATCH" | "REJECT_MATCH") =>
    submit.mutate({
      action,
      reviewTaskId: taskId,
      goldenResidentIds: [],
      sourceRecordKeys: [],
      reason,
    });
  return (
    <div className="row">
      <Field id={`review-reason-${taskId}`} label={t("stewardship.reason")}>
        <TextInput
          id={`review-reason-${taskId}`}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
      </Field>
      <Button
        onClick={() => propose("ACCEPT_MATCH")}
        disabled={reason.trim() === "" || submit.isPending}
        data-testid={`propose-accept-${taskId}`}
      >
        {t("stewardship.submitAccept")}
      </Button>
      <Button
        variant="secondary"
        onClick={() => propose("REJECT_MATCH")}
        disabled={reason.trim() === "" || submit.isPending}
        data-testid={`propose-reject-${taskId}`}
      >
        {t("stewardship.submitReject")}
      </Button>
      <SubmitResult result={submit.data} error={submit.error} />
    </div>
  );
}

function MergeForm() {
  const { t } = useI18n();
  const [ids, setIds] = useState("");
  const [reason, setReason] = useState("");
  const submit = useSubmitProposal();
  return (
    <Panel title={t("stewardship.merge")}>
      <form
        className="row"
        onSubmit={(event) => {
          event.preventDefault();
          submit.mutate({
            action: "MERGE",
            reviewTaskId: null,
            goldenResidentIds: ids
              .split(/[\s,]+/)
              .map((id) => id.trim())
              .filter((id) => id !== ""),
            sourceRecordKeys: [],
            reason,
          });
        }}
      >
        <Field id="merge-ids" label={t("stewardship.goldenIds")}>
          <TextInput
            id="merge-ids"
            value={ids}
            onChange={(e) => setIds(e.target.value)}
          />
        </Field>
        <Field id="merge-reason" label={t("stewardship.reason")}>
          <TextInput
            id="merge-reason"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </Field>
        <Button
          type="submit"
          disabled={reason.trim() === "" || submit.isPending}
          data-testid="propose-merge"
        >
          {t("stewardship.submit")}
        </Button>
      </form>
      <SubmitResult result={submit.data} error={submit.error} />
    </Panel>
  );
}

function SplitForm() {
  const { t } = useI18n();
  const [id, setId] = useState("");
  const [keys, setKeys] = useState("");
  const [reason, setReason] = useState("");
  const submit = useSubmitProposal();
  return (
    <Panel title={t("stewardship.split")}>
      <form
        className="row"
        onSubmit={(event) => {
          event.preventDefault();
          submit.mutate({
            action: "SPLIT",
            reviewTaskId: null,
            goldenResidentIds: [id.trim()],
            sourceRecordKeys: keys
              .split(/[\s,]+/)
              .map((key) => key.trim())
              .filter((key) => key !== ""),
            reason,
          });
        }}
      >
        <Field id="split-id" label={t("stewardship.goldenId")}>
          <TextInput
            id="split-id"
            value={id}
            onChange={(e) => setId(e.target.value)}
          />
        </Field>
        <Field id="split-keys" label={t("stewardship.recordKeys")}>
          <TextInput
            id="split-keys"
            value={keys}
            onChange={(e) => setKeys(e.target.value)}
          />
        </Field>
        <Field id="split-reason" label={t("stewardship.reason")}>
          <TextInput
            id="split-reason"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </Field>
        <Button
          type="submit"
          disabled={reason.trim() === "" || submit.isPending}
          data-testid="propose-split"
        >
          {t("stewardship.submit")}
        </Button>
      </form>
      <SubmitResult result={submit.data} error={submit.error} />
    </Panel>
  );
}

function SubmitResult({
  result,
  error,
}: {
  result: StewardshipProposal | null | undefined;
  error: Error | null;
}) {
  const { t } = useI18n();
  if (error) {
    return <p role="alert">{error.message}</p>;
  }
  if (result) {
    return (
      <p role="status" data-testid="proposal-submitted">
        {t("stewardship.submitted")}
      </p>
    );
  }
  return null;
}

/** Stewardship proposals waiting for a second user, with Approve and Reject for those who may decide. */
export function StewardshipProposalsPanel() {
  const { t } = useI18n();
  const session = useSession();
  const queryClient = useQueryClient();
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const proposals = useQuery({
    queryKey: [...PROPOSALS_KEY, "PENDING_APPROVAL"],
    queryFn: () =>
      unwrap(
        api.GET("/api/stewardship/proposals", {
          params: { query: { status: "PENDING_APPROVAL" } },
        }),
      ),
    enabled: session.can("STEWARDSHIP_VIEW"),
  });
  const decide = useMutation({
    mutationFn: ({
      id,
      action,
    }: {
      id: string;
      action: "approve" | "reject";
    }) => {
      const reason = reasons[id]?.trim() || null;
      return action === "approve"
        ? unwrap(
            api.POST("/api/stewardship/proposals/{proposalId}/approve", {
              params: { path: { proposalId: id } },
              body: { reason },
            }),
          ).then(() => undefined)
        : unwrap(
            api.POST("/api/stewardship/proposals/{proposalId}/reject", {
              params: { path: { proposalId: id } },
              body: { reason },
            }),
          ).then(() => undefined);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: PROPOSALS_KEY });
      await queryClient.invalidateQueries({ queryKey: ["reviews"] });
    },
  });
  if (!session.can("STEWARDSHIP_VIEW")) {
    return null;
  }
  const me = (session.user?.username ?? "").toLowerCase();
  return (
    <Panel title={t("stewardship.proposals")}>
      <QueryState query={proposals}>
        {(items) => (
          <DataTable<StewardshipProposal>
            caption={t("stewardship.proposals")}
            testId="stewardship-proposals"
            rows={items.filter(
              (item): item is StewardshipProposal => item !== null,
            )}
            rowKey={(item) => item.proposalId ?? ""}
            emptyText={t("studio.approval.none")}
            columns={[
              {
                key: "action",
                header: t("stewardship.action"),
                render: (item) => (
                  <>
                    <StatusBadge status={item.status ?? ""} /> {item.action}
                  </>
                ),
              },
              {
                key: "who",
                header: t("stewardship.proposedBy"),
                render: (item) =>
                  `${item.proposedBy ?? "—"}: ${item.reason ?? ""}`,
              },
              {
                key: "goldens",
                header: t("stewardship.goldenIds"),
                render: (item) =>
                  (item.goldenResidentIds ?? [])
                    .map((id) => id.slice(0, 8))
                    .join(", "),
              },
              {
                key: "decide",
                header: t("stewardship.decision"),
                render: (item) => {
                  const id = item.proposalId ?? "";
                  if (!session.can("STEWARDSHIP_APPROVE")) {
                    return "—";
                  }
                  if ((item.proposedBy ?? "").toLowerCase() === me) {
                    return t("studio.approval.awaiting");
                  }
                  return (
                    <div className="row">
                      <Field
                        id={`proposal-reason-${id}`}
                        label={t("studio.approval.reasonLabel")}
                      >
                        <TextInput
                          id={`proposal-reason-${id}`}
                          value={reasons[id] ?? ""}
                          onChange={(e) =>
                            setReasons({ ...reasons, [id]: e.target.value })
                          }
                        />
                      </Field>
                      <Button
                        onClick={() => decide.mutate({ id, action: "approve" })}
                        disabled={decide.isPending}
                        data-testid={`approve-proposal-${id}`}
                      >
                        {t("studio.approval.approve")}
                      </Button>
                      <Button
                        variant="secondary"
                        onClick={() => decide.mutate({ id, action: "reject" })}
                        disabled={
                          decide.isPending || (reasons[id] ?? "").trim() === ""
                        }
                        data-testid={`reject-proposal-${id}`}
                      >
                        {t("studio.approval.reject")}
                      </Button>
                    </div>
                  );
                },
              },
            ]}
          />
        )}
      </QueryState>
      {decide.isError && <p role="alert">{decide.error.message}</p>}
    </Panel>
  );
}
