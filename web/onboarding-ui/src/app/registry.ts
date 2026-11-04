import type { ComponentType } from "react";
import type { Permission } from "../api/types";

/**
 * The route and navigation registry. Every feature (src/features/<feature>) describes its routes and sidebar
 * entries in one {@link FeatureDefinition}; the app builds the router and sidebar from the registry, so adding a
 * feature (the M13 Onboarding Studio, for example) means adding a folder and one line in src/app/features.ts,
 * without changing any existing feature.
 */
export interface FeatureRoute {
  /** Path relative to the app root, without a leading slash (for example `golden/:id`). */
  path: string;
  component: ComponentType;
}

export type NavSection = "operate" | "configure";

export interface NavEntry {
  /** Unique across features; also the test id of the sidebar link. */
  id: string;
  /** i18n key of the label. */
  labelKey: string;
  /** Absolute path the entry links to. */
  to: string;
  section: NavSection;
  /** Lower comes first within the section. */
  order: number;
  /** i18n key of a short badge (for example "M13"). */
  badgeKey?: string;
  /** Shown only to users holding at least one of these permissions; omitted means every signed-in user. */
  permissions?: readonly Permission[];
}

export interface FeatureDefinition {
  id: string;
  routes: FeatureRoute[];
  nav?: NavEntry[];
}

export class FeatureRegistry {
  private readonly features: FeatureDefinition[] = [];

  register(feature: FeatureDefinition): this {
    if (this.features.some((existing) => existing.id === feature.id)) {
      throw new Error(`Feature already registered: ${feature.id}`);
    }
    const paths = new Set(this.routes().map((route) => route.path));
    for (const route of feature.routes) {
      if (route.path.startsWith("/")) {
        throw new Error(`Route paths are relative: ${route.path}`);
      }
      if (paths.has(route.path)) {
        throw new Error(`Route already registered: ${route.path}`);
      }
      paths.add(route.path);
    }
    const navIds = new Set(this.navigation().map((entry) => entry.id));
    for (const entry of feature.nav ?? []) {
      if (navIds.has(entry.id)) {
        throw new Error(`Navigation entry already registered: ${entry.id}`);
      }
      navIds.add(entry.id);
    }
    this.features.push(feature);
    return this;
  }

  list(): readonly FeatureDefinition[] {
    return [...this.features];
  }

  routes(): FeatureRoute[] {
    return this.features.flatMap((feature) => feature.routes);
  }

  /** Sidebar entries by section, each section ordered by `order` then label key. */
  navigation(section?: NavSection): NavEntry[] {
    return this.features
      .flatMap((feature) => feature.nav ?? [])
      .filter((entry) => section === undefined || entry.section === section)
      .sort(
        (a, b) => a.order - b.order || a.labelKey.localeCompare(b.labelKey),
      );
  }
}
