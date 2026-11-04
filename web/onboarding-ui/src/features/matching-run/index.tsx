import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type { FieldComparison, MatchPairDetail, MatchPairSummary } from "../../api/types";
import { useI18n } from "../../i18n/i18n";
import { useListParams } from "../../shared/hooks";
import {
  Button,
  DataTable,
  DescriptionList,
  Field,
  FilterBar,
  IdLink,
  PageHeader,
  Pagination,
  Panel,
  QueryState,
  Select,
  StatCard,
  StatGrid,
  StatusBadge,
} from "../../shared/components";
import { PurposeRevealButton, VIEW_PURPOSE_HEADER } from "../resident/RevealButton";

const DECISIONS = ["AUTO_MATCH", "POSSIBLE_MATCH", "NO_MATCH"];

interface PairKey {
  a: string;
  b: string;
}

function percent(probability: number | null | undefined): string {
  return probability === null || probability === undefined ? "—" : `${(probability * 100).toFixed(1)}%`;
}

function loadPair(runId: string, pair: PairKey, purpose?: string) {
  return unwrap(
    api.GET("/api/v1/matching-runs/{runId}/pairs/{canonicalA}/{canonicalB}", {
      params: { path: { runId, canonicalA: pair.a, canonicalB: pair.b } },
      headers: purpose ? { [VIEW_PURPOSE_HEADER]: purpose } : undefined,
    }),
  );
}

/** One pair side by side: every compared field, its outcome and the match probability. */
function PairComparison({ runId, pair }: { runId: string; pair: PairKey }) {
  const { t } = useI18n();
  // Unmasked data from an audited full view; shown for this view only.
  const [revealed, setRevealed] = useState<MatchPairDetail | null>(null);
  const detail = useQuery({
    queryKey: ["matching-pair", runId, pair.a, pair.b],
    queryFn: () => loadPair(runId, pair),
  });
  return (
    <Panel
      title={t("matchingRun.comparison")}
      id="pair-comparison"
      actions={
        revealed ? (
          <span data-testid="revealed">{t("matchingRun.revealed")}</span>
        ) : (
          <PurposeRevealButton<MatchPairDetail>
            reveal={(purpose) => loadPair(runId, pair, purpose)}
            onRevealed={setRevealed}
          />
        )
      }
    >
      <p>
        <Link to={`/matching-studio/why/${runId}/${pair.a}/${pair.b}`}>
          Why was this pair matched?
        </Link>
      </p>
      <QueryState query={detail} label={t("matchingRun.comparison")}>
        {(masked) => {
          const data = revealed ?? masked;
          return (
            <>
              <DescriptionList
                items={[
                  { label: t("matchingRun.probability"), value: percent(data.pair?.matchProbability) },
                  { label: t("matchingRun.decision"), value: <StatusBadge status={data.pair?.decision} /> },
                  {
                    label: t("matchingRun.recordA"),
                    value: `${data.recordA?.name ?? "—"} (${data.recordA?.stateCode ?? "—"})`,
                  },
                  {
                    label: t("matchingRun.recordB"),
                    value: `${data.recordB?.name ?? "—"} (${data.recordB?.stateCode ?? "—"})`,
                  },
                ]}
              />
              <DataTable<FieldComparison>
                caption={t("matchingRun.comparison")}
                testId="pair-comparison-table"
                rows={data.comparisons}
                rowKey={(row) => row.field ?? ""}
                columns={[
                  { key: "field", header: t("matchingRun.field"), render: (row) => row.field },
                  { key: "a", header: t("matchingRun.valueA"), render: (row) => row.valueA ?? "—" },
                  { key: "b", header: t("matchingRun.valueB"), render: (row) => row.valueB ?? "—" },
                  {
                    key: "outcome",
                    header: t("matchingRun.outcome"),
                    render: (row) => <StatusBadge status={row.outcome} />,
                  },
                  { key: "basis", header: t("matchingRun.basis"), render: (row) => row.basis ?? "—" },
                ]}
              />
            </>
          );
        }}
      </QueryState>
    </Panel>
  );
}

/**
 * One matching run: model, thresholds and pair counts by decision, then its pairs filtered by decision; choosing a
 * pair opens the side-by-side field comparison. Names are masked; RESIDENT_VIEW_FULL can reveal a pair (audited).
 */
