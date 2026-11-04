# M12 operational UI architecture

The M12 UI is a Vite/React/TypeScript application in `web/onboarding-ui`. It is a read-mostly operational view; the persistent header explicitly says **Development build — no authentication**. It must not be presented as a production-authenticated application.

## Structure and navigation

`src/app/features.ts` is the single registry of routes and sidebar entries. Feature modules are organized by operational capability: overview, onboarding runs, state packs, canonical residents, matching evidence, Golden Resident 360, and data quality. The Configure section holds the **Onboarding Studio** (`src/features/studio`: States list, nine-step wizard, Portability evidence) and **Platform settings** (`src/features/setup`). Until the platform database is configured, `SetupGate` renders only the Setup screen.

The shared shell provides landmarks, skip navigation, backend health status, and consistent page headers. Shared components provide query loading/error/not-found/empty states, semantic data tables, pagination, filters, status badges and detail panels.

## API contract

The UI uses `openapi-fetch` with types generated from committed `openapi.json` into `src/api/schema.d.ts`; aliases in `src/api/types.ts` do not duplicate DTO shapes. Run `npm run gen:api` after a backend contract change and `npm run check:api` in CI to regenerate/verify the generated contract. All M12 read views use `/api/v1` endpoints and list views send page/filter values to the backend.

## Internationalization and testing

Text is in `src/i18n/en.json`, accessed through the small fallback-capable i18n provider. The test strategy is component tests for the registry, shell/query states, filters and representative detail/list paths, plus Playwright for the real backend/UI journey. The shell and controls use semantic landmarks, labels, keyboard-focusable links/buttons, and are intended for automated axe checks.

## Local use

Start Common Core on port 8080, then run `npm run dev` from `web/onboarding-ui`. `UDDP_API_URL` can point Vite’s same-origin proxy to another local backend. The Studio and Setup screens write through `/api/studio` and `/api/setup`. `scripts/run-demo.ps1` serves the built UI on port 4173 against the backend on 8081; the Playwright journey (`e2e/`, Edge channel) is run by `scripts/run-studio-acceptance.ps1`.
