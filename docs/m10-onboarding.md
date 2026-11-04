# M10 onboarding orchestration

M10 adds a persisted, synchronous control plane for onboarding a State Pack source. A run records the
exact State Pack ID and version, the source, its status, its current stage and any failure. Every stage
attempt is a row with its own execution ID, timestamps, input/output counts, the identifiers it produced
and any error. Nothing in the orchestrator is state-specific: everything comes from the State Pack.

Tables (Flyway `V9__onboarding_orchestration.sql`): `state_pack_lifecycle`, `onboarding_run`,
`onboarding_stage_execution`. M11 (`V10`) adds `onboarding_run.processing_mode`.

## Processing mode (M11)

A run's `processingMode` is `FULL` (the default, M10 behaviour) or `INCREMENTAL`. `FULL` runs
`python -m matching.run` over every latest canonical Resident and re-evaluates every Golden group. `INCREMENTAL`
runs `python -m matching.run --incremental`, which scores only pairs touching records that are not yet current
Golden members, and the Golden build then joins those records to the current Golden set; every other Golden ID is
untouched (see `docs/golden-lifecycle.md`). `MATCHED` reports the records the run considered as its input count.

## State Pack lifecycle

State Packs are logical, versioned configuration contracts. PostgreSQL lifecycle records are the operational source
of truth for whether a pack is configured and visible. Repository YAML remains a development/test/bootstrap fixture,
not operational state discovery; the normal product authoring route is Onboarding Studio, not a State Pack import.

`DRAFT -> VALIDATED -> ACTIVE -> RETIRED`, one step at a time. Registering a pack creates its DRAFT row
for the version in its `state.yaml`. Only an ACTIVE pack/version can start a run; historical runs keep
the pack/version they started with after it is retired.

## Stages

Stages run strictly in this order. Each completed stage stores `input_count` / `output_count`:

| Stage | What it does | input / output |
| --- | --- | --- |
| `PACK_VALIDATED` | the loaded pack's version equals the run's version | 0 / 0 |
| `SOURCE_TESTED` | the source's configured `location` is readable | 0 / 0 |
| `INGESTED` | creates an `ingestion_batch`, runs the source's generic Hop template (`hopTemplate`) | raw records / raw records |
| `MAPPED` | canonical processing of that batch (mapping, validation, DQ) | raw read / canonical mapped |
| `DQ_CHECKED` | reads the persisted DQ evaluation of that batch | records evaluated / records affected |
| `MATCHED` | runs the Python/Splink module (`python -m matching.run`, `--incremental` for INCREMENTAL runs) | records considered / cluster members |
| `GOLDEN_BUILT` | Golden build from that matching run (carry-forward, M11) | canonical considered / Golden groups |
| `PUBLISHED` | marks the run complete | 0 / 0 |

`INGESTED` stores the batch ID used by `MAPPED` and `DQ_CHECKED`; `MATCHED` stores the matching run ID used
by `GOLDEN_BUILT`. Duration is `completed_at - started_at`.

Run status is `RUNNING` while stages remain, `FAILED` after a stage fails, and `COMPLETED` only once
`PUBLISHED` has completed (set in the same transaction).

## Failure, resume and concurrency

- A failing stage stores its attempt as `FAILED` with `error_message`, sets the run to `FAILED` with
  `failure_message`, and stops. Earlier stages are never re-executed.
- `resume` is allowed only for a `FAILED` run. It retries the failed stage as a new attempt (attempt 2, 3,
  ... with a new execution ID) and, on success, leaves the run `RUNNING` at the next stage. Run the rest
  with `next` or named stages. Resuming a `RUNNING` or `COMPLETED` run is rejected (409).
- A stage is claimed with one conditional insert (the run must be `RUNNING` at that stage), and a unique
  index allows at most one `RUNNING` stage execution per run. Two concurrent `next`/`resume` calls can
  therefore never execute a stage twice; the loser gets 409.
- If the application process dies mid-stage, that stage stays `RUNNING` and blocks the run (see known
  limitations in `docs/milestones/M10_ACCEPTANCE.md`).

## REST API (`/api/onboarding`)

Errors are JSON `{"status": 409, "error": "CONFLICT", "message": "..."}`: 404 `NOT_FOUND` for an unknown run
or State Pack, 409 `CONFLICT` for a request the run or lifecycle state does not allow, 400 `BAD_REQUEST` for
malformed input, and 500 `STAGE_FAILED` (with `runId` and `stage`) when a stage executed and failed.

| Method and path | Result |
| --- | --- |
| `POST /state-packs/{packId}/register` | 201 `{"statePackId": "..."}` |
| `POST /state-packs/{packId}/{version}/transitions/{VALIDATED\|ACTIVE\|RETIRED}` | 204 |
| `POST /runs` | 201 `{"runId", "mode", "processingMode", "status"}` |
| `GET /runs/{runId}` | 200 run with its `stages` |
| `GET /states/{packIdOrStateCode}/runs` | 200 run history, newest first |
| `POST /runs/{runId}/next` | 204, executes the next stage |
| `POST /runs/{runId}/stages/{stage}` | 204, executes that stage if it is next; otherwise 409 |
| `POST /runs/{runId}/resume` | 204, retries the failed stage |

