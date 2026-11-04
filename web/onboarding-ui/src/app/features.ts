import { FeatureRegistry, type FeatureDefinition } from "./registry";
import { OverviewPage } from "../features/overview";
import {
  OnboardingRunsPage,
  OnboardingRunDetailPage,
} from "../features/onboarding";
import { StatesPage, StateDetailPage } from "../features/states";
import {
  BenefitsPage,
  CanonicalPage,
  DqPage,
  Family360Page,
  FamiliesPage,
  GoldenPage,
  MatchingPage,
  ReportsPage,
  Resident360Page,
} from "../features/simple";
import { CanonicalRecordPage } from "../features/canonical";
import { MatchingRunPage } from "../features/matching-run";
import { BenefitDetailPage } from "../features/benefit";
import { PendingApprovalsPage } from "../features/studio/approvals";
import { StewardshipPage } from "../features/stewardship";
import {
  NewStatePage,
  PortabilityPage,
  StudioStatesPage,
  StudioWizardPage,
} from "../features/studio";
import { SettingsPage } from "../features/setup";
import { AuditLogPage } from "../features/audit";
import { RolesPage, UserDetailPage, UsersPage } from "../features/users";
import { RulesStudioPage } from "../features/rules";
import { SchemeCataloguePage } from "../features/schemes";
import { SchemeAuthoringPage } from "../features/schemes/AuthoringPage";
import { SchemeWorkspaceActionsPage } from "../features/schemes/WorkspaceActionsPage";
import { SchemeWizardNewPage, SchemeWizardPage } from "../features/schemes/wizard";
import { MatchingStudioPage, WhyMatchedPage } from "../features/matching-studio";
import { InboxPage } from "../features/inbox";
import { MonitoringPage } from "../features/monitoring";
import { SourceOperationsPage } from "../features/operations";
import { ShapingPluginsPage } from "../features/shaping/PluginsPage";
import { WordListsPage } from "../features/shaping/WordListsPage";
import { GeographyMasterPage } from "../features/geography";
const defs: FeatureDefinition[] = [
  { id: "geography-master", routes: [{ path: "admin/geography", component: GeographyMasterPage }], nav: [{ id: "geography-master", labelKey: "nav.geographyMaster", to: "/admin/geography", section: "configure", order: 10, permissions: ["GEOGRAPHY_UPLOAD"] }] },
  { id: "word-lists", routes: [{ path: "reference-data/word-lists", component: WordListsPage }], nav: [{ id: "word-lists", labelKey: "nav.wordLists", to: "/reference-data/word-lists", section: "configure", order: 8, permissions: ["SHAPING_VIEW"] }] },
  { id: "shaping-plugins", routes: [{ path: "settings/shaping-plugins", component: ShapingPluginsPage }], nav: [{ id: "shaping-plugins", labelKey: "nav.shapingPlugins", to: "/settings/shaping-plugins", section: "configure", order: 9, permissions: ["SHAPING_VIEW"] }] },
  { id: "inbox", routes: [{ path: "inbox", component: InboxPage }], nav: [{ id: "inbox", labelKey: "nav.inbox", to: "/inbox", section: "operate", order: 2, permissions: ["ONBOARDING_VIEW"] }] },
  { id: "monitoring", routes: [{ path: "monitoring", component: MonitoringPage }], nav: [{ id: "monitoring", labelKey: "nav.monitoring", to: "/monitoring", section: "operate", order: 3, permissions: ["ONBOARDING_VIEW"] }] },
  { id: "operations", routes: [{ path: "operations", component: SourceOperationsPage }], nav: [{ id: "operations", labelKey: "nav.operations", to: "/operations", section: "configure", order: 3, permissions: ["ONBOARDING_RUN"] }] },
  { id: "scheme-studio", routes: [{ path: "scheme-studio", component: SchemeCataloguePage }, { path: "scheme-studio/new", component: SchemeAuthoringPage }, { path: "scheme-studio/:schemeId/:versionId/edit", component: SchemeAuthoringPage }, { path: "scheme-studio/:schemeId/:versionId", component: SchemeWorkspaceActionsPage }, { path: "scheme-studio/wizard/new", component: SchemeWizardNewPage }, { path: "scheme-studio/:schemeId/:versionId/wizard/:step", component: SchemeWizardPage }], nav: [{ id: "scheme-studio", labelKey: "nav.schemeStudio", to: "/scheme-studio", section: "configure", order: 4, permissions: ["PLATFORM_SETTINGS", "RULES_APPROVE"] }] },
  { id: "rules", routes: [{ path: "rules", component: RulesStudioPage }], nav: [{ id: "rules", labelKey: "nav.rules", to: "/rules", section: "configure", order: 4, permissions: ["PLATFORM_SETTINGS", "RULES_APPROVE"] }] },
  {
    id: "overview",
    routes: [{ path: "", component: OverviewPage }],
    nav: [
      {
        id: "overview",
        labelKey: "nav.overview",
        to: "/",
        section: "operate",
        order: 1,
        permissions: ["REPORT_VIEW", "ONBOARDING_VIEW", "RESIDENT_VIEW"],
      },
    ],
  },
  {
    id: "runs",
    routes: [
      { path: "onboarding-runs", component: OnboardingRunsPage },
      { path: "onboarding-runs/:id", component: OnboardingRunDetailPage },
    ],
    nav: [
      {
        id: "runs",
        labelKey: "nav.runs",
        to: "/onboarding-runs",
        section: "operate",
        order: 2,
        permissions: ["ONBOARDING_VIEW"],
      },
    ],
  },
  {
    id: "states",
    routes: [
      { path: "states", component: StatesPage },
      { path: "states/:state", component: StateDetailPage },
    ],
    nav: [
      {
        id: "states",
        labelKey: "nav.states",
        to: "/states",
        section: "configure",
        order: 1,
        permissions: ["STATE_PACK_VIEW"],
      },
    ],
  },
  {
    id: "canonical",
    routes: [
      { path: "canonical-residents", component: CanonicalPage },
      { path: "canonical-residents/:id", component: CanonicalRecordPage },
    ],
    nav: [
      {
        id: "canonical",
        labelKey: "nav.canonical",
        to: "/canonical-residents",
        section: "operate",
        order: 3,
        permissions: ["CANONICAL_VIEW"],
      },
    ],
  },
  {
    id: "matching",
    routes: [
      { path: "matching-runs", component: MatchingPage },
      { path: "matching-runs/:id", component: MatchingRunPage },
    ],
    nav: [
      {
        id: "matching",
        labelKey: "nav.matching",
        to: "/matching-runs",
        section: "operate",
        order: 4,
        permissions: ["MATCH_EVIDENCE_VIEW"],
      },
    ],
  },
  {
    id: "golden",
    routes: [
      { path: "golden-residents", component: GoldenPage },
      { path: "golden-residents/:id", component: Resident360Page },
    ],
    nav: [
      {
        id: "golden",
        labelKey: "nav.golden",
        to: "/golden-residents",
        section: "operate",
        order: 5,
        permissions: ["RESIDENT_VIEW"],
      },
    ],
  },
  {
    id: "families",
    routes: [
      { path: "families", component: FamiliesPage },
      { path: "families/:id", component: Family360Page },
    ],
    nav: [
      {
        id: "families",
        labelKey: "nav.families",
        to: "/families",
        section: "operate",
        order: 6,
        permissions: ["FAMILY_VIEW"],
      },
    ],
  },
  {
    id: "benefits",
    routes: [
      { path: "benefits", component: BenefitsPage },
      { path: "benefits/:id", component: BenefitDetailPage },
    ],
    nav: [
      {
        id: "benefits",
        labelKey: "nav.benefits",
        to: "/benefits",
        section: "operate",
        order: 7,
        permissions: ["BENEFIT_VIEW"],
      },
    ],
  },
  {
    id: "reports",
    routes: [{ path: "reports", component: ReportsPage }],
    nav: [
      {
        id: "reports",
        labelKey: "nav.reports",
        to: "/reports",
        section: "operate",
        order: 8,
        permissions: ["REPORT_VIEW"],
      },
    ],
  },
  {
    id: "stewardship",
    routes: [{ path: "stewardship", component: StewardshipPage }],
    nav: [
      {
        id: "stewardship",
        labelKey: "nav.stewardship",
        to: "/stewardship",
        section: "operate",
        order: 9,
        permissions: ["STEWARDSHIP_VIEW"],
      },
    ],
  },
  {
    id: "dq",
    routes: [{ path: "dq", component: DqPage }],
    nav: [
      {
        id: "dq",
        labelKey: "nav.dq",
        to: "/dq",
        section: "operate",
        order: 10,
        permissions: ["DATA_QUALITY_VIEW"],
      },
    ],
  },
  {
    id: "studio",
    routes: [
      { path: "onboarding-studio", component: StudioStatesPage },
      { path: "onboarding-studio/new", component: NewStatePage },
      { path: "onboarding-studio/portability", component: PortabilityPage },
      { path: "onboarding-studio/approvals", component: PendingApprovalsPage },
      {
        path: "onboarding-studio/drafts/:draftId/:step",
        component: StudioWizardPage,
      },
    ],
    nav: [
      {
        id: "studio",
        labelKey: "nav.studio",
        to: "/onboarding-studio",
        section: "configure",
        order: 2,
        permissions: ["STATE_PACK_VIEW"],
      },
      {
        id: "approvals",
        labelKey: "nav.approvals",
        to: "/onboarding-studio/approvals",
        section: "configure",
        order: 3,
        permissions: [
          "STATE_PACK_APPROVE",
          "STATE_PACK_EDIT",
          "STEWARDSHIP_DECIDE",
          "STEWARDSHIP_APPROVE",
        ],
      },
      {
        id: "portability",
        labelKey: "nav.portability",
        to: "/onboarding-studio/portability",
        section: "configure",
        order: 3,
        permissions: ["PORTABILITY_VIEW"],
      },
    ],
  },
  {
    id: "audit",
    routes: [{ path: "audit", component: AuditLogPage }],
    nav: [
      {
        id: "audit",
        labelKey: "nav.audit",
        to: "/audit",
        section: "operate",
        order: 11,
        permissions: ["AUDIT_VIEW"],
      },
    ],
  },
  {
    id: "settings",
    routes: [{ path: "settings", component: SettingsPage }],
    nav: [
      {
        id: "settings",
        labelKey: "nav.settings",
        to: "/settings",
        section: "configure",
        order: 4,
        permissions: ["PLATFORM_SETTINGS"],
      },
    ],
  },
  {
    id: "users",
    routes: [
      { path: "settings/users", component: UsersPage },
      { path: "settings/users/:id", component: UserDetailPage },
      { path: "settings/roles", component: RolesPage },
    ],
    nav: [
      {
        id: "users",
        labelKey: "nav.users",
        to: "/settings/users",
        section: "configure",
        order: 5,
        permissions: ["USER_MANAGE"],
      },
      {
        id: "roles",
        labelKey: "nav.roles",
        to: "/settings/roles",
        section: "configure",
        order: 6,
        permissions: ["USER_MANAGE"],
      },
    ],
  },
  {
    id: "matching-studio",
    routes: [
      { path: "matching-studio", component: MatchingStudioPage },
      { path: "matching-studio/why/:runId/:a/:b", component: WhyMatchedPage },
    ],
    nav: [
      {
        id: "matching-studio",
        labelKey: "nav.matchingStudio",
        to: "/matching-studio",
        section: "configure",
        order: 7,
        permissions: ["MATCHING_VIEW"],
      },
    ],
  },
];
export function createAppRegistry() {
  const registry = new FeatureRegistry();
  defs.forEach((d) => registry.register(d));
  return registry;
}
