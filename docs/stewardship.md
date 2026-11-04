# Stewardship and manual review (M13)

Up to M11, only Splink decided who is the same person: POSSIBLE_MATCH pairs were stored but nobody could act on
them, and Golden Residents could be merged or split only by a new matching run. M13 adds a review queue and manual
merge/split, recorded as an append-only decision log that every later Golden build honours. Matching evidence and
canonical rows are never changed (architecture rule 3): stewardship decides *who is the same person* only through
constraints applied when Golden groups are formed. Decisions are in ADR-0010.

## 1. Data model (V12)

```text
stewardship_review_task      one per POSSIBLE_MATCH source-record pair, created by the Golden build that first sees it
                             record keys a < b, the matching run, canonicals, probability and weight at creation
stewardship_decision         append-only: ACCEPT_MATCH | REJECT_MATCH | MERGE | SPLIT, reviewer, reason,
                             review task (accept/reject), Golden IDs involved, created_at
stewardship_link_constraint  append-only: the decision's constraints on source-record-key pairs,
                             MUST_LINK or CANNOT_LINK
```

- A task is **OPEN** until a decision references it, then **DECIDED**. Tasks are never updated or deleted; the
  status is derived. A pair that already has a task (open or decided) never gets a second one, so a later matching
  run that scores the pair as POSSIBLE_MATCH again does not re-open a decided review.
- Constraints are keyed by **source record key** (ADR-0003), never by canonical ID, so they survive corrections,
  re-deliveries and State Pack version changes. The **effective** constraint of a pair is the one from its latest
  decision: a merge after a split restores the link, a split after a merge removes it.

## 2. Decisions and the constraints they record

| Decision | Input | Constraints |
| --- | --- | --- |
| `ACCEPT_MATCH` | an OPEN review task | `MUST_LINK(a, b)` |
| `REJECT_MATCH` | an OPEN review task | `CANNOT_LINK(a, b)` |
| `MERGE` | two (or more) ACTIVE Golden IDs | `MUST_LINK` for every pair of current members across the identities |
| `SPLIT` | an ACTIVE Golden ID and a non-empty proper subset of its current members (source record keys) | `CANNOT_LINK` for every pair across the two sides; `MUST_LINK` along each side |

Every decision needs a `reviewer` and a `reason`. There is no authentication until M17, so the reviewer is a
declared name, recorded as given.

## 3. How a Golden build applies constraints

`GoldenTargetGroups` forms groups exactly as in M11 (FULL: the run's clusters; INCREMENTAL: current groups joined by
the run's clusters), then:

1. **Separate.** Inside each group that holds both keys of an effective `CANNOT_LINK`, members are placed in
   key order into the first sub-group where they have no `CANNOT_LINK` partner, preferring a sub-group with a
   `MUST_LINK` partner; a member that fits nowhere starts a new sub-group. A split decision constrains every pair
   across its two sides, so it always yields exactly those two sides.
2. **Join.** `MUST_LINK` pairs are applied in decision order and union their groups, unless the union would put an
   effective `CANNOT_LINK` pair together; such a conflicting link is skipped and counted.

Constraints whose keys are not current participants are ignored. Carry-forward (ADR-0001) then assigns Golden IDs as
for any matching change: a merge retires the smaller (or younger) identity with `MERGED_INTO`; in a split the side
with the larger overlap keeps the ID and the other side gets a new ID with `SPLIT_FROM`. IDs are never reused.
The build's `golden_build.summary.stewardship` records how many constraints were in force and how many links were
skipped as conflicts.

After the groups are built, the same transaction creates review tasks for the run's POSSIBLE_MATCH pairs that have
none yet. Golden Families follow the new Golden Resident IDs in the same transaction (M14).

## 4. Applying a decision

A decision endpoint records the decision and its constraints in one transaction, then rebuilds Golden Residents
against the latest COMPLETED matching run, so the effect is visible at once and the response carries the build
summary. If there is no completed matching run the decision is rejected (409). If the rebuild fails, the decision
stays recorded and the next build applies it.

## 5. API

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/stewardship/reviews?status=OPEN\|DECIDED\|ALL` | Paged review queue: both records, current Golden IDs, probability, decision |
| `GET /api/v1/stewardship/reviews/{id}` | One task side by side, with the pair's match evidence |
| `POST /api/stewardship/reviews/{id}/decision` | `{decision: ACCEPT\|REJECT, reviewer, reason}` |
| `POST /api/stewardship/merge` | `{goldenResidentIds: [..], reviewer, reason}` |
| `POST /api/stewardship/split` | `{goldenResidentId, sourceRecordKeys: [..], reviewer, reason}` |
| `GET /api/v1/stewardship/decisions?goldenResidentId=` | Paged decision log with constraints |

Errors: 400 for a malformed request (blank reviewer or reason, a split that names no member, all members or a
non-member), 404 for an unknown task or Golden ID, 409 for a task already decided, a retired Golden ID, or no
completed matching run.

## 6. Limitations

- No screens yet: the M12 operational UI is unfinished; the queue, merge and split are API-only.
- No maker-checker or roles (M17); any caller can decide.
- Household (Family) stewardship is not covered; households follow their members' Golden IDs.
- Constraints are evaluated over all current participants on each build (fine at MVP size).

See also [Held and rejected records](held-and-rejected-records.md): rows held for a steward before they become canonical, bulk rejects that need a second steward, and the fix-in-pack links.
