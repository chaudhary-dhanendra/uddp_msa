import { expect, test, type Page } from "@playwright/test";
import path from "node:path";
import { randomUUID } from "node:crypto";

/**
 * The stakeholder demo, end to end in the browser with the real backend, generic Hop ingestion and Splink matching:
 * Setup screen on an empty platform, then States 1, 2, 3 and 4 through the Onboarding Studio wizard (accepting the
 * suggestions plus the crosswalk values a user types), Ram Singh's Golden Resident 360 with ST1-ST4, and the
 * portability evidence with zero platform code changes. A screenshot of every step at 1366px goes to
 * docs/demo/screens/.
 *
 * Database settings for the Setup screen come from UDDP_DEMO_DB_* (a throwaway database; never printed).
 */
// Playwright runs from web/onboarding-ui.
const repository = path.resolve(process.cwd(), "..", "..");
const screens = path.join(repository, "docs", "demo", "screens");
let shot = 0;

async function screenshot(page: Page, name: string) {
  shot += 1;
  // Wait until the page has loaded and no query is still loading ("networkidle" can wait forever when the browser
  // keeps a request's accounting open across a navigation).
  await page.waitForLoadState("load");
  await expect(page.getByText(/^Loading/)).toHaveCount(0);
  await page.screenshot({
    path: path.join(screens, `${String(shot).padStart(2, "0")}-${name}.png`),
    fullPage: true,
  });
}

interface StateCase {
  packId: string;
  stateCode: string;
  name: string;
  file: string;
  /** Crosswalk values the user chooses because no generic signal can know them. */
  corrections: Record<string, Record<string, string>>;
}

const STATES: StateCase[] = [
  {
    packId: "state-1",
    stateCode: "ST1",
    name: "PDS (Food & Civil Supplies)",
    file: "sample-data/state-1/pds.csv",
    corrections: {
      district: { "101": "100001", "102": "100002" },
      scheme: { PHH: "PDS-RATION", AAY: "PDS-RATION" },
    },
  },
  {
    packId: "state-2",
    stateCode: "ST2",
    name: "Pension (Social Welfare)",
    file: "sample-data/state-2/pension.csv",
    corrections: {
      gender: { "1": "MALE", "2": "FEMALE" },
      "benefit-status": { "1": "ACTIVE" },
      scheme: { VRIDHA: "OLD-AGE-PENSION" },
    },
  },
  {
    packId: "state-3",
    stateCode: "ST3",
    name: "Welfare beneficiaries (Social Welfare)",
    file: "sample-data/state-3/beneficiaries.csv",
    corrections: {},
  },
  {
    packId: "state-4",
    stateCode: "ST4",
    name: "Citizen register (Planning)",
    file: "sample-data/state-4/citizen-register.csv",
    corrections: {},
  },
];

/** The first administrator's password: random for each run, kept in memory only. */
const ADMIN_PASSWORD = `Demo-${randomUUID()}-1a`;

