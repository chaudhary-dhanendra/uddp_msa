import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import type { StudioDraft } from "../../../api/types";
import { I18nProvider } from "../../../i18n/i18n";
import { RulesStep } from "./RulesStep";

const draft = {
  draftId: "d1",
  mappings: [],
  crosswalks: [],
  rules: {
    validation: [
      { id: "name-required", field: "name", required: true, severity: "error" },
      { id: "dob-not-future", type: "notFuture", field: "dateOfBirth", severity: "error" },
    ],
    matching: { fields: [], autoMatch: 0.95, possibleMatch: 0.75 },
    survivorship: {},
  },
} as unknown as StudioDraft;

describe("RulesStep focus", () => {
  it("highlights and focuses the rule a Fix in pack link names", () => {
    render(
      <MemoryRouter initialEntries={["/onboarding-studio/drafts/d1/6?focus=dob-not-future"]}>
        <QueryClientProvider client={new QueryClient()}>
          <I18nProvider>
            <RulesStep draft={draft} onBack={vi.fn()} onDone={vi.fn()} />
          </I18nProvider>
        </QueryClientProvider>
      </MemoryRouter>,
    );
    const rows = screen.getByTestId("validation-rules").querySelectorAll("tbody tr");
    expect(rows[0]?.getAttribute("data-focused")).toBeNull();
    expect(rows[1]?.getAttribute("data-focused")).toBe("true");
    expect(document.activeElement).toBe(rows[1]);
  });
});
