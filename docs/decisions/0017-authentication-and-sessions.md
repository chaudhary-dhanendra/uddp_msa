# ADR-0017: Server-side sessions, local accounts with bcrypt and lockout, optional OIDC

Status: accepted (P1 Part 1).

## Context
Until P1 the platform had no authentication: every API answered anyone who could reach it, and the UI showed a
"no authentication" banner. A first security foundation (V15: users, roles, permissions, state scopes, an audit
table, `spring-boot-starter-security`) made every API require a session, but the UI had no sign-in screen, all demo
users shared the password `demo`, and there was no lockout, password policy or forced change.

## Decision
- **Sessions, not tokens.** The UI and the API are one origin (the UI server proxies `/api`), so the browser keeps a
  server-side session behind the `UDDP_SESSION` cookie (HttpOnly, SameSite=Lax, Secure over HTTPS). No token is ever
  readable by page scripts. Signing in always starts a new session (the old one is invalidated), so a session ID
  known before sign-in is useless. The idle timeout is `server.servlet.session.timeout` (30 minutes by default);
  the UI shows the sign-in screen with "your session has expired" on the next 401.
- **CSRF.** Every write needs the token from the `XSRF-TOKEN` cookie (readable by design) in the `X-XSRF-TOKEN`
  header; the UI's API client adds it, the multipart upload adds it itself, and every response carries the cookie.
  The token is not masked per request (the SPA echoes the cookie value).
- **Local accounts.** Passwords are bcrypt (cost 12); hashes are upgraded on sign-in when the cost changes. Policy:
  at least 12 characters (configurable, never below 8, at most 128), upper and lower case, a digit and a symbol, not
  containing the username, not a well-known password. Five failed sign-ins lock the account for 15 minutes
  (`uddp.security.lockout.*`); the reply never says whether the username exists, the password was wrong or the
  account is locked or disabled, and an unknown username costs the same bcrypt verification. A new or reset password
  is random, shown once and must be changed at the next sign-in: until then the session has no permission at all
  (403 `PASSWORD_CHANGE_REQUIRED`) except changing the password and signing out.
- **The signed-in user is reloaded on every request** (`PrincipalRefreshFilter`), so disabling a user ends the
  session at once and role or state-scope changes apply to the next request.
- **No shared demo password.** The demo seeder is off unless `uddp.security.demo.seed-users=true` (demo mode). It
  creates one user per role with its own random one-time password, written once to the console, or, when
  `uddp.security.demo.credentials-file` is set (as `scripts/run-demo.ps1 -DemoUsers` does), to that owner-only
  file, which the script prints and deletes. Never to the log.
- **Setup mode.** Before a database exists the Setup endpoints are open (nothing to protect, nobody to sign in). Once
  the database is ready and has no user, the Setup screen creates the first user, a `PLATFORM_ADMIN` for every state,
  with the password the person chooses (it must meet the policy), and signs them in. After that, setup and settings
  need `PLATFORM_SETTINGS`; the public status shows only the mode and whether the first administrator is needed.
- **Optional OIDC single sign-on** behind `uddp.security.oidc.*` (any standard issuer; Keycloak in the tests). The
  issuer's metadata is discovered on first use, so the platform starts while the provider is down; local sign-in stays
  available. Users are matched by issuer subject and created on first sign-in. Roles and state scopes come only
  from the provider's groups (`group-roles`, `group-states`) and are refreshed at every sign-in; a user without a
  mapped role or state is refused, and a username already used by a local account is never taken over.

## Consequences
`AuthenticationApiTest` drives the real filter chain (sessions, CSRF, lockout, forced change, sign-out, disabled
users), `DemoSeedAndSetupAdminTest` the first administrator and the seeder, `OidcKeycloakTest` the authorization
code flow against a Keycloak Testcontainer with a generated realm. Tests written before P1 run as a test platform
administrator (`testsupport/TestSecurity`, test classpath only). The e2e scripts create the first administrator of
their throwaway database with a random in-memory password (`scripts/e2e/uddp-api.ps1`). Account recovery without an
administrator (forgotten password e-mail) is not in scope.
