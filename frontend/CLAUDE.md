# Frontend

React + TypeScript + Vite, talking to whichever backend `VITE_API_BASE_URL`
(in `.env`) points at — see the root `CLAUDE.md` for how the two halves
talk. No server-rendering, no proxy layer: every call goes straight from the
browser to the backend's REST API.

## Commands

- Dev server: `npm run dev` (Vite — prints the actual port, usually 5173).
- Before pushing, run all three — CI runs them independently, so passing a
  subset locally is not enough:
  - `npx tsc -b` (typecheck). **Not** `tsc --noEmit` — it silently no-ops
    against this repo's project-references `tsconfig.json` (`"files": []`)
    and always exits 0 regardless of real errors.
  - `npm run lint` (eslint) — has caught real issues `tsc`+`vitest` both
    missed (a strict react-hooks config, among other things).
  - `npm test` (vitest).
- Build: `npm run build`.

## Conventions

- All server state goes through `@tanstack/react-query` — a `useQuery` keyed
  by resource, a `useMutation` that invalidates the relevant query key(s) on
  success. No other client-side data layer.
- All HTTP calls go through `src/api/client.ts` (`apiFetch` for JSON,
  `apiFetchStream` for a raw `Response` — SSE or binary). The Bearer token
  is attached automatically; `apiFetch`'s `body` accepts a plain object
  (JSON-stringified) or a `FormData` (multipart, e.g. a file upload) — it
  special-cases `FormData` to skip the JSON `Content-Type` header.
- No CSS framework — inline `style={{...}}` objects throughout, plus a small
  set of CSS custom properties for the theme (`var(--ink)`, `var(--ink2)`,
  `var(--ink3)`, `var(--card)`, `var(--elev)`, `var(--line)`,
  `var(--ember)`, ...). `src/components/Card.tsx` is the one shared layout
  primitive.
- A destructive action (delete, retire, unlink, ...) confirms via a plain
  `window.confirm(...)` before mutating — no custom modal component for
  this.
- An authenticated binary resource (e.g. a shoe wear photo) can't be loaded
  via a plain `<img src="...">`, since the browser won't attach the Bearer
  token — fetch it as a blob via `apiFetchStream` and render a
  `URL.createObjectURL(...)` object URL instead, revoking it on
  unmount/change (see `screens/gear/ShoePhotoImage.tsx` for the pattern).
- `src/lib/csv.ts` has a small dependency-free CSV parser (handles quoted
  fields) used by the admin shoe-catalog and gear CSV import flows — reuse
  it rather than writing another one.
