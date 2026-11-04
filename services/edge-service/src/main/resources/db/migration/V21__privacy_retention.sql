-- P1 Part 5b: data retention. Expired payloads are dropped while IDs and metadata stay, so lineage still resolves;
-- append-only audit events are soft-deleted through tombstones (hidden from reads, never updated or deleted).

ALTER TABLE raw_record ADD COLUMN payload_purged_at timestamptz;
ALTER TABLE canonical_resident ADD COLUMN payload_purged_at timestamptz;

CREATE TABLE retention_run (
    run_id uuid PRIMARY KEY,
    started_at timestamptz NOT NULL DEFAULT now(),
    triggered_by text NOT NULL,
    dry_run boolean NOT NULL,
    counts jsonb NOT NULL
);

CREATE TABLE retention_tombstone (
    category text NOT NULL,
    record_id text NOT NULL,
    purged_at timestamptz NOT NULL DEFAULT now(),
    run_id uuid NOT NULL REFERENCES retention_run(run_id),
    PRIMARY KEY (category, record_id)
);
