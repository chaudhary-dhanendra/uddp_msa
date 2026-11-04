# ADR-0007: A typed, paged read API under /api/v1, documented with springdoc OpenAPI

Status: accepted (M12).

## Context
The UI must use types generated from the backend's OpenAPI document, with no hand-written DTOs. The pre-M12 read
endpoints (`/api/golden-residents`, `/api/onboarding/runs/{id}`, ...) return `Map<String, Object>`, so their
OpenAPI schema is an untyped object; they have no paging, and tests depend on their exact bodies. M12 must not change
existing response contracts unless versioned.

## Decision
- Add the read side as new endpoints under `/api/v1/`, served by read services in `gov.uddp.core.read` and
  controllers in `gov.uddp.core.api.v1`. Every response is a Java record, so springdoc emits a named schema and
  `openapi-typescript` generates a matching TypeScript type.
- Every list is `Page<T>` (`items, page, size, totalItems, totalPages, sort`) with `page` (0-based), `size` (1..200,
  default 20) and `sort=field,asc|desc` from a per-endpoint allow-list mapped to SQL expressions (never
  concatenated from input). Every error is `ApiError` (`status, error, message, path`): 400 for invalid IDs,
  filters, page, size or sort; 404 for unknown resources.
- Pre-M12 endpoints stay byte-compatible. The UI uses them only for its allowed write actions (start run, next stage,
  resume, Golden build); records in `WriteApiSchemas` describe their existing bodies in OpenAPI without being used
  to build them.
- The read services are read-only (connections opened read-only); matching and Golden rows are never updated.
- springdoc-openapi 2.8.9 serves `/v3/api-docs` and `/swagger-ui.html` (paths `/api/**` and `/health`). The committed
  copy `web/onboarding-ui/openapi.json` is checked against the served document by `OpenApiContractTest`; the UI's
  `npm run gen:api` generates `src/api/schema.d.ts` from it, so the UI build needs no running backend.

## Consequences
Two read surfaces exist until the old map-based reads are deprecated (a later milestone can point them at the same
services). No migration was needed: every M12 query is served by existing indexes at MVP volumes.
