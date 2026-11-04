import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import { useI18n } from "../../i18n/i18n";
import {
  DataTable,
  PageHeader,
  Panel,
  QueryState,
  StatCard,
  StatGrid,
} from "../../shared/components";
import { studioKeys } from "./hooks";
import styles from "./studio.module.css";

/** Portability evidence: per state, what the Studio generated and how much platform code changed (from git). */
export function PortabilityPage() {
  const { t, dateTime } = useI18n();
  const evidence = useQuery({
    queryKey: studioKeys.portability,
    queryFn: () => unwrap(api.GET("/api/studio/portability")),
    staleTime: 0,
  });
  return (
    <>
      <PageHeader
        title={t("studio.portability.title")}
        description={t("studio.portability.description")}
      />
      <QueryState query={evidence}>
        {(e) => {
          const totalChanges = e.states.reduce(
            (sum, s) => sum + s.platformCodeChanges.length,
            0,
          );
          return (
            <div className="stack">
              <StatGrid label={t("studio.portability.title")}>
                <StatCard
                  label={t("studio.portability.statesOnboarded")}
                  value={e.states.length}
                  testId="evidence-states"
                />
                <StatCard
                  label={t("studio.portability.changes")}
                  value={
                    <span
                      className={totalChanges === 0 ? styles.zero : undefined}
                    >
                      {totalChanges}
                    </span>
                  }
                  testId="evidence-code-changes"
                />
                <StatCard
                  label={t("studio.portability.files")}
                  value={e.states.reduce(
                    (sum, s) => sum + s.generatedFiles.length,
                    0,
                  )}
                />
              </StatGrid>
              {e.gitError && (
                <p className={styles.error} role="alert">
                  {t("studio.portability.gitError", { message: e.gitError })}
                </p>
              )}
              <Panel title={t("studio.portability.title")}>
                <DataTable
                  caption={t("studio.portability.title")}
                  testId="evidence-table"
                  rows={e.states}
                  rowKey={(s) => s.packId ?? ""}
                  columns={[
                    {
                      key: "state",
                      header: t("studio.portability.state"),
                      render: (s) => `${s.displayName} (${s.stateCode})`,
                    },
                    {
                      key: "files",
                      header: t("studio.portability.files"),
                      render: (s) => (
                        <details>
                          <summary>{s.generatedFiles.length}</summary>
                          <ul className={styles.values}>
                            {s.generatedFiles.map((f) => (
                              <li key={f}>
                                {s.packId}/{f}
                              </li>
                            ))}
                          </ul>
                        </details>
                      ),
                    },
                    {
                      key: "templates",
                      header: t("studio.portability.templates"),
                      render: (s) => <code>{s.hopTemplates.join(", ")}</code>,
                    },
                    {
                      key: "commit",
                      header: t("studio.portability.commit"),
                      render: (s) => (
                        <code>
                          {s.platformCommitAtPublish?.slice(0, 12) ?? "—"}
                        </code>
                      ),
                    },
                    {
                      key: "changes",
                      header: t("studio.portability.changes"),
                      render: (s) =>
                        s.platformCodeChanges.length === 0 ? (
                          <span
                            className={styles.zero}
                            data-testid={`changes-${s.packId}`}
                          >
                            {t("studio.portability.zero")}
                          </span>
                        ) : (
                          <ul data-testid={`changes-${s.packId}`}>
                            {s.platformCodeChanges.map((f) => (
                              <li key={f}>
                                <code>{f}</code>
                              </li>
                            ))}
                          </ul>
                        ),
                    },
                  ]}
                />
              </Panel>
              <Panel title={t("studio.portability.guard")}>
                <p data-testid="guard-result">
                  {e.guard
                    ? t("studio.portability.guardResult", {
                        tests: e.guard.tests,
                        failures: e.guard.failures,
                        errors: e.guard.errors,
                        time: dateTime(e.guard.reportTime),
                      })
                    : t("studio.portability.guardMissing")}
                </p>
                <p className="muted">
                  {t("studio.portability.baseline", {
                    ref: e.baselineRef ?? "",
                    commit: e.baselineCommit?.slice(0, 12) ?? "—",
                  })}{" "}
                  ·{" "}
                  {t("studio.portability.current", {
                    commit: e.currentCommit?.slice(0, 12) ?? "—",
                  })}
                </p>
                <h3>
                  {t("studio.portability.changedSinceBaseline", {
                    ref: e.baselineRef ?? "",
                  })}
                </h3>
                {e.changedSinceBaseline.length === 0 ? (
                  <p className={styles.zero}>
                    {t("studio.portability.noneChanged")}
                  </p>
                ) : (
                  <details>
                    <summary>{e.changedSinceBaseline.length}</summary>
                    <ul className={styles.values}>
                      {e.changedSinceBaseline.map((f) => (
                        <li key={f}>{f}</li>
                      ))}
                    </ul>
                  </details>
                )}
              </Panel>
            </div>
          );
        }}
      </QueryState>
    </>
  );
}
