import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import {
  PageHeader,
  Panel,
  QueryState,
  StatCard,
  StatGrid,
} from "../../shared/components";
import { useI18n } from "../../i18n/i18n";
import { InboxCountsPanel } from "../inbox";
import { useSession } from "../auth";
export function OverviewPage() {
  const { number } = useI18n();
  const session = useSession();
  const q = useQuery({
    queryKey: ["overview"],
    queryFn: () => unwrap(api.GET("/api/v1/overview")),
  });
  return (
    <>
      <PageHeader
        title="Operational overview"
        description="Read-only platform totals and source activity."
      />
      <QueryState query={q}>
        {(data) => (
          <>
            <StatGrid label="Platform totals">
              <StatCard
                label="Configured sources"
                value={number(data.totals?.states)}
              />
              <StatCard
                label="Raw records"
                value={number(data.totals?.rawRecords)}
              />
              <StatCard
                label="Canonical residents"
                value={number(data.totals?.canonicalResidents)}
              />
              <StatCard
                label="Golden residents"
                value={number(data.totals?.goldenResidents)}
              />
              <StatCard
                label="Onboarding runs"
                value={number(data.totals?.onboardingRuns)}
              />
            </StatGrid>
            {data.identity && (
              <StatGrid label="Identity">
                {Object.entries(data.identity.recordsWithIdentifier).map(
                  ([type, count]) => (
                    <StatCard
                      key={type}
                      label={`Records with ${type}`}
                      value={number(count)}
                    />
                  ),
                )}
                <StatCard
                  label="Residents with a UDDP ID"
                  value={number(data.identity.residentsWithUddpId)}
                />
                <StatCard
                  label="Families with a UDDP ID"
                  value={number(data.identity.familiesWithUddpId)}
                />
                <StatCard
                  label="Identity policy"
                  value={
                    data.identity.identityPolicyVersion == null
                      ? "None approved"
                      : `v${data.identity.identityPolicyVersion}`
                  }
                />
              </StatGrid>
            )}
            {session.can("ONBOARDING_VIEW") && <InboxCountsPanel />}
            <Panel title="Source activity">
              <ul>
                {data.states.map((s) => (
                  <li key={s.stateCode}>
                    {s.stateCode}: {number(s.rawRecords)} raw records,{" "}
                    {number(s.canonicalResidents)} canonical residents
                  </li>
                ))}
              </ul>
            </Panel>
          </>
        )}
      </QueryState>
    </>
  );
}
