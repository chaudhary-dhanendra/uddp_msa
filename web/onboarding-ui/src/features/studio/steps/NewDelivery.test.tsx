import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { StudioDraft } from "../../../api/types";
import { I18nProvider } from "../../../i18n/i18n";
import { NewDeliveryPanel } from "./NewDelivery";

const draft = { draftId: "d1", published: { version: "1.0.0" }, source: { sourceType: "file" } } as unknown as StudioDraft;
const wrap = (node: React.ReactNode) => render(<QueryClientProvider client={new QueryClient()}><I18nProvider>{node}</I18nProvider></QueryClientProvider>);

describe("NewDeliveryPanel", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("is shown only for a published file source", () => {
    wrap(<NewDeliveryPanel draft={{ ...draft, published: null } as unknown as StudioDraft} onDelivered={vi.fn()} />);
    expect(screen.queryByTestId("upload-delivery")).toBeNull();
  });

  it("uploads the new delivery and reports a refused one", async () => {
    const user = userEvent.setup(); document.cookie = "XSRF-TOKEN=t";
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ message: "The new delivery lacks mapped column(s): name" }), { status: 409 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(draft), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const onDelivered = vi.fn();
    wrap(<NewDeliveryPanel draft={draft} onDelivered={onDelivered} />);
    await user.upload(screen.getByLabelText("New delivery file"), new File(["pid\nP1\n"], "june.csv", { type: "text/csv" }));
    await user.click(screen.getByTestId("upload-delivery"));
    expect(await screen.findByText(/lacks mapped column/)).toBeTruthy();
    await user.click(screen.getByTestId("upload-delivery"));
    expect(await screen.findByText(/New delivery uploaded/)).toBeTruthy();
    expect(onDelivered).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[1]?.[0])).toBe("/api/studio/drafts/d1/delivery");
  });
});
