# ADR-0006: The only updates to history rows; retired IDs resolve through the API

Status: accepted (M11).

## Decision
- History is append-only. The only updates are one-time closes: `effective_to` on a Golden version or membership,
  and `status`, `retired_at` and `merged_into_id` when an identity retires. Canonical, Raw and matching rows are
  never updated or deleted.
- `GET /api/golden-residents/{id}` for a retired ID returns 200 with `status: RETIRED`, `mergedIntoId` and
  `resolvedGoldenResidentId` (the end of the merge chain), rather than 404 or a redirect, so clients holding old
  IDs can follow them. `GET /api/golden-residents/{id}/history` works for every ID.