export function MatchingRunPage() {
  const { t } = useI18n();
  const { id = "" } = useParams();
  const params = useListParams();
  const decision = params.get("decision");
  const [selected, setSelected] = useState<PairKey | null>(null);
  const run = useQuery({
    queryKey: ["matching-run", id],
    queryFn: () => unwrap(api.GET("/api/v1/matching-runs/{runId}", { params: { path: { runId: id } } })),
  });
  const pairs = useQuery({
    queryKey: ["matching-run", id, "pairs", decision, params.page],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/matching-runs/{runId}/pairs", {
          params: {
            path: { runId: id },
            query: {
              decision: decision === "" ? undefined : decision,
              page: params.page,
              size: 20,
              sort: "matchProbability,desc",
            },
          },
        }),
      ),
  });
  return (
    <>
      <PageHeader title={t("matchingRun.title")} description={t("matchingRun.description")} />
      <div className="stack">
        <QueryState query={run} label={t("matchingRun.title")}>
          {(data) => {
            const summary = data.run;
            return (
              <Panel title={t("matchingRun.summary")} id="run-summary">
                <StatGrid label={t("matchingRun.counts")}>
                  <StatCard label="AUTO_MATCH" value={summary?.pairs?.autoMatch ?? 0} testId="count-auto" />
                  <StatCard label="POSSIBLE_MATCH" value={summary?.pairs?.possibleMatch ?? 0} testId="count-possible" />
                  <StatCard label="NO_MATCH" value={summary?.pairs?.noMatch ?? 0} testId="count-no" />
                  <StatCard label={t("matchingRun.clusters")} value={summary?.clusters ?? 0} />
                </StatGrid>
                <DescriptionList
                  items={[
                    { label: t("matchingRun.model"), value: summary?.modelVersion },
                    { label: t("matchingRun.mode"), value: summary?.mode },
                    { label: t("matchingRun.status"), value: <StatusBadge status={summary?.status} /> },
                    { label: t("matchingRun.autoThreshold"), value: summary?.autoMatchThreshold },
                    { label: t("matchingRun.possibleThreshold"), value: summary?.possibleMatchThreshold },
                    { label: t("matchingRun.records"), value: summary?.recordsConsidered },
                    {
                      label: t("matchingRun.started"),
                      value: summary?.startedAt ? new Date(summary.startedAt).toLocaleString() : "—",
                    },
                    {
                      label: t("matchingRun.goldenBuilds"),
                      value: data.goldenBuilds.length,
                    },
                  ]}
                />
              </Panel>
            );
          }}
        </QueryState>
        <Panel title={t("matchingRun.pairs")} id="run-pairs">
          <FilterBar label={t("matchingRun.filters")}>
            <Field id="pair-decision" label={t("matchingRun.decision")}>
              <Select
                id="pair-decision"
                value={decision}
                onChange={(event) => {
                  setSelected(null);
                  params.set({ decision: event.target.value });
                }}
                options={[
                  { value: "", label: t("matchingRun.anyDecision") },
                  ...DECISIONS.map((value) => ({ value, label: value })),
                ]}
              />
            </Field>
          </FilterBar>
          <QueryState query={pairs} label={t("matchingRun.pairs")}>
            {(page) => (
              <>
                <DataTable<MatchPairSummary>
                  caption={t("matchingRun.pairs")}
                  testId="pairs-table"
                  rows={page.items}
                  rowKey={(pair) => `${pair.canonicalRecordA ?? ""}:${pair.canonicalRecordB ?? ""}`}
                  emptyText={t("matchingRun.noPairs")}
                  columns={[
                    {
                      key: "a",
                      header: t("matchingRun.recordA"),
                      render: (pair) => (
                        <>
                          {pair.nameA ?? "—"} ({pair.stateCodeA ?? "—"}){" "}
                          <IdLink id={pair.canonicalRecordA} to={`/canonical-residents/${pair.canonicalRecordA ?? ""}`} />
                        </>
                      ),
                    },
                    {
                      key: "b",
                      header: t("matchingRun.recordB"),
                      render: (pair) => (
                        <>
                          {pair.nameB ?? "—"} ({pair.stateCodeB ?? "—"}){" "}
                          <IdLink id={pair.canonicalRecordB} to={`/canonical-residents/${pair.canonicalRecordB ?? ""}`} />
                        </>
                      ),
                    },
                    {
                      key: "probability",
                      header: t("matchingRun.probability"),
                      align: "end",
                      render: (pair) => percent(pair.matchProbability),
                    },
                    {
                      key: "decision",
                      header: t("matchingRun.decision"),
                      render: (pair) => <StatusBadge status={pair.decision} />,
                    },
                    {
                      key: "compare",
                      header: t("matchingRun.compare"),
                      render: (pair) => (
                        <Button
                          variant="secondary"
                          onClick={() =>
                            setSelected({ a: pair.canonicalRecordA ?? "", b: pair.canonicalRecordB ?? "" })
                          }
                        >
                          {t("matchingRun.compare")}
                        </Button>
                      ),
                    },
                  ]}
                />
                <Pagination page={page} onPage={params.setPage} label={t("matchingRun.pairs")} />
              </>
            )}
          </QueryState>
        </Panel>
        {selected && <PairComparison key={`${selected.a}:${selected.b}`} runId={id} pair={selected} />}
      </div>
    </>
  );
}
