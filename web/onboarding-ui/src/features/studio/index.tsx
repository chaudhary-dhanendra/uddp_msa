import { Link, useNavigate, useParams } from "react-router-dom";
import { useI18n } from "../../i18n/i18n";
import {
  DataTable,
  PageHeader,
  Panel,
  QueryState,
  StatusBadge,
} from "../../shared/components";
import type { StudioStateEntry } from "../../api/types";
import {
  departmentLabel,
  usePackDepartments,
  type PackDepartment,
} from "../departments";
import { useDraft, useStudioStates, useStudioStatus } from "./hooks";
import { DetailsStep } from "./steps/DetailsStep";
import { UploadStep } from "./steps/UploadStep";
import { ShapeStep } from "./steps/ShapeStep";
import { MapStep } from "./steps/MapStep";
import { CrosswalkStep } from "./steps/CrosswalkStep";
import { RulesStep } from "./steps/RulesStep";
import { PreviewStep } from "./steps/PreviewStep";
import { PublishStep } from "./steps/PublishStep";
import { DoneStep, OnboardStep } from "./steps/OnboardStep";
import styles from "./studio.module.css";

export { PortabilityPage } from "./PortabilityPage";

export const STEP_COUNT = 10;

export function draftPath(draftId: string, step: number) {
  return `/onboarding-studio/drafts/${draftId}/${step}`;
}

/** Sources grouped by owning department; sources without a department come last. */
function groupByDepartment(
  rows: StudioStateEntry[],
  departments: Map<string, PackDepartment>,
): {
  key: string;
  department: PackDepartment | undefined;
  rows: StudioStateEntry[];
}[] {
  const groups = new Map<
    string,
    {
      key: string;
      department: PackDepartment | undefined;
      rows: StudioStateEntry[];
    }
  >();
  for (const row of rows) {
    const department = departments.get(row.packId ?? "");
    const key = department?.code ?? "";
    const group = groups.get(key) ?? { key, department, rows: [] };
    group.rows.push(row);
    groups.set(key, group);
  }
  return [...groups.values()].sort((a, b) => {
    if (a.key === "" || b.key === "") {
      return a.key === "" ? 1 : -1;
    }
    return a.key.localeCompare(b.key);
  });
}

/** The Studio's Sources list: every source, under its department, with its draft / published / onboarded status. */
export function StudioStatesPage() {
  const { t, dateTime } = useI18n();
  const states = useStudioStates();
  const status = useStudioStatus();
  const departments = usePackDepartments();
  return (
    <>
      <PageHeader
        title={t("studio.title")}
        description={t("studio.description")}
        actions={
          <Link
            className="buttonLink"
            to="/onboarding-studio/new"
            data-testid="new-state"
          >
            {t("studio.newState")}
          </Link>
        }
      />
      {status.data && !status.data.demoMode && (
        <p className={styles.notice} role="note">
          {t("studio.demoModeOff", {
            directory: status.data.studioPacksDirectory,
          })}
        </p>
      )}
      <Panel title={t("studio.states")}>
        <QueryState
          query={states}
          isEmpty={(rows) => rows.length === 0}
          emptyText={t("studio.noStates")}
        >
          {(allRows) =>
            groupByDepartment(allRows, departments).map(
              ({ key, department, rows }) => (
                <DataTable
                  key={key}
                  caption={departmentLabel(department, t("studio.unassigned"))}
                  rows={rows}
                  rowKey={(r) => r.draftId ?? ""}
                  columns={[
                    {
                      key: "state",
                      header: t("studio.col.state"),
                      render: (r) => (
                        <Link
                          to={draftPath(
                            r.draftId ?? "",
                            r.status === "ONBOARDED" ? 9 : 2,
                          )}
                        >
                          {r.displayName} ({r.stateCode})
                        </Link>
                      ),
                    },
                    {
                      key: "pack",
                      header: t("studio.col.pack"),
                      render: (r) => <code>{r.packId}</code>,
                    },
                    {
                      key: "status",
                      header: t("studio.col.status"),
                      render: (r) => (
                        <span data-testid={`state-status-${r.packId}`}>
                          <StatusBadge status={r.status} />
                        </span>
                      ),
                    },
                    {
                      key: "version",
                      header: t("studio.col.version"),
                      render: (r) => r.version ?? "—",
                    },
                    {
                      key: "source",
                      header: t("studio.col.source"),
                      render: (r) => r.sourceFile ?? "—",
                    },
                    {
                      key: "run",
                      header: t("studio.col.run"),
                      render: (r) => <StatusBadge status={r.lastRunStatus} />,
                    },
                    {
                      key: "updated",
                      header: t("studio.col.updated"),
                      render: (r) => dateTime(r.updatedAt),
                    },
                  ]}
                />
              ),
            )
          }
        </QueryState>
      </Panel>
    </>
  );
}

