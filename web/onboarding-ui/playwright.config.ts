import { defineConfig } from "@playwright/test";

/**
 * The demo journey against a running demo (scripts/run-studio-acceptance.ps1 starts a throwaway database, the
 * backend in setup mode and the UI, then runs this). Uses the installed Microsoft Edge, so no browser download is
 * needed; UDDP_PLAYWRIGHT_CHANNEL can name another installed channel.
 */
export default defineConfig({
  testDir: "e2e",
  timeout: 45 * 60 * 1000,
  expect: { timeout: 30_000 },
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: process.env.UDDP_DEMO_URL ?? "http://127.0.0.1:4173",
    channel: process.env.UDDP_PLAYWRIGHT_CHANNEL ?? "msedge",
    viewport: { width: 1366, height: 900 },
    // Fail a stuck navigation or click instead of waiting for the whole test timeout.
    navigationTimeout: 120_000,
    actionTimeout: 120_000,
    trace: "retain-on-failure",
  },
});
