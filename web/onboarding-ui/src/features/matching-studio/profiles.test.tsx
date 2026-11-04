import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { mockBackend, renderAt, testUser } from "../../shared/testBackend";
import { WeightsChart } from "./WeightsChart";
import { PROFILE_ID, profile } from "./testData";
import { readWeights } from "./types";

afterEach(() => vi.unstubAllGlobals());

describe("weights chart", () => {
  it("explains each learned weight in plain language", () => {
    render(<WeightsChart weights={readWeights(profile())} />);
    expect(screen.getByTestId("weights-date_of_birth")).toHaveTextContent("900 times more likely");
    expect(screen.getByTestId("weights-mobile")).toHaveTextContent("-4.2");
    render(<WeightsChart weights={[]} />);
    expect(screen.getByText("No weights yet: train the profile first.")).toBeInTheDocument();
  });
});

describe("profiles panel", () => {
  it("lets a draft be edited and trained, and shows the training status", async () => {
    const user = userEvent.setup();
    const received = mockBackend(testUser(["MATCHING_VIEW", "MATCHING_MANAGE"]), {
      "/api/v1/matching/profiles": () => [profile()],
      [`/api/v1/matching/profiles/${PROFILE_ID}`]: () => profile(),
      [`/api/v1/matching/profiles/${PROFILE_ID}/training-jobs`]: () => [
        { jobId: "j1", profileId: PROFILE_ID, status: "COMPLETED", message: "Trained on 10000 records", requestedBy: "maker",
          startedAt: "2026-10-27T00:00:00Z", finishedAt: "2026-10-27T00:05:00Z" },
      ],
    });
    renderAt("/matching-studio?tab=profiles");

    expect(await screen.findByRole("heading", { name: /resident v2/ })).toBeInTheDocument();
    expect(await screen.findByTestId("training-status")).toHaveTextContent("Trained on 10000 records");
    await user.selectOptions(screen.getByLabelText("Comparison for date_of_birth"), "EXACT");
    await user.click(screen.getByRole("button", { name: "Save and train" }));

    await vi.waitFor(() => expect(received.some((r) => r.path.endsWith("/train"))).toBe(true));
    expect(received.some((r) => r.path === `/api/v1/matching/profiles/${PROFILE_ID}`)).toBe(true);
  });

  it("makes a different user approve: the submitter only sees that approval is pending", async () => {
    mockBackend(testUser(["MATCHING_VIEW", "MATCHING_APPROVE"]), {
      "/api/v1/matching/profiles": () => [profile({ status: "PENDING_APPROVAL", submittedBy: "anita" })],
    });
    renderAt("/matching-studio?tab=profiles");

    expect(await screen.findByText(/a different user with approval rights must approve/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  it("offers approval to another user and sends it", async () => {
    const user = userEvent.setup();
    const received = mockBackend(testUser(["MATCHING_VIEW", "MATCHING_APPROVE"]), {
      "/api/v1/matching/profiles": () => [profile({ status: "PENDING_APPROVAL", submittedBy: "maker" })],
    });
    renderAt("/matching-studio?tab=profiles");

    await user.click(await screen.findByRole("button", { name: "Approve" }));

    await vi.waitFor(() => expect(received.some((r) => r.path === `/api/v1/matching/profiles/${PROFILE_ID}/approve`)).toBe(true));
  });
});
