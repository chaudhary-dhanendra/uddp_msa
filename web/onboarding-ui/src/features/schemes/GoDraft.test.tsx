import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { GoDraftButton } from "./GoDraft";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("Draft from GO", () => {
  afterEach(() => { vi.unstubAllGlobals(); document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT"; });

  it("is hidden when AI drafting is not enabled", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ enabled: false }));
    vi.stubGlobal("fetch", fetchMock);
    render(<GoDraftButton schemeId="S" versionId="v1" />);
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.queryByText("Draft from GO")).toBeNull();
  });

  it("uploads the GO and shows the marked draft", async () => {
    document.cookie = "XSRF-TOKEN=token";
    const draft = { ruleSetId: "r1", version: 1, status: "DRAFT", marker: "AI draft - verify against GO", model: "m", documentName: "go.pdf", documentSha256: "x", requiredAttributes: ["age"], rows: [{ condition: "age >= 60", output: "ELIGIBLE", clauseReference: "GO 3" }] };
    const fetchMock = vi.fn().mockResolvedValueOnce(json({ enabled: true })).mockResolvedValueOnce(json(draft));
    vi.stubGlobal("fetch", fetchMock);
    const onDrafted = vi.fn();
    render(<GoDraftButton schemeId="S" versionId="v1" onDrafted={onDrafted} />);
    await userEvent.upload(await screen.findByLabelText("GO PDF"), new File(["%PDF"], "go.pdf", { type: "application/pdf" }));
    await userEvent.click(screen.getByText("Draft from GO"));
    expect(await screen.findByText(/AI draft - verify against GO/)).toBeTruthy();
    expect(String(fetchMock.mock.calls[1]?.[0])).toContain("/api/scheme-packs/S/versions/v1/eligibility/ai-draft");
    expect(onDrafted).toHaveBeenCalledWith(draft);
  });
});
