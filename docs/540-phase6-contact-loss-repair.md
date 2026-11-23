# Phase 6: contact-loss / cadence-lock detection and repair

Design for issue #540's phase 6 (phase 5 is `540-phase5-agreement-trends.md`). This is the
failure-*classification* tooling #503's intro problem statement was actually motivated by (the
TICKR's intermittent contact loss), deliberately scoped out of phase 4's comparison *statistics*.
The tracking issue explicitly flags this phase as needing threshold/data-model decisions before
implementation - this doc makes those calls so an implementing agent has a concrete spec, not just
direction.

**Amended 2026-11-22 (post-design review):** overlapping-patch rejection added (§5); the
PROTECT-on-`from_source` post-unpatch consequence made explicit and accepted (§4); a held-level
exception to the run-length cap for heartrate, so long freezes - the canonical TICKR case - are no
longer missed by design (§2); a minimum-variance guard for the cadence-lock window correlation
(§3); flagged-segment transparency in patch responses and a sanity cap on explicit segment length
(§5); `VALID_FIELDS` made explicit including the power delegation (§2/§5); matching test cases
added (§8).

## 1. What already exists, and what's genuinely new

`dropout_detection.py` (`backend/activities/dropout_detection.py`, mirrored
`DropoutDetector` in Java - check its exact path under `backend_java/.../calc/` before adding
files) already does real contact-loss detection, but narrowly: a literal 0 W run on `power`,
confirmed by a reference stream or a cadence fallback. It's called today from `merge_apply.py`,
`activity_source_service.attach_stream`, and `compare_streams`'s own dropout pass - always
power, always a literal-zero run, never exposed as its own read endpoint, and its flags are never
persisted (recomputed fresh every call).

Phase 6 needs two things this doesn't cover, plus a way to act on either:

1. **Generalized contact loss** - "a sudden, implausible drop in one stream while another stays
   plausible" for fields *other than* power's literal-0 case, primarily `heartrate` (a TICKR
   dropout doesn't necessarily read a literal 0 - it can report a frozen/garbage value or a
   physiologically-impossible jump).
2. **Cadence lock** - entirely new: a wrist HR stream correlating with cadence instead of a
   reference strap/pedal stream. No existing module touches this at all.

