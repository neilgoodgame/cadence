# E2E tests (Playwright)

Real-browser smoke tests, separate from vitest's unit/component suite (`npm test`).
These drive the actual app in Chromium against a real backend - no mocks.

## Running

1. Have the Java backend up (`cd ../backend_java && docker compose up -d`) - these tests
   hit it for real (signup, auth, etc.), same as a person using the app.
2. `npm run test:e2e` (headless) or `npm run test:e2e:ui` (Playwright's interactive UI
   runner, good for debugging a failure). Playwright starts the Vite dev server itself
   (see `playwright.config.ts`'s `webServer`) if one isn't already running on :5173.

## Conventions

- Each test gets its own fresh athlete account via `signUpNewAthlete()` in `helpers.ts` -
  signs up through the real UI with a unique generated email. Don't reuse a shared/manual
  test account here; isolation keeps tests independent and avoids clashing with anyone
  poking at that account by hand.
- **Don't try to cache/share a login across tests via Playwright's `storageState`.**
  `backend_java`'s refresh token is single-use and rotated on every redemption (see
  `AuthContext.tsx`'s `doRefresh` comment), and every full page load re-triggers a silent
  refresh on mount - so a saved storageState's baked-in refresh token only ever works for
  the *first* test that loads it; everything after that gets silently logged out. Plain
  per-test signup (no shared state) is the correct model here, not just the simple one.
- One consequence of per-test signup: `POST /v1/auth/register` is rate-limited to 5/hour
  per IP (`RegistrationRateLimiter` in `backend_java`, same limiter regardless of whether
  the call comes from the UI or a script), so don't run this suite more than a handful of
  times back-to-back locally, and keep the number of auth-signup tests small. If it
  outgrows that budget, raise `REGISTER_RATE_LIMIT_MAX_ATTEMPTS` for local/CI runs rather
  than trying to route around the limiter.
- Keep this suite thin - a handful of critical flows (auth, scheduling, tagging), not full
  screen-by-screen coverage. Vitest is still the right tool for component/logic-level
  testing; reach for Playwright only when the thing under test is real browser behavior
  (layout, modal stacking, multi-step flows against a live API).
