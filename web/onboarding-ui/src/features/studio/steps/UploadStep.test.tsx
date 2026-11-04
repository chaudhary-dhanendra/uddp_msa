import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { StudioDraft } from "../../../api/types";
import { I18nProvider } from "../../../i18n/i18n";
import { LARGE_WORKBOOK_BYTES, SheetPicker, UploadStep } from "./UploadStep";

const draft = {
  draftId: "d1",
  source: { sourceType: "file", fileName: "census.xlsx", layout: [{ op: "header-detect@1.0" }], parse: [] },
} as unknown as StudioDraft;

const wrap = (node: React.ReactNode) =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <I18nProvider>{node}</I18nProvider>
    </QueryClientProvider>,
  );

const sheets = [
  { name: "Ngairang", rows: 7, selected: true },
  { name: "Senkhai", rows: 3, selected: true },
];

describe("SheetPicker", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("lists each sheet with its rows and stores the ticked ones on the source", async () => {
    const user = userEvent.setup();
    document.cookie = "XSRF-TOKEN=t";
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(draft), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    wrap(<SheetPicker draft={draft} sheets={sheets} />);

    expect(screen.getByLabelText("Ngairang (7 rows)")).toHaveProperty("checked", true);
    await user.click(screen.getByLabelText("Ngairang (7 rows)"));
    await user.click(screen.getByTestId("save-sheets"));

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/studio/drafts/d1/shaping");
    expect(JSON.parse(String(init.body))).toEqual({
      layout: [{ op: "header-detect@1.0" }],
      parse: [],
      sheets: ["Senkhai"],
    });
  });

  it("needs at least one sheet", async () => {
    const user = userEvent.setup();
    wrap(<SheetPicker draft={draft} sheets={[{ name: "Only", rows: 2, selected: true }]} />);
    await user.click(screen.getByLabelText("Only (2 rows)"));
    expect(screen.getByRole("alert").textContent).toContain("at least one sheet");
    expect(screen.getByTestId("save-sheets")).toHaveProperty("disabled", true);
  });
});

describe("UploadStep", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("accepts CSV and Excel and warns about a large workbook", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ uploadLimitBytes: 100 * 1024 * 1024 }), { status: 200 })),
    );
    wrap(<UploadStep draft={{ draftId: "d1" } as StudioDraft} onBack={vi.fn()} onDone={vi.fn()} />);
    const input = screen.getByLabelText("Source file (CSV or Excel)");
    expect(input.getAttribute("accept")).toBe(".csv,.xlsx");

    const small = new File(["x"], "small.xlsx");
    await user.upload(input, small);
    expect(screen.queryByTestId("large-workbook")).toBeNull();

    const big = new File(["x"], "big.xlsx");
    Object.defineProperty(big, "size", { value: LARGE_WORKBOOK_BYTES + 1024 * 1024 });
    await user.upload(input, big);
    expect(screen.getByTestId("large-workbook").textContent).toContain("export the sheets as CSV");
  });
});