function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} is required (see scripts/run-studio-acceptance.ps1)`,
    );
  }
  return value;
}

async function continueStep(page: Page) {
  await page.getByTestId("continue").click();
}

async function onboard(page: Page, state: StateCase) {
  const key = state.packId;
  await page.goto("/onboarding-studio");
  await page.getByTestId("new-state").click();
  await page.getByLabel("Pack ID", { exact: true }).fill(state.packId);
  await page.getByLabel("Source code", { exact: true }).fill(state.stateCode);
  await page.getByLabel("Display name", { exact: true }).fill(state.name);
  await screenshot(page, `${key}-1-details`);
  await continueStep(page);

  await page
    .getByTestId("source-file")
    .setInputFiles(path.join(repository, state.file));
  await page.getByTestId("upload").click();
  await expect(page.getByTestId("profile-table")).toBeVisible();
  await screenshot(page, `${key}-2-upload-profile`);
  await continueStep(page);

  await expect(page.getByTestId("mapping-table")).toBeVisible();
  await screenshot(page, `${key}-3-map`);
  await continueStep(page);

  await expect(
    page.getByRole("heading", { name: "Crosswalks" }).first(),
  ).toBeVisible();
  for (const [crosswalk, values] of Object.entries(state.corrections)) {
    for (const [source, target] of Object.entries(values)) {
      await page.getByTestId(`xw-${crosswalk}-${source}`).selectOption(target);
    }
  }
  await expect(page.getByTestId("crosswalk-missing")).toHaveCount(0);
  await screenshot(page, `${key}-4-crosswalks`);
  await continueStep(page);

  await expect(page.getByTestId("validation-rules")).toBeVisible();
  await screenshot(page, `${key}-5-rules`);
  await continueStep(page);

  await expect(page.getByTestId("preview-clean")).toBeVisible();
  await screenshot(page, `${key}-6-preview`);
  await continueStep(page);

  await page.getByTestId("publish").click();
  await expect(page.getByTestId("published")).toBeVisible();
  await screenshot(page, `${key}-7-publish`);
  await continueStep(page);

  await page.getByTestId("start-onboarding").click();
  await expect(page.getByTestId("onboarding-completed")).toBeVisible({
    timeout: 15 * 60 * 1000,
  });
  await screenshot(page, `${key}-8-onboard`);
  await continueStep(page);

  await expect(page.getByTestId("done-portability")).toBeVisible();
  await screenshot(page, `${key}-9-done`);

  await page.goto("/onboarding-studio");
  await expect(page.getByTestId(`state-status-${state.packId}`)).toContainText(
    "ONBOARDED",
  );
}

test("demo: empty platform to four onboarded states with zero platform code", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page.getByTestId("setup-screen")).toBeVisible();
  await screenshot(page, "setup-empty");
  await page.getByLabel("Host", { exact: true }).fill(required("UDDP_DEMO_DB_HOST"));
  await page.getByLabel("Port", { exact: true }).fill(required("UDDP_DEMO_DB_PORT"));
  await page.getByLabel("Database", { exact: true }).fill(required("UDDP_DEMO_DB_NAME"));
  await page.getByLabel("User", { exact: true }).fill(required("UDDP_DEMO_DB_USER"));
  await page.getByLabel("Password", { exact: true }).fill(required("UDDP_DEMO_DB_PASSWORD"));
  await page.getByTestId("test-connection").click();
  await expect(page.getByTestId("connection-result")).toContainText(
    "Connection works",
  );
  await screenshot(page, "setup-tested");
  await page.getByTestId("apply-settings").click();
  // P1: a new platform asks for its first administrator, who is then signed in.
  await expect(page.getByTestId("create-admin-screen")).toBeVisible({
    timeout: 120_000,
  });
  await page.getByLabel("Username", { exact: true }).fill("demo.admin");
  await page.getByLabel("Password", { exact: true }).fill(ADMIN_PASSWORD);
  await page.getByLabel("Repeat the new password", { exact: true }).fill(ADMIN_PASSWORD);
  await screenshot(page, "setup-first-admin");
  await page.getByTestId("create-admin").click();
  await expect(page.getByTestId("nav-studio")).toBeVisible({
    timeout: 120_000,
  });
  await expect(page.getByTestId("current-user")).toContainText("demo.admin");
  await screenshot(page, "overview-empty-platform");

  await page.goto("/onboarding-studio");
  await expect(page.getByText("No states yet")).toBeVisible();
  await screenshot(page, "studio-zero-states");

  for (const state of STATES) {
    await onboard(page, state);
  }
  await screenshot(page, "studio-four-states");

  await page.goto("/golden-residents");
  await page.getByLabel("Search", { exact: true }).fill("Ram Singh");
  await page.getByLabel("Search", { exact: true }).press("Enter");
  await page.getByRole("table").getByRole("link").first().click();
  const states = page.getByText(/ST\d, ST\d, ST\d, ST\d/);
  await expect(states).toBeVisible();
  for (const code of ["ST1", "ST2", "ST3", "ST4"]) {
    await expect(states).toContainText(code);
  }
  await screenshot(page, "ram-singh-360");

  await page.goto("/onboarding-studio/portability");
  await expect(page.getByTestId("evidence-states")).toContainText("4");
  await expect(page.getByTestId("evidence-code-changes")).toContainText("0");
  for (const state of STATES) {
    await expect(page.getByTestId(`changes-${state.packId}`)).toContainText(
      "0",
    );
  }
  await screenshot(page, "portability-evidence");

  await page.goto("/settings");
  await screenshot(page, "settings-masked");
});
