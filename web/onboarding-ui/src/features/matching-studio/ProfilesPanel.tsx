import { useState } from "react";
import { Button, Field, QueryState } from "../../shared/components";
import { useSession } from "../auth";
import {
  useBlockingEstimate,
  useCreateProfile,
  useProfileAction,
  useProfiles,
  useTrainingJobs,
  useUpdateProfile,
} from "./hooks";
import {
  DEFAULT_CONFIG,
  readConfig,
  readEstimate,
  readWeights,
  type Comparison,
  type FieldConfig,
  type Profile,
  type ProfileConfig,
} from "./types";
import { WeightsChart } from "./WeightsChart";

const COMPARISON_LABELS: Record<Comparison, string> = {
  NAME: "Name: spelling, sound and transliteration",
  DOB: "Date of birth: exact, year, day-month swap",
  EXACT: "Exact match",
};

function blockingText(config: ProfileConfig): string {
  return config.blocking.map((rule) => rule.join(", ")).join("\n");
}

function parseBlocking(text: string): string[][] {
  return text
    .split("\n")
    .map((line) =>
      line
        .split(",")
        .map((column) => column.trim())
        .filter((column) => column !== ""),
    )
    .filter((rule) => rule.length > 0);
}

function Estimate({ profileId }: { profileId: string }) {
  const estimate = useBlockingEstimate();
  const data = estimate.data === undefined ? null : readEstimate(estimate.data);
  return (
    <div>
      {estimate.isError && <p role="alert">The estimate failed.</p>}
      {data && (
        <p data-testid="blocking-estimate">
          About {data.totalPairs.toLocaleString()} candidate pairs instead of{" "}
          {data.allPairs.toLocaleString()} (
          {data.rules
            .map((r) => `${r.rule.join(" + ")}: ${r.pairs.toLocaleString()}`)
            .join("; ")}
          )
        </p>
      )}
      <Button
        variant="secondary"
        onClick={() => estimate.mutate(profileId)}
        disabled={estimate.isPending}
      >
        Estimate pair count
      </Button>
    </div>
  );
}

function TrainingStatus({ profileId }: { profileId: string }) {
  const jobs = useTrainingJobs(profileId);
  const latest = jobs.data?.[0];
  if (!latest) {
    return <p>Not trained yet.</p>;
  }
  return (
    <p data-testid="training-status" role="status">
      Training: <strong>{latest.status ?? ""}</strong> {latest.message}
    </p>
  );
}

