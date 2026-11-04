import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type { StudioApproval } from "../../api/types";
import { useI18n } from "../../i18n/i18n";
import {
  Button,
  DataTable,
  Field,
  PageHeader,
  QueryState,
  StatusBadge,
  TextInput,
} from "../../shared/components";
import { useSession } from "../auth";
import { StewardshipProposalsPanel } from "../stewardship";
import { refreshDraft } from "./hooks";
import styles from "./studio.module.css";

export const APPROVALS_KEY = ["studio", "approvals"];

/**
 * A submitted State Pack version: its status, who submitted and decided it, and — for a different user holding
 * STATE_PACK_APPROVE while it is pending — Approve and Reject (a rejection needs a reason).
 */
export function ApprovalActions({ approval }: { approval: StudioApproval }) {
  const { t } = useI18n();
  const session = useSession();
  const queryClient = useQueryClient();
  const [reason, setReason] = useState("");
  const decide = useMutation({
    mutationFn: (action: "approve" | "reject") =>
      unwrap(
        action === "approve"
          ? api.POST("/api/studio/approvals/{approvalId}/approve", {
              params: { path: { approvalId: approval.approvalId ?? "" } },
              body: { reason: reason.trim() || null },
            })
          : api.POST("/api/studio/approvals/{approvalId}/reject", {
              params: { path: { approvalId: approval.approvalId ?? "" } },
              body: { reason: reason.trim() || null },
            }),
      ),
    onSuccess: async (draft) => {
      setReason("");
      refreshDraft(queryClient, approval.draftId ?? "", draft);
      await queryClient.invalidateQueries({ queryKey: APPROVALS_KEY });
    },
  });
  const pending = approval.status === "PENDING_APPROVAL";
  const ownSubmission =
    (session.user?.username ?? "").toLowerCase() ===
    (approval.submittedBy ?? "").toLowerCase();
  const mayDecide =
    pending && session.can("STATE_PACK_APPROVE") && !ownSubmission;
  return (
    <div className="stack" data-testid={`approval-${approval.packId}`}>
      <p>
        <StatusBadge status={approval.status} />{" "}
        {t("studio.approval.submitted", {
          version: approval.version,
          user: approval.submittedBy,
        })}
        {approval.decidedBy &&
          ` ${t("studio.approval.decided", { user: approval.decidedBy })}`}
      </p>
      {approval.decisionReason && (
        <p className="muted">
          {t("studio.approval.reason", { reason: approval.decisionReason })}
        </p>
      )}
      {pending && ownSubmission && (
        <p className={styles.notice} data-testid="awaiting-other-approver">
          {t("studio.approval.awaiting")}
        </p>
      )}
      {mayDecide && (
        <div className="row">
          <Field
            id={`reason-${approval.approvalId}`}
            label={t("studio.approval.reasonLabel")}
          >
            <TextInput
              id={`reason-${approval.approvalId}`}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </Field>
          <Button
            onClick={() => decide.mutate("approve")}
            disabled={decide.isPending}
            data-testid={`approve-${approval.packId}`}
          >
            {t("studio.approval.approve")}
          </Button>
          <Button
            variant="secondary"
            onClick={() => decide.mutate("reject")}
            disabled={decide.isPending || reason.trim() === ""}
            data-testid={`reject-${approval.packId}`}
          >
            {t("studio.approval.reject")}
          </Button>
        </div>
      )}
      {decide.isError && (
        <p className={styles.error} role="alert">
          {decide.error.message}
        </p>
      )}
    </div>
  );
}

/** The State Pack versions waiting for a second user, with Approve and Reject for those who may decide. */
export function PendingApprovalsPage() {
  const { t } = useI18n();
  const session = useSession();
  // The page also hosts stewardship proposals, so a steward without State Pack access opens it too: ask for pack
  // approvals only when the user may see them, instead of showing a permission error above the proposals.
  const seesPacks = session.can("STATE_PACK_VIEW");
  const approvals = useQuery({
    enabled: seesPacks,
    queryKey: [...APPROVALS_KEY, "PENDING_APPROVAL"],
    queryFn: () =>
      unwrap(
        api.GET("/api/studio/approvals", {
          params: { query: { status: "PENDING_APPROVAL" } },
        }),
      ),
  });
  return (
    <>
      <PageHeader
        title={t("studio.approval.title")}
        description={t("studio.approval.description")}
      />
      {seesPacks && <QueryState query={approvals}>
        {(items) => (
          <DataTable<StudioApproval>
            caption={t("studio.approval.title")}
            testId="pending-approvals"
            rows={items.filter((item): item is StudioApproval => item !== null)}
            rowKey={(item) => item.approvalId ?? ""}
            emptyText={t("studio.approval.none")}
            columns={[
              {
                key: "pack",
                header: t("studio.approval.pack"),
                render: (item) => (
                  <Link to={`/onboarding-studio/drafts/${item.draftId}/8`}>
                    {item.packId} ({item.stateCode})
                  </Link>
                ),
              },
              {
                key: "decision",
                header: t("studio.approval.decision"),
                render: (item) => <ApprovalActions approval={item} />,
              },
            ]}
          />
        )}
      </QueryState>}
      <StewardshipProposalsPanel />
    </>
  );
}
