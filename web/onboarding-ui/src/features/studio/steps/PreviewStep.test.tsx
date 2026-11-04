import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { PreviewRow } from "../../../api/types";
import { I18nProvider } from "../../../i18n/i18n";
import { fixLink } from "../../held/fixTargets";
import { HeldPreviewCounts } from "./PreviewStep";

const issue = (code: string, kind: string, key: string | null, suggestion: string | null = null) => ({
  field: "gender",
  severity: "error",
  message: `${code} message`,
  code,
  fix: { kind, key },
  suggestion,
});

const rows = [
  { rowNumber: 1, source: {}, resident: null, family: null, benefit: null, issues: [issue("UNMAPPED_CODE", "CROSSWALK", "gender", "Nearest values in crosswalk gender: M")] },
  { rowNumber: 2, source: {}, resident: null, family: null, benefit: null, issues: [issue("UNMAPPED_CODE", "CROSSWALK", "gender"), issue("RULE_FAILED:dob-not-future", "RULE", "dob-not-future")] },
] as unknown as PreviewRow[];

const wrap = (node: React.ReactNode) =>
  render(
    <MemoryRouter>
      <I18nProvider>{node}</I18nProvider>
    </MemoryRouter>,
  );

describe("HeldPreviewCounts", () => {
  it("groups the sample's issues by reason code with a link to the exact step and the suggested fix", () => {
    wrap(<HeldPreviewCounts rows={rows} draftId="d1" />);
    const unmapped = screen.getByTestId("preview-code-UNMAPPED_CODE");
    expect(unmapped.textContent).toContain("2 issue(s) UNMAPPED_CODE");
    expect(within(unmapped).getByRole("link", { name: "Fix in pack: Crosswalks step" }).getAttribute("href"))
      .toBe("/onboarding-studio/drafts/d1/5?focus=gender");
    expect(unmapped.textContent).toContain("Suggested fix: Nearest values in crosswalk gender: M");
    const rule = screen.getByTestId("preview-code-RULE_FAILED:dob-not-future");
    expect(within(rule).getByRole("link", { name: "Fix in pack: rule dob-not-future (Rules step)" }).getAttribute("href"))
      .toBe("/onboarding-studio/drafts/d1/6?focus=dob-not-future");
    expect(screen.getByText("Total held: 2 row(s).")).toBeTruthy();
  });

  it("says when nothing would be held", () => {
    wrap(<HeldPreviewCounts rows={[]} draftId="d1" />);
    expect(screen.getByTestId("preview-held-none")).toBeTruthy();
  });
});

describe("fixLink", () => {
  it("opens each kind's wizard step, or Rules Studio and the Studio without a draft", () => {
    expect(fixLink({ kind: "SHAPING", key: "name" }, "LOW_CONFIDENCE", "d1").to).toBe("/onboarding-studio/drafts/d1/3?focus=name");
    expect(fixLink({ kind: "MAPPING", key: "DOB" }, "UNREADABLE_DATE", "d1").to).toBe("/onboarding-studio/drafts/d1/4?focus=DOB");
    expect(fixLink({ kind: "PACK_SOURCE", key: "census" }, "DUPLICATE_SOURCE_KEY", "d1").to).toBe("/onboarding-studio/drafts/d1/2?focus=census");
    expect(fixLink(null, "RULE_FAILED:r1", null).to).toBe("/rules?focus=r1");
    expect(fixLink(null, "UNMAPPED_CODE", null)).toEqual({ label: "Fix in pack: Crosswalks step", to: "/onboarding-studio" });
  });
});
