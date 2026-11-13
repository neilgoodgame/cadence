# Frontend

React + TypeScript + Vite. Every call goes straight from the browser to the backend REST API at `VITE_API_BASE_URL` (see root `CLAUDE.md`). No SSR, no proxy.

## Commands (run from `frontend/`)

- Dev server: `npm run dev` (prints the port, usually 5173). Build: `npm run build`.
- Before pushing, all three must pass (CI runs them separately):
  1. `npx tsc -b`. Do **not** use `tsc --noEmit`; it always exits 0 here (project references, `"files": []`).
  2. `npm run lint`. It catches issues `tsc` and vitest miss.
  3. `npm test`
- E2E (Playwright, real backend): `npm run test:e2e` (see `e2e/README.md`). Only needed if you touched a flow it covers.
- Long output: redirect, then read the summary only.
  - `npx tsc -b > /tmp/tsc.log 2>&1; echo $?`
  - `npm test -- --reporter=dot` for a short vitest run; rerun one file once you know which failed.

## Conventions

- Server state: only `@tanstack/react-query`. A `useQuery` keyed by resource; a `useMutation` that invalidates the relevant keys on success.
- HTTP: only via `src/api/client.ts`. `apiFetch` for JSON (body may be an object or `FormData`), `apiFetchStream` for a raw `Response` (SSE or binary). The Bearer token is added automatically.
- Styling: no CSS framework. Use inline `style={{...}}` and the theme CSS variables (`var(--ink)`, `--ink2`, `--ink3`, `--card`, `--elev`, `--line`, `--ember`, ...). `src/components/Card.tsx` is the shared layout primitive.
- Destructive actions (delete, retire, unlink): confirm with `window.confirm(...)` before mutating. No custom modal.
- Authenticated images/binaries: `<img src>` can't send the Bearer token. Fetch a blob with `apiFetchStream`, show a `URL.createObjectURL(...)` URL, and revoke it on unmount/change (example: `screens/gear/ShoePhotoImage.tsx`).
- CSV parsing: reuse `src/lib/csv.ts` (handles quoted fields). Don't write another parser.
