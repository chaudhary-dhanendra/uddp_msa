import { useState } from "react";
import { useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type {
  BenefitBeneficiary,
  BenefitDetail,
  BenefitObservationView,
  DisbursementView,
} from "../../api/types";
import { useI18n } from "../../i18n/i18n";
import {
  DataTable,
  DescriptionList,
  IdLink,
  JsonValue,
  PageHeader,
  Panel,
  QueryState,
  ShortId,
  StatusBadge,
} from "../../shared/components";
import { PurposeRevealButton, VIEW_PURPOSE_HEADER } from "../resident/RevealButton";

function loadBenefit(id: string, purpose?: string) {
  return unwrap(
    api.GET("/api/v1/benefits/{id}", {
      params: { path: { id } },
      headers: purpose ? { [VIEW_PURPOSE_HEADER]: purpose } : undefined,
    }),
  );
}

/**
 * One benefit: the scheme it belongs to, its beneficiaries with links to their Golden Residents, the household,
 * disbursements and the source observations behind it. Names are masked; RESIDENT_VIEW_FULL can reveal them (audited).
 */
export function BenefitDetailPage() {
  const { t } = useI18n();
  const { id = "" } = useParams();
  // Unmasked data from an audited full view; shown for this view only.
  const [revealed, setRevealed] = useState<BenefitDetail | null>(null);
  const detail = useQuery({ queryKey: ["benefit", id], queryFn: () => loadBenefit(id) });
  return (
    <QueryState query={detail} label={t("benefitDetail.title")}>
      {(masked) => {
        const data = revealed ?? masked;
        const benefit = data.benefit;
        return (
          <>
            <PageHeader
              title={benefit?.schemeName ?? t("benefitDetail.title")}
              description={t("benefitDetail.description")}
              actions={
                revealed ? (
                  <span data-testid="revealed">{t("benefitDetail.revealed")}</span>
                ) : (
                  <PurposeRevealButton<BenefitDetail>
                    reveal={(purpose) => loadBenefit(id, purpose)}
                    onRevealed={setRevealed}
                  />
                )
              }
            />
            <div className="stack">
              <Panel title={t("benefitDetail.benefit")} id="benefit-summary">
                <DescriptionList
                  items={[
                    { label: t("benefitDetail.scheme"), value: `${benefit?.schemeName ?? "—"} (${benefit?.schemeId ?? "—"})` },
                    { label: t("benefitDetail.type"), value: benefit?.benefitType },
                    { label: t("benefitDetail.form"), value: benefit?.benefitForm },
                    { label: t("benefitDetail.level"), value: benefit?.beneficiaryLevel },
                    { label: t("benefitDetail.status"), value: <StatusBadge status={benefit?.status} /> },
                    { label: t("benefitDetail.state"), value: benefit?.stateCode },
                    { label: t("benefitDetail.source"), value: benefit?.sourceSystem },
                    { label: t("benefitDetail.department"), value: benefit?.departmentName },
                    { label: t("benefitDetail.startDate"), value: benefit?.startDate },
                    { label: t("benefitDetail.entitlement"), value: <JsonValue value={benefit?.entitlement} /> },
                  ]}
                />
              </Panel>
              <Panel title={t("benefitDetail.beneficiaries")} id="benefit-beneficiaries">
                <DataTable<BenefitBeneficiary>
                  caption={t("benefitDetail.beneficiaries")}
                  testId="benefit-beneficiaries"
                  rows={data.beneficiaries}
                  rowKey={(row) => row.canonicalBenefitObservationId ?? row.residentRecordKey ?? ""}
                  emptyText={t("benefitDetail.noBeneficiaries")}
                  columns={[
                    { key: "name", header: t("benefitDetail.name"), render: (row) => row.name ?? "—" },
                    {
                      key: "golden",
                      header: t("benefitDetail.golden"),
                      render: (row) => (
                        <IdLink id={row.goldenResidentId} to={`/golden-residents/${row.goldenResidentId ?? ""}`} />
                      ),
                    },
                    {
                      key: "canonical",
                      header: t("benefitDetail.canonical"),
                      render: (row) => (
                        <IdLink
                          id={row.canonicalResidentId}
                          to={`/canonical-residents/${row.canonicalResidentId ?? ""}`}
                        />
                      ),
                    },
                    { key: "status", header: t("benefitDetail.status"), render: (row) => <StatusBadge status={row.status} /> },
                  ]}
                />
              </Panel>
              {data.household && (
                <Panel title={t("benefitDetail.household")} id="benefit-household">
                  <DescriptionList
                    items={[
                      { label: t("benefitDetail.sourceFamily"), value: data.household.sourceFamilyId },
                      {
                        label: t("benefitDetail.goldenFamily"),
                        value: (
                          <IdLink
                            id={data.household.goldenFamilyId}
                            to={`/families/${data.household.goldenFamilyId ?? ""}`}
                          />
                        ),
                      },
                      { label: t("benefitDetail.members"), value: data.household.memberGoldenResidentIds.length },
                    ]}
                  />
                </Panel>
              )}
              <Panel title={t("benefitDetail.disbursements")} id="benefit-disbursements">
                <DataTable<DisbursementView>
                  caption={t("benefitDetail.disbursements")}
                  testId="benefit-disbursements"
                  rows={data.disbursements}
                  rowKey={(row) => `${row.canonicalBenefitObservationId ?? ""}:${row.disbursedOn ?? ""}:${row.reference ?? ""}`}
                  emptyText={t("benefitDetail.noDisbursements")}
                  columns={[
                    { key: "on", header: t("benefitDetail.disbursedOn"), render: (row) => row.disbursedOn ?? "—" },
                    { key: "amount", header: t("benefitDetail.amount"), align: "end", render: (row) => row.amount ?? "—" },
                    {
                      key: "quantity",
                      header: t("benefitDetail.quantity"),
                      align: "end",
                      render: (row) => (row.quantity === null ? "—" : `${row.quantity} ${row.unit ?? ""}`),
                    },
                    { key: "reference", header: t("benefitDetail.reference"), render: (row) => row.reference ?? "—" },
                  ]}
                />
              </Panel>
              <Panel title={t("benefitDetail.observations")} id="benefit-observations">
                <DataTable<BenefitObservationView>
                  caption={t("benefitDetail.observations")}
                  testId="benefit-observations"
                  rows={data.observations}
                  rowKey={(row) => row.canonicalBenefitObservationId ?? ""}
                  columns={[
                    { key: "observed", header: t("benefitDetail.observedAt"), render: (row) => row.observedAt ?? "—" },
                    { key: "status", header: t("benefitDetail.status"), render: (row) => <StatusBadge status={row.benefitStatus} /> },
                    { key: "raw", header: t("benefitDetail.rawRecord"), render: (row) => <ShortId id={row.rawRecordId} /> },
                    { key: "pack", header: t("benefitDetail.packVersion"), render: (row) => row.statePackVersion },
                    {
                      key: "current",
                      header: t("benefitDetail.current"),
                      render: (row) => <StatusBadge status={row.current ? "CURRENT" : "SUPERSEDED"} />,
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