/** Step 1 for a source that has no draft yet. */
export function NewStatePage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  return (
    <>
      <PageHeader
        title={t("studio.newState")}
        description={t("studio.description")}
      />
      <Stepper current={1} />
      <DetailsStep
        draft={null}
        onDone={(draft) => navigate(draftPath(draft.draftId ?? "", 2))}
      />
    </>
  );
}

/** The onboarding wizard for one draft; the step is in the URL so every step can be bookmarked and resumed. */
export function StudioWizardPage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { draftId = "", step = "2" } = useParams();
  const current = Math.min(
    STEP_COUNT,
    Math.max(1, Number.parseInt(step, 10) || 2),
  );
  const draft = useDraft(draftId);
  const go = (next: number) => navigate(draftPath(draftId, next));
  return (
    <QueryState query={draft}>
      {(d) => (
        <>
          <PageHeader
            title={`${d.displayName} (${d.stateCode})`}
            description={t("studio.stepOf", { step: current })}
            actions={<StatusBadge status={d.status} />}
          />
          <Stepper current={current} draftId={draftId} />
          {current === 1 && <DetailsStep draft={d} onDone={() => go(2)} />}
          {current === 2 && (
            <UploadStep draft={d} onBack={() => go(1)} onDone={() => go(3)} />
          )}
          {current === 3 && (
            <ShapeStep draft={d} onBack={() => go(2)} onDone={() => go(4)} />
          )}
          {current === 4 && (
            <MapStep draft={d} onBack={() => go(3)} onDone={() => go(5)} />
          )}
          {current === 5 && (
            <CrosswalkStep
              draft={d}
              onBack={() => go(4)}
              onDone={() => go(6)}
            />
          )}
          {current === 6 && (
            <RulesStep draft={d} onBack={() => go(5)} onDone={() => go(7)} />
          )}
          {current === 7 && (
            <PreviewStep draft={d} onBack={() => go(6)} onDone={() => go(8)} />
          )}
          {current === 8 && (
            <PublishStep draft={d} onBack={() => go(7)} onDone={() => go(9)} />
          )}
          {current === 9 && (
            <OnboardStep draft={d} onBack={() => go(8)} onDone={() => go(10)} />
          )}
          {current === 10 && <DoneStep draft={d} />}
        </>
      )}
    </QueryState>
  );
}

function Stepper({ current, draftId }: { current: number; draftId?: string }) {
  const { t } = useI18n();
  return (
    <ol className={styles.stepper} aria-label={t("studio.title")}>
      {Array.from({ length: STEP_COUNT }, (_, i) => i + 1).map((n) => {
        const content = (
          <>
            <span className={styles.stepNumber}>{n}</span>
            {t(`studio.steps.${n}`)}
          </>
        );
        return (
          <li key={n}>
            {draftId ? (
              <Link
                to={draftPath(draftId, n)}
                className={styles.step}
                aria-current={n === current ? "step" : undefined}
                data-testid={`step-${n}`}
              >
                {content}
              </Link>
            ) : (
              <span
                className={styles.step}
                aria-current={n === current ? "step" : undefined}
              >
                {content}
              </span>
            )}
          </li>
        );
      })}
    </ol>
  );
}
