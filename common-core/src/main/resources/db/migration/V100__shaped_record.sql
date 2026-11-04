create table shaped_record (
    raw_record_id uuid not null,
    state_pack_version text not null,
    shaping_digest text not null,
    fields jsonb not null,
    provenance jsonb not null,
    created_at timestamptz not null default now(),
    primary key (raw_record_id, shaping_digest)
);
