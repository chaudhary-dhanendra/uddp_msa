import { NavLink, Outlet } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useI18n } from "../i18n/i18n";
import type { FeatureRegistry, NavSection } from "./registry";
import { UserMenu, useSession } from "../features/auth";
import { NotificationBell } from "../features/monitoring";
import styles from "./Layout.module.css";

const SECTIONS: NavSection[] = ["operate", "configure"];

/** Health of the backend for the header indicator; failures are shown, never thrown. */
function useBackendStatus() {
  return useQuery({
    queryKey: ["health"],
    queryFn: async () => {
      const response = await fetch("/health");
      return response.ok;
    },
    refetchInterval: 30_000,
    retry: false,
  });
}

/** App shell: persistent development banner, header, registry-driven sidebar and the routed page. */
export function Layout({ registry }: { registry: FeatureRegistry }) {
  const { t } = useI18n();
  const session = useSession();
  const health = useBackendStatus();
  const up = health.data === true;
  return (
    <div className={styles.shell}>
      <a className={styles.skip} href="#main">
        {t("layout.skipToContent")}
      </a>
      <div className={styles.banner} role="note" data-testid="dev-banner">
        {t("layout.devBanner")}
      </div>
      <header className={styles.header}>
        <div className={styles.brand}>
          <span className={styles.brandMark} aria-hidden="true">
            U
          </span>
          <span>
            <strong>{t("app.name")}</strong>
            <span className={styles.brandSub}>{t("app.tagline")}</span>
          </span>
        </div>
        <div className={styles.headerEnd}>
          <span
            className={styles.health}
            data-up={health.isPending ? undefined : String(up)}
            role="status"
          >
            {health.isPending
              ? t("layout.backendChecking")
              : up
                ? t("layout.backendUp")
                : t("layout.backendDown")}
          </span>
          <NotificationBell />
          <UserMenu />
        </div>
      </header>
      <div className={styles.body}>
        <nav className={styles.sidebar} aria-label={t("layout.navigation")}>
          {SECTIONS.map((section) => {
            const entries = registry
              .navigation(section)
              .filter((entry) => session.canAny(entry.permissions));
            if (entries.length === 0) {
              return null;
            }
            return (
              <div key={section} className={styles.navSection}>
                <h2 className={styles.navHeading} id={`nav-${section}`}>
                  {t(`layout.section.${section}`)}
                </h2>
                <ul aria-labelledby={`nav-${section}`}>
                  {entries.map((entry) => (
                    <li key={entry.id}>
                      <NavLink
                        to={entry.to}
                        end={entry.to === "/"}
                        className={({ isActive }) =>
                          isActive
                            ? `${styles.navLink} ${styles.active}`
                            : styles.navLink
                        }
                        data-testid={`nav-${entry.id}`}
                      >
                        <span>{t(entry.labelKey)}</span>
                        {entry.badgeKey && (
                          <span className={styles.navBadge}>
                            {t(entry.badgeKey)}
                          </span>
                        )}
                      </NavLink>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </nav>
        <main id="main" className={styles.main} tabIndex={-1}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
