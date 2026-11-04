import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { SessionProvider } from "../auth";
import { GeographyPanel, LgdCodePicker } from "./GeographyPanel";

afterEach(() => vi.unstubAllGlobals());

describe("F5 Studio geography", () => {
  it("searches generic LGD levels with context, keeps review explicit, and renders coverage and LGD operations", async () => {
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(input instanceof Request ? input.url : String(input), "http://localhost"); calls.push(url.toString());
      const body: unknown = url.pathname === "/api/v1/auth/me" ? { username:"a",displayName:"A",roles:[],permissions:["GEOGRAPHY_SYNC"],departmentScope:["ALL"],mustChangePassword:false,authSource:"LOCAL",sessionTimeoutSeconds:1 }
        : url.pathname === "/api/geography/entities" ? { items:[{entityType:"VILLAGE",code:"500001",name:"Riverbend",status:"ACTIVE"}], page:0,size:10,totalItems:1,totalPages:1,sort:"name,asc" }
        : url.pathname === "/api/geography/coverage" ? [{stateCode:"ST1",sourceSystem:"pds",level:"village",records:100,supplied:100,resolved:72,unknown:28,resolvedPercent:72,unknownPercent:28}]
        : url.pathname === "/api/geography/sync/status" ? {activeSnapshotId:"snap",lastSuccessAt:"2026-01-01T00:00:00Z",stale:false}
        : url.pathname === "/api/geography/sync/runs" ? {items:[{runId:"r",status:"FAILED",startedAt:"a",finishedAt:"b",snapshotId:null,error:"source failed"}],page:0,size:10,totalItems:1,totalPages:1,sort:"startedAt,desc"}
        : {items:[{changeId:1,changeType:"RENAMED",entityType:"VILLAGE",code:"500001",oldValue:"Old",newValue:"New"}],page:0,size:10,totalItems:1,totalPages:1,sort:"changeId,desc"};
      return new Response(JSON.stringify(body), {status:200,headers:{"Content-Type":"application/json"}});
    }));
    render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><SessionProvider><GeographyPanel stateCode="ST1" /></SessionProvider></QueryClientProvider>);
    expect(await screen.findByText(/Active snapshot: snap/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Geography level"), {target:{value:"village"}});
    fireEvent.change(screen.getByLabelText("Code or name"), {target:{value:"River"}});
    expect(await screen.findByText("Riverbend (500001)")).toBeInTheDocument();
    expect(screen.queryByTestId("geo-resolved")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", {name:"Select"}));
    expect(screen.getByTestId("geo-resolved")).toHaveTextContent("Riverbend");
    expect(screen.getByTestId("geo-coverage")).toHaveTextContent("ST1/pds");
    expect(screen.getByTestId("geo-coverage")).toHaveTextContent("28.0");
    expect(screen.getByTestId("lgd-sync-history")).toHaveTextContent("source failed");
    expect(screen.getByTestId("lgd-change-history")).toHaveTextContent("RENAMED");
    expect(screen.getByRole("button", {name:"Trigger LGD sync"})).toBeInTheDocument();
    expect(calls.some((url) => url.includes("type=VILLAGE"))).toBe(true);
  });

  it("picks a crosswalk target from a server-side LGD search", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(input instanceof Request ? input.url : String(input), "http://localhost");
      const body = url.pathname === "/api/reference-data/lgd-village/values" && url.searchParams.get("q") === "River"
        ? [{code:"500001",displayName:"Riverbend",status:"ACTIVE"}] : [];
      return new Response(JSON.stringify(body), {status:200,headers:{"Content-Type":"application/json"}});
    }));
    const chosen: string[] = [];
    render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><LgdCodePicker referenceSet="lgd-village" label="village Rivr" value="" onChange={(code) => chosen.push(code)} /></QueryClientProvider>);
    fireEvent.change(screen.getByLabelText("village Rivr"), {target:{value:"River"}});
    fireEvent.click(await screen.findByRole("button", {name:"Riverbend (500001)"}));
    expect(chosen).toEqual(["500001"]);
  });
});
