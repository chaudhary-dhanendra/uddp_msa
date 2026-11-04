import { useState } from "react";
import { useI18n } from "../../i18n/i18n";
import { Button, Panel, QueryState, StatusBadge, TextInput } from "../../shared/components";
import { useSession } from "../auth";
import { useDecideChange, usePendingChanges, type ShapingChange } from "./api";

function describe(change: ShapingChange, t: ReturnType<typeof useI18n>["t"]): string {
  return change.kind === "WORD_LIST"
    ? t("shaping.queue.wordList", { name: change.target })
    : t("shaping.queue.plugin", {
        action: change.action === "APPROVE" ? t("shaping.plugins.approve") : t("shaping.plugins.revoke"),
        name: String(change.payload.name ?? change.payload.fileName ?? change.target.slice(0, 12)),
      });
}

/** Changes waiting for approval, with Approve / Reject for a user who may decide and did not propose them. */
export function ChangeQueue({ kind }: { kind: "WORD_LIST" | "PLUGIN" }) {
  const { t } = useI18n();
  const session = useSession();
  const pending = usePendingChanges(kind);
  const decide = useDecideChange();
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const canDecide = session.can("SHAPING_APPROVE");
  return (
    <Panel title={t("shaping.queue.title")} id={`queue-${kind}`}>
      <QueryState query={pending} emptyText={t("shaping.queue.empty")} isEmpty={(rows) => rows.length === 0}>
        {(rows) => (
          <ul style={{ listStyle: "none", padding: 0 }} data-testid="change-queue">
            {rows.map((change) => {
              const own = change.createdBy === session.user?.username;
              return (
                <li key={change.changeId}>
                  <strong>{describe(change, t)}</strong>{" "}
                  <StatusBadge status={change.status} />{" "}
                  <span className="muted">{t("shaping.queue.proposedBy", { user: change.createdBy })}</span>
                  {canDecide && !own && (
                    <div className="row">
                      <TextInput
                        aria-label={t("shaping.queue.reason")}
                        placeholder={t("shaping.queue.reason")}
                        value={reasons[change.changeId] ?? ""}
                        onChange={(e) => setReasons({ ...reasons, [change.changeId]: e.target.value })}
                      />
                      <Button
                        onClick={() => decide.mutate({ changeId: change.changeId, decision: "approve" })}
                        disabled={decide.isPending}
                      >
                        {t("shaping.queue.approve")}
                      </Button>
                      <Button
                        variant="secondary"
                        onClick={() =>
                          decide.mutate({
                            changeId: change.changeId,
                            decision: "reject",
                            reason: reasons[change.changeId],
                          })
                        }
                        disabled={decide.isPending || !(reasons[change.changeId] ?? "").trim()}
                      >
                        {t("shaping.queue.reject")}
                      </Button>
                    </div>
                  )}
                  {canDecide && own && <span className="muted"> {t("shaping.queue.ownChange")}</span>}
                </li>
              );
            })}
          </ul>
        )}
      </QueryState>
      {decide.error && (
        <p role="alert" data-testid="decision-error">
          {decide.error.message}
        </p>
      )}
    </Panel>
  );
}
