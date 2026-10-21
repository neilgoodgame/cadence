# Login: original mock vs live app

- Original mock: `Login.dc.html` (repo root, unmodified)
- Updated mock: `design-updates/Login.dc.html`
- Live source: `frontend/src/screens/LoginScreen.tsx`
- Screenshots: `design-updates/screenshots/` (`login-live-signin.png`, `login-live-create-account.png`, `login-design-updated.png`)

The updated mock was changed to match the live app. Each row below says how the
original mock differed. To restore a mock-only idea, copy it back from the
original file.

Checked: Teal theme only, Sign in and Create account states, visually in Safari
and against the React source.
Not checked: Violet and Day themes, narrow viewports, hover/focus states, the
error and submitting states in a browser.

## Left brand panel

| Area | Original mock | Live app |
|---|---|---|
| Structure | Three rows: brand mark at top, copy vertically centred, testimonial at bottom. `padding: 36px 48px`, `gap: 20px` | Single column, everything vertically centred. `padding: 48px` |
| Width | `width: 46%` | `flex-basis: 46%` (same max 680px) |
| Text colour | `#f3f1ee` | `#fff` |
| Grid lines | Separate absolutely-positioned layer, 34px squares | `background-image` on the panel, 32px squares |
| Ember glow | 480px blurred radial glow, top-right | None |
| Brand mark | 30px ember square with a 13px white inner square; `gap: 12px` | 30px plain ember square; `gap: 10px`; 32px below it |
| Eyebrow ("TRAINING INTELLIGENCE") | 20px bottom margin | 14px bottom margin |
| Headline | `margin: 0`, `max-width: 13ch`, `text-wrap: balance` | `margin: 0 0 16px`, no max-width, no balance |
| Paragraph copy | "Import from Garmin, Wahoo or Strava and Cadence turns raw FIT files into form, fatigue and race-readiness — for you and everyone you train with." | "Upload activities from any device, see the analysis that matters, and plan what comes next." |
| Paragraph style | `rgba(243,241,238,0.62)`, `margin: 18px 0 0`, `max-width: 38ch` | `opacity: 0.7`, no margin, no max-width |
| Sparkline | Ember area/line chart, 74px tall | Removed |
| Stat tiles | 486 activities synced, +8.4 form (TSB), 265w threshold | Removed |
| Testimonial | Gradient avatar, "The first tool that made my whole squad's data legible." — Lena V., triathlon coach | Removed |

## Right auth panel

| Area | Original mock | Live app |
|---|---|---|
| Panel padding | `padding: 40px` | None |
| Form width | `width: 100%; max-width: 392px` | Fixed `width: 392px` |
| Tab row | `margin-bottom: 28px`; tab padding `0 2px 12px` | `margin-bottom: 24px`; tab padding `0 0 12px` |
| Subhead | `margin: 6px 0 26px` | `margin: 6px 0 24px` |
| Social sign-in | "Continue with Strava", Google and Apple buttons | Removed |
| Divider | "OR WITH EMAIL" rule | Removed |
| Password label row | Label plus an ember "Forgot?" link, sign-in only | Label only |
| Submit button | Text plus right-arrow icon, flex with `gap: 8px` | Text only, centred |
| Footer line | "© 2026 Cadence · Privacy · Terms" pinned to the bottom | Removed |

## Live behaviour the mock does not show

These are in the app but are not drawn in either mock:

- **Error message:** a 13px ember-coloured line between the password field and the submit button when sign-in or registration fails.
- **Submitting state:** the submit button drops to 70% opacity while the request is in flight.
- **Validation:** all fields are `required`, and the password has `minLength=10` on Create account only.
- **Theme toggle:** the live pill is about 12px narrower than the mock's (`padding: 6px 13px` per segment in the mock). I did not open `frontend/src/layout/ThemeToggle.tsx` to confirm the live padding.

## Intentionally left as in the original mock

- CSS variable names are camelCase in the mock (`--brandBg`, `--gridLine`) and kebab-case in the app (`--brand-bg`, `--grid-line`).
- The submit button links to `Dashboard.dc.html` so the prototype can navigate. The live app submits a form and calls the API.
- Template bindings (`{{ themeVars }}`, `sc-if`, etc.) and the three theme variants.

## Screenshot notes

- `login-design-updated.png` was captured at a shorter Safari viewport than the live screenshots, so its content sits higher. That is vertical centring, not a layout difference.
- `login-live-create-account.png` shows Safari's autofill in the email and password fields. That is not part of the app.
