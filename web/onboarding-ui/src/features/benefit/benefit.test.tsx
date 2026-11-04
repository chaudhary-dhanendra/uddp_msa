import { afterEach, describe, expect, it, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import type { BenefitDetail } from "../../api/types";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";

const ID = "99999999-9999-9999-9999-999999999999";
const GOLDEN = "22222222-2222-2222-2222-222222222222";

const DETAIL: BenefitDetail = {
  benefit: {
    benefitId: ID, schemeId: "PDS-RATION", schemeName: "Public distribution ration", benefitType: "IN_KIND",
    benefitForm: "RATION", beneficiaryLevel: "FAMILY", status: "ACTIVE", stateCode: "XX", sourceSystem: "pds",
    departmentCode: "FOOD", departmentName: "Food and civil supplies", startDate: "2024-01-01", entitlement: { riceKg: 35 },
    benefitRecordKey: "XX|pds|R1001", createdAt: null, exclusivityGroup: null, goldenFamilyId: null, lastDisbursement: null,
    observedAt: null, sourceBenefitId: "R1001", sourceFamilyId: "H1", sourceSchemeCode: "PDS",
  },
  beneficiaries: [
    { name: "Ram S.", goldenResidentId: GOLDEN, canonicalResidentId: null, canonicalBenefitObservationId: "o1",
      batchId: null, rawRecordId: null, residentRecordKey: "k1", status: "ACTIVE" },
  ],
  household: { sourceFamilyId: "H1", goldenFamilyId: null, familyRecordKey: "f1", memberGoldenResidentIds: [GOLDEN], status: "ACTIVE" },
  disbursements: [
    { disbursedOn: "2026-10-01", amount: null, quantity: 35, unit: "kg", reference: "LIFT-1", rawRecordId: null, canonicalBenefitObservationId: "o1" },
  ],
  observations: [
    { canonicalBenefitObservationId: "o1", observedAt: "2026-10-01", benefitStatus: "ACTIVE", rawRecordId: null, statePackVersion: "1.0.0",
      current: true, batchId: null, canonicalResidentId: null, entitlement: null, mappingVersion: null, payload: null,
      residentRecordKey: null, schemeCatalogueVersion: null, startDate: null },
  ],
};

afterEach(() => vi.unstubAllGlobals());

describe("benefit page", () => {
  it("shows the scheme, beneficiaries with Golden links, household, disbursements and observations", async () => {
    mockBackend(testUser(["BENEFIT_VIEW"]), { [`/api/v1/benefits/${ID}`]: () => DETAIL });
    renderAt(`/benefits/${ID}`);

    expect(await screen.findByRole("heading", { name: "Public distribution ration" })).toBeInTheDocument();
    expect(screen.getByText("Food and civil supplies")).toBeInTheDocument();
    const beneficiaries = screen.getByTestId("benefit-beneficiaries");
    expect(within(beneficiaries).getByText("Ram S.")).toBeInTheDocument();
    expect(within(beneficiaries).getByRole("link", { name: GOLDEN })).toHaveAttribute("href", `/golden-residents/${GOLDEN}`);
    expect(screen.getByRole("region", { name: "Household" })).toHaveTextContent("H1");
    expect(within(screen.getByTestId("benefit-disbursements")).getByText("35 kg")).toBeInTheDocument();
    expect(within(screen.getByTestId("benefit-observations")).getByText("1.0.0")).toBeInTheDocument();
    expect(screen.queryByTestId("reveal")).not.toBeInTheDocument();
  });
});
