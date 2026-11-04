# ADR-0019: State scoping in every read, and how cross-state records are shown

Status: accepted (P1 Part 2).

## Context
Users are assigned states (`user_state_scope`: state codes, or `ALL`). A state administrator or a program user for
ST1 must not see ST2 data through any endpoint: not in a list, a count, a report, a detail or a search. Some records
span states by design: a Golden Resident has members from several states (Ram Singh is ST1, ST2, ST3 and ST4), a
match pair or review task joins records of two states, a duplicate-benefit finding combines benefits from several.

## Decision
- **Where it is applied.** Every read service filters by the current user's scope in SQL
  (`Db.inScope(column)`, `GoldenVisibility.visible(alias)`), so totals and paging are computed over what the user
  may see. A detail outside the scope answers **404, exactly as an unknown ID**, so existence is not revealed.
  Writes on another state are refused (403 for an explicit state, 404 for a record found by ID).
- **Platform code sees everything.** A request always carries an authentication (anonymous without a session), so
  "no authentication at all" means platform code outside a request (startup, the background onboarding run, a
  scheduled job); `CurrentUser.scope()` gives it every state.
- **Golden Residents.** One is visible when any member, current or past, is from a state in scope. Members outside
  the scope are **hidden** (`hiddenMemberCount` says how many), `stateCodes` lists only states in scope, and a value
  that survived from a member outside the scope is **withheld**: no value, no winning record, no contributor from
  outside the scope (`withheld: true`, listed in `withheldFields`, removed from the payload and from version
  changes and events). Search results apply the same rule to the summary values. The survivorship itself is
  unchanged: the Golden record is one platform fact, only its presentation is scoped.
- **Pairs and review tasks** (match evidence, stewardship queue and decision log) are visible only when both
  records are in scope; run-level counts are recomputed over what is visible.
- **Households and benefits** belong to one state each and are filtered by it; a Resident 360's benefits list only
  in-scope benefits. Duplicate-benefit findings consider only in-scope benefits. The eligible-but-not-enrolled report
  counts enrolment in every state (so a resident enrolled elsewhere is not wrongly listed) but lists only visible
  residents, with withheld values left out.
- **Overview** shows only in-scope states and totals over them; **audit events** show in-scope events and events
  that concern no state (sign-ins, user administration).
- The pre-v1 views that are not scoped (`/api/golden-residents`, `/api/state-packs`) and platform-wide operations
  (the Golden build, which rebuilds every state) need the `ALL` scope.

## Consequences
`StateScopeApiTest` proves, as an ST1-only user holding every permission (so only the scope limits it), that no list,
count or report contains an ST2/ST3 record ID or state code, that every ST2 detail is 404 while a platform
administrator sees it, that Ram Singh is shown with only his ST1 member and withheld values, and that writes on ST2
are refused. A household view shows each member's Golden name under the PII masking rules (ADR-0022) without the
per-field withholding; households are single-state, so their members are in scope.