function ProfileEditor({ profile }: { profile: Profile }) {
  const session = useSession();
  const id = profile.profileId ?? "";
  const editable = profile.status === "DRAFT";
  const [config, setConfig] = useState(() => readConfig(profile.config));
  const [blocking, setBlocking] = useState(() => blockingText(readConfig(profile.config)));
  const update = useUpdateProfile();
  const train = useProfileAction("train");
  const submit = useProfileAction("submit");
  const approve = useProfileAction("approve");
  const reject = useProfileAction("reject");
  const username = session.user?.username;
  const canApprove =
    session.can("MATCHING_APPROVE") && profile.submittedBy !== username;

  const setField = (field: string, change: Partial<FieldConfig> | null) =>
    setConfig((c) => {
      const others = c.fields.filter((f) => f.field !== field);
      const current = c.fields.find((f) => f.field === field);
      if (change === null) {
        return { ...c, fields: others };
      }
      const base: FieldConfig = current ?? {
        field,
        comparison: DEFAULT_CONFIG.fields.find((f) => f.field === field)?.comparison ?? "EXACT",
      };
      const fields = DEFAULT_CONFIG.fields
        .map((d) => d.field)
        .flatMap((name) =>
          name === field ? [{ ...base, ...change }] : others.filter((f) => f.field === name),
        );
      return { ...c, fields };
    });

  const save = () =>
    update.mutateAsync({ id, config: { ...config, blocking: parseBlocking(blocking) } });
  const weights = readWeights(profile);

  return (
    <section aria-label={`Profile ${profile.name ?? ""} version ${profile.version}`}>
      <h3>
        {profile.name} v{profile.version} <strong>{profile.status ?? ""}</strong>
      </h3>
      <fieldset disabled={!editable}>
        <legend>Compared fields</legend>
        {DEFAULT_CONFIG.fields.map(({ field }) => {
          const entry = config.fields.find((f) => f.field === field);
          return (
            <div className="row" key={field}>
              <label>
                <input
                  type="checkbox"
                  checked={entry !== undefined}
                  onChange={(e) => setField(field, e.target.checked ? {} : null)}
                />{" "}
                {field.replace(/_/g, " ")}
              </label>
              {entry && (
                <>
                  <select
                    aria-label={`Comparison for ${field}`}
                    value={entry.comparison}
                    onChange={(e) =>
                      setField(field, { comparison: e.target.value as Comparison })
                    }
                  >
                    {(Object.keys(COMPARISON_LABELS) as Comparison[]).map((c) => (
                      <option key={c} value={c}>
                        {COMPARISON_LABELS[c]}
                      </option>
                    ))}
                  </select>
                  <label>
                    <input
                      type="checkbox"
                      checked={entry.tfAdjust === true}
                      onChange={(e) => setField(field, { tfAdjust: e.target.checked })}
                    />{" "}
                    Treat common values as weaker evidence
                  </label>
                </>
              )}
            </div>
          );
        })}
        <Field
          id={`blocking-${id}`}
          label="Blocking rules"
          hint="One rule per line; a rule lists the columns that must be equal for two records to be compared."
        >
          <textarea
            id={`blocking-${id}`}
            rows={4}
            value={blocking}
            onChange={(e) => setBlocking(e.target.value)}
          />
        </Field>
        <div className="row">
          <Field id={`auto-${id}`} label="AUTO_MATCH threshold">
            <input
              id={`auto-${id}`}
              type="number"
              step="0.01"
              min="0"
              max="1"
              value={config.thresholds.autoMatch}
              onChange={(e) =>
                setConfig((c) => ({
                  ...c,
                  thresholds: { ...c.thresholds, autoMatch: Number(e.target.value) },
                }))
              }
            />
          </Field>
          <Field id={`possible-${id}`} label="Review threshold">
            <input
              id={`possible-${id}`}
              type="number"
              step="0.01"
              min="0"
              max="1"
              value={config.thresholds.possibleMatch}
              onChange={(e) =>
                setConfig((c) => ({
                  ...c,
                  thresholds: { ...c.thresholds, possibleMatch: Number(e.target.value) },
                }))
              }
            />
          </Field>
        </div>
      </fieldset>
      {update.isError && <p role="alert">The profile could not be saved.</p>}
      {editable && (
        <div className="row">
          <Button onClick={() => void save()} disabled={update.isPending}>
            Save draft
          </Button>
          <Button
            variant="secondary"
            onClick={() => void save().then(() => train.mutate(id))}
            disabled={update.isPending || train.isPending}
          >
            Save and train
          </Button>
          <Button
            variant="secondary"
            onClick={() => submit.mutate(id)}
            disabled={profile.trainedAt === null || submit.isPending}
          >
            Submit for approval
          </Button>
        </div>
      )}
      {(submit.isError || train.isError) && (
        <p role="alert">That step is not possible yet (train the profile before submitting).</p>
      )}
      <Estimate profileId={id} />
      <TrainingStatus profileId={id} />
      {profile.status === "PENDING_APPROVAL" && (
        <div className="row">
          {canApprove ? (
            <>
              <Button onClick={() => approve.mutate(id)}>Approve</Button>
              <Button variant="secondary" onClick={() => reject.mutate(id)}>
                Send back
              </Button>
            </>
          ) : (
            <p>Waiting for approval: a different user with approval rights must approve it.</p>
          )}
        </div>
      )}
      <h4>What the model learned</h4>
      <WeightsChart weights={weights} />
    </section>
  );
}

/** Versioned matching profiles: edit, train, submit and (as a different user) approve. */
export function ProfilesPanel() {
  const profiles = useProfiles();
  const create = useCreateProfile();
  const [selected, setSelected] = useState<string | null>(null);
  return (
    <section aria-labelledby="profiles-title">
      <h2 id="profiles-title">Matching profiles</h2>
      <p>
        Until a profile is approved, matching keeps using the built-in model
        (resident-match-v1).
      </p>
      <Button
        onClick={() =>
          create.mutate({ name: "resident", config: DEFAULT_CONFIG }, { onSuccess: (p) => setSelected(p.profileId ?? null) })
        }
        disabled={create.isPending}
      >
        New profile from defaults
      </Button>
      <QueryState
        query={profiles}
        isEmpty={(list) => list.length === 0}
        emptyText="No profiles yet."
      >
        {(list: Profile[]) => {
          const current = list.find((p) => p.profileId === selected) ?? list[0];
          return (
            <>
              <ul aria-label="Profiles">
                {list.map((p) => (
                  <li key={p.profileId}>
                    <Button
                      variant={p.profileId === current?.profileId ? "primary" : "secondary"}
                      onClick={() => setSelected(p.profileId ?? null)}
                    >
                      {p.name} v{p.version} · {p.status}
                    </Button>
                  </li>
                ))}
              </ul>
              {current && <ProfileEditor key={current.profileId} profile={current} />}
            </>
          );
        }}
      </QueryState>
    </section>
  );
}
