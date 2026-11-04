# ADR-0018: Every endpoint declares its permission; anything undeclared is denied

Status: accepted (P1 Part 2).

## Context
P1 Part 1 made every `/api/**` call require a session, but any signed-in user could still call any endpoint. The
platform has six roles (`PLATFORM_ADMIN`, `STATE_ADMIN`, `DATA_ENGINEER`, `DATA_STEWARD`, `PROGRAM_USER`, `AUDITOR`)
built from 25 permissions (V16), and a new endpoint must not be open just because nobody thought about it.

## Decision
- **The HTTP endpoint is the entry point.** Each controller method carries `@PreAuthorize` naming the permission(s)
  it needs (`hasAuthority('PERM_X')`, `hasAnyAuthority(...)`), or `permitAll()` / `isAuthenticated()` for the few
  public and sign-in endpoints, or a rule of `AuthorizationService` (`@access.*`): setup endpoints are open only in
  setup mode, creating the first administrator only while no user exists, and the views that are not state-scoped
  (`/api/golden-residents` before v1, `/api/state-packs`, the Golden build) need every state. Services are not
  proxied: the background onboarding thread and scheduled jobs act for the platform, and there is no other way in.
- **Default deny.** `DefaultDenyInterceptor` refuses (403) any platform handler without a rule, and
  `EndpointAuthorizationTest` fails the build for one.
- **Roles are reference data** in V16, replaced wholesale (not merged with whatever an earlier seed wrote):
  `PLATFORM_ADMIN` everything; `STATE_ADMIN` the Studio, onboarding, approving publications and reading its states'
  data unmasked on request; `DATA_ENGINEER` the Studio and onboarding but never approving a publication;
  `DATA_STEWARD` residents (unmasked on request), match evidence, lineage and stewardship decisions; `PROGRAM_USER`
  residents, families, benefits and reports with personal data masked (no lineage to raw records); `AUDITOR`
  read-only everything plus the audit trail, never a decision.
- A user who must change the password holds no permission at all until it is changed (ADR-0017).

## Consequences
`EndpointAuthorizationTest` writes down the expected rule of every endpoint independently of its annotation,
checks that the table and the served endpoints are the same set, then calls every endpoint as each role (with the
role's permissions read from the database) and anonymously: allowed calls must not answer 401/403, denied calls
must answer 403 (401 anonymously). It also requires `@Audited` on every write (ADR-0020). Adding an endpoint
therefore means choosing its permission in two places.
