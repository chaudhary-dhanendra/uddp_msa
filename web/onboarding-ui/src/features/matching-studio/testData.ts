import type { Profile } from "./types";

export const PROFILE_ID = "11111111-1111-1111-1111-111111111111";
export const RUN_ID = "66666666-6666-6666-6666-666666666666";
export const REC_A = "77777777-7777-7777-7777-777777777777";
export const REC_B = "88888888-8888-8888-8888-888888888888";

export function profile(overrides: Partial<Profile> = {}): Profile {
  return {
    profileId: PROFILE_ID,
    name: "resident",
    version: 2,
    status: "DRAFT",
    config: {},
    parameters: {},
    weights: ([
      { field: "date_of_birth", level: "Exact date of birth", m: 0.9, u: 0.001, matchWeight: 9.8,
        plainLanguage: "date_of_birth: when exact date of birth, a pair is 900 times more likely to be the same person" },
      { field: "mobile", level: "Different", m: 0.05, u: 0.9, matchWeight: -4.2,
        plainLanguage: "mobile: when different, a pair is 18 times less likely to be the same person" },
    ] as unknown) as Profile["weights"],
    trainingRecords: 10000,
    createdBy: "maker",
    submittedBy: null,
    approvedBy: null,
    createdAt: "2026-10-27T00:00:00Z",
    trainedAt: "2026-10-27T01:00:00Z",
    approvedAt: null,
    ...overrides,
  };
}