**Decision**: do not generalize `dropout_detection.py` itself to cover this - it stays exactly as
it is (power's zero-glitch case, already relied on by three call sites and tested). Add two new,
separate pure-computation modules instead, matching its file-per-detector convention.

## 2. Generalized contact-loss detector

New module `backend/activities/implausible_jump_detection.py` (mirrored
`backend_java/.../calc/ImplausibleJumpDetector.java`), same reference-corroboration interface as
`dropout_detection.detect_dropouts` but single-tier, not two-tier: unlike
`detect_dropouts` there is no single-stream (cadence) fallback branch here at all (see the
docstring below) - confirmation requires a reference stream, full stop. Triggered by a
physiologically-impossible second-to-second jump instead of a literal zero - power's zero-glitch
case doesn't generalize because "coasting" (a real, large, instant drop to 0) is normal for power
but never physiologically normal for heartrate.

```python
_MAX_PHYSIOLOGICAL_DELTA = {"heartrate": 25, "cadence": 40}  # bpm/s, rpm/s - internal defaults,
# not request parameters, same status as dropout_detection's _MAX_RUN_SECONDS/_REFERENCE_MIN_WATTS.
# "power" is deliberately absent here - it keeps using dropout_detection.py, not this module.
_MAX_RUN_SECONDS = 10  # a longer implausible stretch is a real (if unusual) physiological event,
# not a sensor glitch - same reasoning as dropout_detection's own 5s cap, loosened slightly since
# an HR dropout-and-recovery can run a bit longer than a power zero-glitch. EXCEPTION, heartrate
# only: when the run's held level (median of its values) exceeds _MAX_PLAUSIBLE_HR, the length cap
# does not apply - a sustained implausible HR is never physiological (unlike power, where a
# sustained high reading is real effort), so a long freeze at an implausible level - the canonical
# TICKR failure - must not be dismissed as "unusual physiology". Reference confirmation still
# applies. Cadence keeps the plain cap (symmetry-only field, per the v1 scope note below).
_MAX_PLAUSIBLE_HR = 215  # bpm - above any documented human max; internal default like the others.

@dataclass(frozen=True)
class ImplausibleRun:
    start_t: int
    length: int
    peak_delta: float

def detect_implausible_jumps(
    primary: dict[int, int | None], field: str, reference: dict[int, int | None] | None,
) -> tuple[set[int], list[ImplausibleRun]]:
    """Finds runs of consecutive seconds where primary's second-to-second change exceeds
    _MAX_PHYSIOLOGICAL_DELTA[field] on entry and/or exit (a glitch looks like a spike: jump away,
    hold at an implausible level, jump back - not a smooth ramp), confirmed as a dropout only when
    short (<= _MAX_RUN_SECONDS) - except heartrate runs held above _MAX_PLAUSIBLE_HR, confirmed at
    any length (the freeze case) - and reference (same-timeline corroborating stream) stays smooth
    and plausible throughout the run - no reference, no confirmation (unlike dropout_detection's
    cadence fallback, there's no reliable single-stream signature for an HR glitch, so this
    returns nothing confirmed without a reference rather than guessing). Mirrors
    backend_java's ImplausibleJumpDetector - keep in sync."""
```

v1 scope for *this detector*: `field in {"heartrate", "cadence"}` only (matches
`_MAX_PHYSIOLOGICAL_DELTA`'s keys); `power` continues to go through `dropout_detection.py`
unchanged. That scope is for the detector, not the endpoint - section 5's `streams/flags`
accepts `activity_source_service.VALID_FIELDS` (`{heartrate, cadence, power}` - import and reuse
that existing constant, see section 4's note; don't define a second one), with `power`
delegating to `dropout_detection.detect_dropouts`; don't misread this section as excluding power
from the endpoint. Note in the docstring and PR
description that cadence support is included for symmetry but the issue's own motivating case is
heartrate (TICKR) - don't invest in cadence-specific tuning beyond "it runs" unless asked.

## 3. Cadence-lock detector

New module `backend/activities/cadence_lock_detection.py` (mirrored
`backend_java/.../calc/CadenceLockDetector.java`). Genuinely new algorithm - no precedent to
mirror in either backend, so the thresholds below are this doc's own proposal, to be tuned against
real TICKR data once implemented (flag as a follow-up, not a blocker).

```python
_WINDOW_SECONDS = 60
_STEP_SECONDS = 10         # sliding window hop
_LOCK_CORRELATION_THRESHOLD = 0.85   # Pearson r between HR and cadence within a window
_MIN_WINDOW_SAMPLES = 30   # reuse stream_comparison.MIN_OVERLAP_SAMPLES's floor, not a new constant value
_MIN_CADENCE_CV = 0.02     # coefficient of variation (std/mean) the window's cadence series must
# reach before its Pearson r is trusted - on a near-constant cadence (steady-state running, the
# common case) r between two low-variance series is noise-dominated and can spuriously cross the
# lock threshold, while the actual lock signature requires HR to track cadence *variation*, which
# needs variation. Untested guess, tuned alongside _LOCK_CORRELATION_THRESHOLD.

@dataclass(frozen=True)
class LockRun:
    start_t: int
    end_t: int  # note: DropoutRun/ImplausibleRun use start_t+length, not start_t+end_t - see
    # section 5's normalization note; stream_flag_service.py converts both shapes to one
    # consistent [start_t, end_t] inclusive pair before they reach the streams/flags response.
    cadence_correlation: float
    reference_correlation: float | None  # None if no reference stream was available

def detect_cadence_lock(
    hr_primary: dict[int, int], cadence: dict[int, int], reference_hr: dict[int, int] | None,
) -> list[LockRun]:
    """Slides a _WINDOW_SECONDS window (hop _STEP_SECONDS) over hr_primary's overlap with cadence;
    windows whose cadence coefficient of variation (std/mean) is below _MIN_CADENCE_CV are skipped
    outright - on a near-constant cadence (steady-state running, the common case) Pearson r between
    two low-variance series is noise-dominated and can spuriously cross the lock threshold, while
    the actual lock signature requires HR to track cadence *variation*, which needs variation. For
    each remaining window with >= _MIN_WINDOW_SAMPLES paired seconds, computes Pearson r between
    hr_primary and cadence (reuse stream_comparison's private _pearson via a small shared helper -
    promote it to a public pearson() in stream_comparison.py rather than duplicating the formula a
    third time). Flags a window when that correlation >= _LOCK_CORRELATION_THRESHOLD AND, when
    reference_hr is available, the window's HR-vs-reference correlation is lower than its
    HR-vs-cadence correlation (HR is tracking the wrist's own cadence sensor better than it's
    tracking a presumed-truth HR source - the actual lock signature). With no reference_hr, flags
    on the cadence-correlation threshold alone - weaker evidence, same "no reference, no
    confirmation" caution as detect_implausible_jumps, so the response/UI must visibly distinguish
    reference-confirmed flags from reference_hr=None ones rather than presenting both identically.
    Adjacent/overlapping flagged windows are merged into one LockRun. Mirrors backend_java's
    CadenceLockDetector - keep in sync."""
```

**Explicit judgment calls, flag in the PR**: the `0.85` threshold and `60s`/`10s` window are
untested guesses tuned for "plausible enough to try," not measured against real lock episodes -
the issue itself says this needs design work, and real tuning needs real flagged examples this
doc doesn't have access to. Ship it behind the same "always computed, never silently trusted"
posture `dropout_detection` already established (report confidence/evidence, let the athlete/UI
judge) rather than auto-patching on a lock flag.

## 4. The patch data model

New model `StreamPatch` in `backend/activities/models.py`, directly mirroring `Merge`'s
"recorded, auditable, reversible" shape (`models.py:557`) rather than inventing a different
pattern:

```python
class StreamPatch(PrefixedIDModel):
    """Issue #540 (phase 6): one applied repair - replaces activity's primary stream for field,
    at the given segments, with from_source's own stored reading there. Always patches the
    *primary* stream (never a stored ActivitySource) - repair targets what the athlete trains/
    races by; patching a non-primary stored source has no use case here and isn't supported.
    Reversible via unpatch_stream as long as this row exists - previous_values snapshots exactly
    what primary held before the patch, since (unlike Merge's overwrite rows) there's no stored
    upload file to re-parse for an arbitrary flagged-segment edit. Mirrors backend_java's
    StreamPatch entity - keep the two in sync."""

    id_prefix = "pat"

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="stream_patches")
    # Reuse activity_source_service.VALID_FIELDS ("power", "heartrate", "cadence") directly here
    # and in stream_flag_service.py - don't redefine a same-named constant in the new module.
    # merge_service.py already hit this exact collision risk and resolved it by importing
    # VALID_FIELDS aliased (`as ATTACHABLE_FIELDS`); follow that precedent rather than
    # reintroducing a second VALID_FIELDS that can silently drift from the original.
    field = models.CharField(max_length=20)  # activity_source_service.VALID_FIELDS
    from_source = models.ForeignKey(ActivitySource, on_delete=models.PROTECT, related_name="+")
    # Issue #540: "contact_loss" or "cadence_lock" when segments came from detect_stream_flags'
    # live detector output at patch time; null when the caller passed explicit segments instead -
    # the detector's own flags are never persisted (recomputed live, same as dropout_detection's
    # existing stance), so this just records which detector (if any) produced them, not a
    # reference back to a stored flag row.
    detected_kind = models.CharField(max_length=20, null=True, blank=True)  # noqa: DJ001
    segments = models.JSONField()  # [[start_t, end_t], ...] - inclusive, applied
    # {str(t): original_value_or_null} for exactly the patched seconds - small (bounded by
    # segment length, not the whole stream), unlike Merge's file-reparse restore path.
    previous_values = models.JSONField()
    created_at = models.DateTimeField(auto_now_add=True)
    undone_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        ordering = ["-created_at"]
```

`on_delete=models.PROTECT` on `from_source` (not `SET_NULL` like `Merge.attached_source`) -
deliberate difference: `Merge`'s audit survives its source being deleted because the merge itself
still happened; a `StreamPatch` without knowing which source it copied from can't be meaningfully
unpatched, so block the delete instead of letting the audit row go silently unrestorable. Note this
as a deliberate deviation from `Merge`'s precedent, not an oversight, in the PR description.

Accepted consequence: unpatch sets `undone_at` but keeps the row, so PROTECT keeps blocking
`from_source` deletion even for fully-undone patches - a source ever used in any patch is
permanently undeletable. Accepted deliberately: individual source deletion is rare, the audit row
is worth keeping indefinitely (it's what makes unpatch possible at all), and Django PROTECT can't
be made conditional on `undone_at` without a custom deletion check. If this ever actually bites
(an athlete blocked from deleting a source by an old undone patch), delete the stale StreamPatch
row then (admin/manual) rather than weakening PROTECT.

Migrations: `backend/activities/migrations/0037_streampatch.py` (next after `0036_...` -
re-check at implementation time in case other phase-5/unrelated migrations land first) and
`backend_java/src/main/resources/db/migration/V108__stream_patch.sql` (next after `V107__...`,
same caveat).

## 5. Detect / patch / unpatch operations

### Detect (read-only, never persists)

```
GET /v1/activities/{id}/streams/flags?field=&kind=contact_loss|cadence_lock&reference=
```

`reference` is a source ref (`"primary"` or an `ActivitySource.id`, same shape as
`compare_streams`' `a`/`b`) - the corroborating stream. For `kind=contact_loss`: loads `field`'s
primary series and `reference`'s series, runs `detect_implausible_jumps` for `heartrate`/
`cadence`, or re-runs `dropout_detection.detect_dropouts` unchanged for `power` (same function,
just newly exposed via this endpoint rather than only compare/merge/attach's internal calls).
For `kind=cadence_lock`: `field` must be `"heartrate"`; loads HR primary, the activity's own
cadence, and `reference` as `reference_hr`. Response: the run list plus, for `cadence_lock`,
whether each run was reference-confirmed (section 3's visibility requirement).
**Run-shape normalization:** `DropoutRun`/`ImplausibleRun` carry `start_t`+`length`; `LockRun`
already carries `start_t`+`end_t`. `stream_flag_service.py` converts both to one consistent
`[start_t, end_t]` inclusive pair (`end_t = start_t + length - 1` for the first two) before
they reach this response or feed `streams/patch`'s `segments: "flagged"` path - don't let the
two kinds diverge in shape. Service function:
`backend/activities/stream_flag_service.py` - new module, since this orchestrates two detectors
behind one endpoint rather than extending either detector module or
`activity_source_service.py` itself.

### Patch (write, supports `preview`)

```
POST /v1/activities/{id}/streams/patch
{ "field": "heartrate", "from_source": "src_...",
  "segments": [[1200, 1206]] | "flagged",
  "kind": "contact_loss",   // required when segments == "flagged", ignored otherwise
  "reference": "src_...",   // required when segments == "flagged" (passed through to detect)
  "preview": true }
```

`segments: "flagged"` means: call the same detection this endpoint's `GET .../flags` would, live,
right now, using `kind`/`reference`, and patch whatever it currently finds - **flags are never
stored**, matching `dropout_detection`'s existing "always recomputed, never persisted" stance (see
section 4's `detected_kind` comment); there is no separate "flag" row this references. Explicit
`segments` bypasses detection entirely - the caller already knows what to patch.

**Overlap rejection:** a patch is rejected (409 conflict - the repo's existing ConflictException /
Django equivalent, naming the overlapping patch id) when its segments overlap any *active*
(`undone_at IS NULL`) StreamPatch on the same activity + field. Without this, a second patch's
`previous_values` would snapshot the first patch's already-patched values and unpatch order would
matter (LIFO restores correctly; the reverse writes stale values over a still-active patch) -
rejecting overlaps makes unpatch order-independent. Test in section 8.

**Explicit-segment sanity cap:** reject explicit `segments` whose total length exceeds 3600 s
(1 h) - repair targets glitch runs, not whole-stream rewrites (that's merge/attach territory); a
bigger request is almost certainly a caller bug. `segments: "flagged"` is exempt (detector output
is bounded by the detectors' own run caps). Untested guess, flag in the PR.

**Flagged-segment transparency:** for `segments: "flagged"`, detection runs live at patch time
(never persisted - see section 4), so the response (preview *and* commit) must include the
segments actually applied, not just counts - otherwise a preview and its commit can silently
diverge if the underlying data changes between the two.

Implementation: reuse `merge_apply.py`'s field-rewrite machinery, not a new copy routine -
`_compute_stream_field`'s signature (target values, source values, offset, null_policy) is
almost exactly "patch a run of seconds from source into target" already; add a segment-restricted
variant (or a `segments: list[tuple[int,int]] | None` parameter to the existing function - prefer
extending it over forking it, since both now do "rewrite target from source, optionally
restricted to certain seconds") and reuse it here with `null_policy="overwrite"` always (a patch
always applies - that's the point) restricted to `segments`. Wrap in `transaction.atomic()` with
`transaction.set_rollback(True)` under `preview=True`, exactly `merge_service.py`'s own preview
pattern (`merge_service.py:158-173`) - don't invent a second preview mechanism.

After a real (non-preview) patch: snapshot `previous_values` from `Record` before rewriting,
rewrite `Record`'s `field` column for the patched seconds from `from_source`'s `ActivityStream`,
create the `StreamPatch` row, then run `activity_post_processing.recompute_after_merge` (the same
cascade `merge_apply`/`attach_stream` already trigger) since `Record` changed.

### Unpatch

```
POST /v1/activities/{id}/streams/unpatch
{ "patch_id": "pat_..." }   // patch_id optional, same "required only if >1 active" rule
                             // as POST .../unmerge's merge_id (views.py:650-657)
```

Restores `Record`'s `field` values at the patch's `segments` from `previous_values`, sets
`undone_at`, re-runs the recompute cascade. Fails if `patch_id` doesn't resolve to an un-undone
patch on this activity, or if more than one active patch exists for `field` and `patch_id` was
omitted - mirror `undo_merge`'s exact error wording style (`merge_service.py`, check its real
function name near `undo_merge`) for consistency.

## 6. API surface summary

- `GET /v1/activities/{id}/streams/flags` - new `StreamFlagsView`, `backend/activities/views.py`,
  routed in `urls.py` next to `streams/compare`.
- `POST /v1/activities/{id}/streams/patch` - new `StreamPatchView`.
- `POST /v1/activities/{id}/streams/unpatch` - new `StreamUnpatchView`.
- MCP, `ActivityMCPTools` (`backend/activities/mcp.py`, same home as `compare_streams`/
  `set_primary_source`): `detect_stream_flags(...)` (`ACTIVITIES_READ`), `patch_stream(...)` /
  `unpatch_stream(...)` (`ACTIVITIES_WRITE`).
- `openapi.yaml`: three new path entries modeled on `streams/compare`'s existing style
  (`openapi.yaml:1249`) and `POST .../merge` / `POST .../unmerge`'s request-body style for the
  write endpoints.

## 7. Frontend

In `SourcesTab.tsx`'s `StreamComparisonPanel` (where the existing overlay/difference/Bland-Altman
charts already render for a chosen pair), add a "Detect issues" action that calls
`detectStreamFlags` and highlights flagged runs as shaded bands on
`StreamComparisonOverlayChart.tsx` (check whether it already supports annotation bands; if not,
add a minimal `highlightRanges?: [number, number][]` prop rather than a parallel chart component).
A flagged run gets a "Patch from {source label}" button -> calls patch with `preview: true` first,
shows the before/after counts (mirrors `MergeDialog.tsx`'s existing preview-before-commit flow -
check it for the exact pattern before building a new one), then commits on confirmation. An
"Undo patch" action next to any applied patch, `window.confirm(...)`-gated per `frontend/
CLAUDE.md`'s destructive-action convention - mirror `Header.tsx`'s delete button (line ~430,
`window.confirm(\`Delete "${activity.name}"? This can't be undone.\`)`) for the actual confirm
pattern, not new copy. Note: `MergeDialog.tsx`'s own "Undo" button is *not* a valid precedent here
- it calls `undoMutation.mutate()` with no `window.confirm` guard at all, which is itself
inconsistent with `frontend/CLAUDE.md`'s convention; don't copy it as-is.

Given the real number of new interaction states (detect -> preview -> patch -> undo, times two
detector kinds), consider whether this belongs in a new `StreamFlagsCard.tsx` instead of growing
`SourcesTab.tsx`/`StreamComparisonPanel` further - `SourcesTab.tsx` is already 219 lines handling
three fields' worth of source-switching UI; don't make it the dumping ground for repair UI too if
a sibling component reads more cleanly. Judgment call for whoever implements this - not resolved
here.

## 8. Tests

- `backend/activities/tests/test_implausible_jump_detection.py`,
  `test_cadence_lock_detection.py` (unit, pure functions - mirror `dropout_detection` tests'
  style, check their exact file name).
- `backend/activities/tests/test_stream_flag_service.py` (unit).
- `backend/activities/tests/test_stream_patch.py` or extend `test_activity_source_service.py`
  (integration-ish - patch/unpatch round-trip, recompute cascade fired, `previous_values`
  restores exactly).
- Java mirrors: `ImplausibleJumpDetectorTest.java`, `CadenceLockDetectorTest.java`,
  `StreamFlagServiceTest.java` (or integration test, matching whichever style
  `StreamComparisonCalculatorTest.java` uses - unit, no Spring context needed for pure
  calculators), `StreamPatchServiceIntegrationTest.java` (watch the fixture-literal-collision
  gotcha in `backend_java/CLAUDE.md`).
- Overlap rejection: a second patch overlapping an active same-field patch 409s; after unpatch
  the same segments are accepted again.
- Long-freeze confirmation: a heartrate run > _MAX_RUN_SECONDS held above _MAX_PLAUSIBLE_HR with
  a smooth reference is confirmed (the canonical TICKR freeze); the same run at a plausible held
  level is not.
- Cadence-lock variance guard: a steady-state window (cadence CV < _MIN_CADENCE_CV) with high
  spurious r is not flagged.

## 9. Explicit open questions / deliberate scope cuts

1. Cadence-lock thresholds (`0.85`, `60s`/`10s`) are untuned guesses - real tuning needs real
   flagged TICKR episodes this design doc has no access to. Ship with visible confidence
   (reference-confirmed vs not), expect to revisit.
2. `power` is excluded from `implausible_jump_detection.py` by design (keeps using
   `dropout_detection.py` unchanged) - if a future need arises to patch power dropouts through
   this same `streams/flags`/`streams/patch` surface, `kind=contact_loss` for `field=power` should
   delegate to `dropout_detection.detect_dropouts` rather than needing a third detector; this
   doc's `stream_flag_service.py` sketch in section 5 already assumes that branch.
3. Patching only ever targets the primary stream (never a stored `ActivitySource`) - deliberate
   v1 scope cut per section 4; revisit if a real workflow wants the reverse direction.
4. Whether `detect_stream_flags` should also run automatically (e.g. a background job flagging
   likely contact-loss activities proactively) is explicitly out of scope here - this phase is
   on-demand detection + manual repair only, matching the issue's own "design work before
   implementation" framing rather than expanding it.