Examples (`state-4` is the M10 configuration fixture):

```bash
curl -X POST localhost:8080/api/onboarding/state-packs/state-4/register
curl -X POST localhost:8080/api/onboarding/state-packs/state-4/1.0.0/transitions/VALIDATED
curl -X POST localhost:8080/api/onboarding/state-packs/state-4/1.0.0/transitions/ACTIVE

# Stage by stage (mode defaults to STEP: the run is created, no stage runs yet)
curl -X POST localhost:8080/api/onboarding/runs -H 'Content-Type: application/json' \
  -d '{"packId":"state-4","version":"1.0.0","sourceSystem":"citizen-register"}'
# -> {"runId":"<id>","mode":"STEP","status":"RUNNING"}
curl -X POST localhost:8080/api/onboarding/runs/<id>/stages/PACK_VALIDATED
curl -X POST localhost:8080/api/onboarding/runs/<id>/next
curl -X POST localhost:8080/api/onboarding/runs/<id>/stages/MATCHED
# -> 409 {"status":409,"error":"CONFLICT","message":"Invalid stage order: MATCHED requested but the next stage is INGESTED"}

# Full run
curl -X POST localhost:8080/api/onboarding/runs -H 'Content-Type: application/json' \
  -d '{"packId":"state-4","version":"1.0.0","sourceSystem":"citizen-register","mode":"FULL"}'
# -> {"runId":"<id>","mode":"FULL","status":"COMPLETED"}
#    or 500 {"status":500,"error":"STAGE_FAILED","message":"...","runId":"<id>","stage":"MATCHED"}
curl -X POST localhost:8080/api/onboarding/runs/<id>/resume

# Incremental onboarding (M11): only the new source's records are matched and joined to the current Golden set
curl -X POST localhost:8080/api/onboarding/runs -H 'Content-Type: application/json' \
  -d '{"packId":"state-4","version":"1.0.0","sourceSystem":"citizen-register","mode":"FULL","processingMode":"INCREMENTAL"}'

curl localhost:8080/api/onboarding/runs/<id>
curl localhost:8080/api/onboarding/states/ST4/runs
```

The service resolves source locations, the Hop pipeline and the Python module relative to the repository
root, so the application must run from `common-core/` (as `mvn spring-boot:run` does). The pack directory is
`uddp.state-pack-directory`; to onboard State 4 through the API, point it at a directory containing the
`state-4` pack.

## How to onboard a new state

1. Create `state-packs/<pack-id>/` with `state.yaml` and its `sources/`, `mappings/`, `crosswalks/`,
   `validation/`, `matching/` and `survivorship/` YAML (see `docs/state-pack-spec.md` and the State 4
   example in `m10-state-packs/state-4/`). Every file source names the generic template
   `hopTemplate: generic-file-ingest.hpl`.
2. Put the source file where its `location` points.
3. Run `mvn test`. `M10PortabilityGuardTest` fails if the new state required any engine, matching or Hop
   change, or if production code names the state.
4. Register the pack, transition it to VALIDATED and ACTIVE, and start a run (STEP or FULL) as above.
5. Check the run with `GET /runs/{runId}`; if a stage failed, fix the cause (configuration or data) and
   `resume`.

No Java, Python or Hop change is needed. If one seems needed, it must be generic and land in the engine for
every state, never as a state branch.

## Verification

- `OnboardingLifecycleTest`, `OnboardingPersistenceTest`, `OnboardingFailureResumeTest` (Testcontainers)
  and `OnboardingControllerTest` (MockMvc, full Spring stack) cover the lifecycle, the persisted stage
  history, failure/resume and concurrency, and the API contract. They replace only the Hop and Python
  process boundaries, with what those boundaries write.
- `scripts/reset-and-run-e2e.ps1` (3 states) and `scripts/reset-and-run-m10-e2e.ps1` (3 states + State 4)
  prove the real generic Hop ingestion and Splink matching on a throwaway database.
- `scripts/reset-and-run-onboarding-api-e2e.ps1` proves this API itself with the real `ProcessHopRunner` and
  `PythonMatchingRunner`: every pack is registered, activated and onboarded through `/api/onboarding` only (the first
  run FULL, later runs INCREMENTAL) into a throwaway database; it asserts raw 7, canonical 7, Golden 5 and that no
  stored failure text contains the password.
- Hop receives the database settings through a temporary JVM argument file deleted after the run, and every stored
  or returned failure message is redacted (ADR-0013).

See also [Held and rejected records](held-and-rejected-records.md) for what an onboarding run holds or rejects, the release gate on the held share and the batch rejection report.
