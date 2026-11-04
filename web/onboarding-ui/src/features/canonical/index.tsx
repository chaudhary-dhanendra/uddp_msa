import { useState } from "react";
import { useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type {
  CanonicalResidentDetail,
  CanonicalVersionView,
  DqFinding,
  FieldMapping,
  ValidationWarningView,
} from "../../api/types";
import { useI18n } from "../../i18n/i18n";
import {
  DataTable,
  DescriptionList,
  IdLink,
  JsonBlock,
  JsonValue,
  PageHeader,
  Panel,
  QueryState,
  ShortId,
  StatusBadge,
} from "../../shared/components";
import { PurposeRevealButton, VIEW_PURPOSE_HEADER } from "../resident/RevealButton";

function loadDetail(id: string, purpose?: string) {
  return unwrap(
    api.GET("/api/v1/canonical-residents/{id}", {
      params: { path: { id } },
      headers: purpose ? { [VIEW_PURPOSE_HEADER]: purpose } : undefined,
    }),
  );
}

/**
 * One canonical Resident: where it came from (state, source, batch, State Pack version), each source value against
 * the canonical value it became, extensions, data-quality warnings and errors, lineage to the raw record, batch and
 * Golden Resident, and its version history. Personal data is masked; RESIDENT_VIEW_FULL can reveal it (audited).
 */
export function CanonicalRecordPage() {
  const { t } = useI18n();
  const { id = "" } = useParams();
  // Unmasked data from an audited full view; shown for this view only.
  const [revealed, setRevealed] = useState<CanonicalResidentDetail | null>(null);
  const detail = useQuery({
    queryKey: ["canonical-resident", id],
    queryFn: () => loadDetail(id),
  });
  return (
    <QueryState query={detail} label={t("canonicalDetail.title")}>
      {(masked) => {
        const data = revealed ?? masked;
        const summary = data.summary;
        return (
          <>
            <PageHeader
              title={summary?.name ?? t("canonicalDetail.title")}
              description={t("canonicalDetail.description")}
              actions={
                revealed ? (
                  <span data-testid="revealed">{t("canonicalDetail.revealed")}</span>
                ) : (
                  <PurposeRevealButton<CanonicalResidentDetail>
                    reveal={(purpose) => loadDetail(id, purpose)}
                    onRevealed={setRevealed}
                  />
                )
              }
            />
            <div className="stack">
              <Panel title={t("canonicalDetail.record")} id="canonical-record">
                <DescriptionList
                  items={[
                    { label: t("canonicalDetail.state"), value: summary?.stateCode },
                    { label: t("canonicalDetail.source"), value: summary?.sourceSystem },
                    { label: t("canonicalDetail.entity"), value: summary?.sourceEntity },
                    { label: t("canonicalDetail.batch"), value: <ShortId id={summary?.batchId} /> },
                    { label: t("canonicalDetail.packVersion"), value: summary?.statePackVersion },
                    {
                      label: t("canonicalDetail.status"),
                      value: <StatusBadge status={summary?.current ? "CURRENT" : "SUPERSEDED"} />,
                    },
                    { label: t("canonicalDetail.version"), value: summary?.versionNumber },
                    { label: t("canonicalDetail.recordKey"), value: summary?.sourceRecordKey },
                  ]}
                />
              </Panel>
              <Panel title={t("canonicalDetail.values")} id="canonical-values">
                <DataTable<FieldMapping>
                  caption={t("canonicalDetail.values")}
                  testId="canonical-values"
                  rows={data.fieldMappings}
                  rowKey={(row) => `${row.sourceField ?? ""}>${row.target ?? ""}`}
                  emptyText={t("canonicalDetail.noMappings")}
                  columns={[
                    { key: "sourceField", header: t("canonicalDetail.sourceField"), render: (row) => row.sourceField },
                    { key: "sourceValue", header: t("canonicalDetail.sourceValue"), render: (row) => row.sourceValue ?? "—" },
                    { key: "target", header: t("canonicalDetail.target"), render: (row) => row.target },
                    {
                      key: "canonicalValue",
                      header: t("canonicalDetail.canonicalValue"),
                      render: (row) => <JsonValue value={row.canonicalValue} />,
                    },
                    {
                      key: "rule",
                      header: t("canonicalDetail.rule"),
                      render: (row) => row.crosswalk ?? row.conversion ?? "—",
                    },
                  ]}
                />
              </Panel>
              <Panel title={t("canonicalDetail.extensions")} id="canonical-extensions">
                <JsonBlock value={data.extensions} label={t("canonicalDetail.extensions")} />
              </Panel>
              <Panel title={t("canonicalDetail.quality")} id="canonical-quality">
                <DataTable<ValidationWarningView>
                  caption={t("canonicalDetail.warnings")}
                  testId="canonical-warnings"
                  rows={data.warnings}
                  rowKey={(row) => `${row.fieldPath ?? ""}:${row.rule ?? ""}`}
                  emptyText={t("canonicalDetail.noWarnings")}
                  columns={[
                    { key: "field", header: t("canonicalDetail.field"), render: (row) => row.fieldPath },
                    { key: "rule", header: t("canonicalDetail.rule"), render: (row) => row.rule },
                    { key: "message", header: t("canonicalDetail.message"), render: (row) => row.message },
                  ]}
                />
                <DataTable<DqFinding>
                  caption={t("canonicalDetail.findings")}
                  testId="canonical-findings"
                  rows={data.dqFindings}
                  rowKey={(row) => row.dqResultId ?? `${row.ruleId ?? ""}:${row.fieldPath ?? ""}`}
                  emptyText={t("canonicalDetail.noFindings")}
                  columns={[
                    {
                      key: "severity",
                      header: t("canonicalDetail.severity"),
                      render: (row) => <StatusBadge status={row.severity} />,
                    },
                    { key: "rule", header: t("canonicalDetail.rule"), render: (row) => row.ruleId },
                    { key: "field", header: t("canonicalDetail.field"), render: (row) => row.fieldPath },
                    { key: "message", header: t("canonicalDetail.message"), render: (row) => row.message },
                  ]}
                />
              </Panel>
              <Panel title={t("canonicalDetail.lineage")} id="canonical-lineage">
                <DescriptionList
                  items={[
                    {
                      label: t("canonicalDetail.golden"),
                      value: (
                        <IdLink
                          id={summary?.goldenResidentId}
                          to={`/golden-residents/${summary?.goldenResidentId ?? ""}`}
                          label={t("canonicalDetail.golden")}
                        />
                      ),
                    },
                    { label: t("canonicalDetail.rawRecord"), value: <ShortId id={data.raw?.rawRecordId} /> },
                    { label: t("canonicalDetail.sourceRecordId"), value: data.raw?.sourceRecordId },
                    {
                      label: t("canonicalDetail.ingestedAt"),
                      value: data.raw?.ingestedAt ? new Date(data.raw.ingestedAt).toLocaleString() : "—",
                    },
                    { label: t("canonicalDetail.pipeline"), value: data.raw?.pipelineTemplate },
                    { label: t("canonicalDetail.batch"), value: <ShortId id={data.batch?.batchId} /> },
                    { label: t("canonicalDetail.batchStatus"), value: <StatusBadge status={data.batch?.status} /> },
                    {
                      label: t("canonicalDetail.batchRecords"),
                      value: data.batch ? `${data.batch.rawRecords} / ${data.batch.canonicalResidents}` : "—",
                    },
                  ]}
                />
              </Panel>
              <Panel title={t("canonicalDetail.versions")} id="canonical-versions">
                <DataTable<CanonicalVersionView>
                  caption={t("canonicalDetail.versions")}
                  testId="canonical-versions"
                  rows={data.versions}
                  rowKey={(row) => row.canonicalResidentId ?? String(row.versionNumber)}
                  columns={[
                    { key: "version", header: t("canonicalDetail.version"), render: (row) => row.versionNumber },
                    {
                      key: "record",
                      header: t("canonicalDetail.record"),
                      render: (row) =>
                        row.canonicalResidentId === id ? (
                          <ShortId id={row.canonicalResidentId} />
                        ) : (
                          <IdLink
                            id={row.canonicalResidentId}
                            to={`/canonical-residents/${row.canonicalResidentId ?? ""}`}
                          />
                        ),
                    },
                    {
                      key: "status",
                      header: t("canonicalDetail.status"),
                      render: (row) => <StatusBadge status={row.current ? "CURRENT" : "SUPERSEDED"} />,
                    },
                    { key: "pack", header: t("canonicalDetail.packVersion"), render: (row) => row.statePackVersion },
                    {
                      key: "created",
                      header: t("canonicalDetail.createdAt"),
                      render: (row) => (row.createdAt ? new Date(row.createdAt).toLocaleString() : "—"),
                    },
                  ]}
                />
              </Panel>
            </div>
          </>
        );
      }}
    </QueryState>
  );
}
