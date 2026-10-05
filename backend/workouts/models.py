from django.db import models

from accounts.models import User
from core.models import PrefixedIDModel


class WorkoutFolder(PrefixedIDModel):
    id_prefix = "wfd"

    created_by = models.ForeignKey(User, on_delete=models.CASCADE, related_name="workout_folders")
    name = models.CharField(max_length=100)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["created_by", "name"], name="unique_athlete_folder_name"),
        ]

    def __str__(self) -> str:
        return self.name


class Workout(PrefixedIDModel):
    id_prefix = "wkt"

    SPORT_CHOICES = [
        ("bike", "Bike"),
        ("run", "Run"),
    ]

    # Internal-only — not part of the public Workout schema in openapi.yaml.
    # Used purely for list-scoping ("the athlete's workout library") and to
    # resolve the target athlete for permission checks on detail/update/delete.
    # Never serialize this field.
    created_by = models.ForeignKey(User, on_delete=models.CASCADE, related_name="workouts")

    name = models.CharField(max_length=200)
    sport = models.CharField(max_length=10, choices=SPORT_CHOICES)
    # Free-text classification (e.g. "vo2"). Not settable via any documented
    # request body — clients can never write it, so it just stays blank.
    type = models.CharField(max_length=50, blank=True, default="")
    duration = models.IntegerField(default=0)
    tss = models.IntegerField(default=0)
    folder = models.ForeignKey(WorkoutFolder, null=True, blank=True, on_delete=models.SET_NULL, related_name="workouts")
    tags = models.JSONField(default=list, blank=True)
    # Flattened per-leaf average target intensity, recomputed alongside duration/tss
    # whenever steps are replaced — cheap chart data for library cards/rows without
    # shipping the full step tree in the list response.
    chart_preview = models.JSONField(default=list, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self) -> str:
        return self.name


MATCH_SCAN_STATUS_CHOICES = [
    ("queued", "Queued"),
    ("processing", "Processing"),
    ("ready", "Ready"),
    ("failed", "Failed"),
]


class WorkoutMatchScan(PrefixedIDModel):
    """A background scan of the workout's athlete's own unmatched, same-sport activities for
    likely matches, ranked by Pearson correlation between each activity's actual power stream
    and the workout's planned %FTP-vs-time curve - see workouts.match_scan. Mirrors
    dataexport.ExportJob's shape (same status lifecycle, same total/processed item-progress
    pair) since fetching a full per-second Record stream per candidate is too slow to do
    synchronously in the request.
    """

    id_prefix = "wms"

    DURATION_BASIS_CHOICES = [("time", "Time"), ("distance", "Distance")]
    CORRELATION_BASIS_CHOICES = [("power", "Power"), ("laps", "Laps")]

    workout = models.ForeignKey(Workout, on_delete=models.CASCADE, related_name="match_scans")
    status = models.CharField(max_length=12, choices=MATCH_SCAN_STATUS_CHOICES, default="queued")
    # Null until the duration pre-filter has run (see match_scan.run_match_scan) - mirrors
    # ExportJob.total_items's own null-until-upfront-count-query window.
    total_candidates = models.IntegerField(null=True, blank=True)
    processed_candidates = models.IntegerField(default=0)
    # Which leaf step kinds counted toward the correlation for this scan - chosen once, at
    # creation, by whoever triggered it (see WorkoutMatchScanCreateView), not an athlete-wide
    # preference. Same JSON-list shape as Workout.tags below.
    excluded_step_kinds = models.JSONField(default=list)
    # Whether the candidate pre-filter compares each activity's actual moving_time or distance_km
    # against the workout's planned total - chosen once, at creation, same as excluded_step_kinds
    # above. "time" (the default) is exact for a time-ended plan; "distance" exists because a
    # distance-ended plan's `duration` column is itself just a pace estimate (see
    # match_scan.total_planned_distance_meters), so for e.g. a trail long run whose real pacing
    # varies with terrain, comparing distance (the one quantity the plan actually fixes) instead
    # of duration avoids rejecting a genuine match purely on normal pacing variance. The athlete
    # picks whichever matches how the workout's steps are actually structured - a mixed workout
    # (some time-ended, some distance-ended steps) is valid either way, just less precise.
    duration_basis = models.CharField(max_length=10, choices=DURATION_BASIS_CHOICES, default="time")
    # Whether the power stream is smoothed (see match_scan.SMOOTHING_WINDOW_SECONDS) before
    # correlating against the workout's plan - chosen once, at creation, same as the fields
    # above. Off by default: it changes the score itself (filtering real second-to-second
    # noise so a session whose segment averages genuinely match the plan isn't penalized for
    # terrain/stride variability within each segment), not just additional information, so an
    # athlete opts in deliberately rather than every scan's numbers shifting silently.
    smooth_power = models.BooleanField(default=False)
    # Which scoring method the correlation pass uses - chosen once, at creation, same as the
    # fields above. "power" (the default) correlates the activity's real power stream against
    # the plan's %FTP-vs-time/distance curve (see match_scan.correlate_records/
    # correlate_records_by_step_boundary) - "laps" ignores power entirely and instead checks
    # whether the activity's own real device laps structurally match the plan's steps (see
    # match_scan.correlate_laps): "did the athlete run the prescribed structure", not "did they
    # hit the prescribed numbers". Requires an exact lap-count match against the workout's
    # steps; duration_basis/smooth_power don't apply in this mode (no power stream involved).
    correlation_basis = models.CharField(max_length=10, choices=CORRELATION_BASIS_CHOICES, default="power")
    error_message = models.CharField(max_length=500, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    completed_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self) -> str:
        return f"{self.workout_id} match scan ({self.status})"


class WorkoutMatchScanCandidate(models.Model):
    """One candidate activity a scan evaluated, however low its correlation - not just the top
    N - so the full ranked list stays inspectable (this is what let us work out, during manual
    testing, that a real match was missing only because of a truncated search window, not a bad
    score)."""

    scan = models.ForeignKey(WorkoutMatchScan, on_delete=models.CASCADE, related_name="candidates")
    # String FK to activities.Activity to avoid a circular import at model-definition time
    # (activities already imports from workouts.calculations) - same convention Activity.workout
    # uses in reverse.
    activity = models.ForeignKey("activities.Activity", on_delete=models.CASCADE, related_name="+")
    correlation = models.FloatField()
    duration_diff_seconds = models.IntegerField()
    # Null whenever the workout's total planned distance couldn't be determined (e.g. a
    # time-ended-only bike workout, which has no run-pace-style distance inference) -
    # informational either way, like duration_diff_seconds, regardless of which basis this
    # scan actually filtered by.
    distance_diff_km = models.FloatField(null=True, blank=True)
    coverage = models.FloatField()
    # Informational only (regression-slope-derived), never used for ranking - see
    # match_scan.correlate_activity.
    implied_ftp = models.IntegerField(null=True, blank=True)

    class Meta:
        ordering = ["-correlation"]

    def __str__(self) -> str:
        return f"{self.scan_id} candidate {self.activity_id} (r={self.correlation:.2f})"


class WorkoutStep(models.Model):
    """Not fetched by its own id, so it uses a plain BigAutoField per the
    core.models.PrefixedIDModel convention. `order` (scoped to `parent`, or to
    `workout` for top-level steps) makes the nested `steps` tree on the workout
    detail response reconstructable.

    A step is either a leaf (`kind` in warmup/block/rec/cool — has an
    `end_type`/`duration`/`distance` and a target) or a `repeat` group (has
    `repeat` and owns child rows via `parent`, which may themselves be nested
    `repeat` groups). See the CHECK constraint below for the exact split.
    """

    KIND_CHOICES = [
        ("warmup", "Warmup"),
        ("block", "Block"),
        ("rec", "Recovery"),
        ("cool", "Cooldown"),
        ("repeat", "Repeat"),
    ]
    END_TYPE_CHOICES = [
        ("time", "Time"),
        ("distance", "Distance"),
        ("manual", "Manual"),
    ]
    TARGET_TYPE_CHOICES = [
        ("power", "Power"),
        ("hr", "Heart rate"),
        ("pace", "Pace"),
        ("cadence", "Cadence"),
        ("open", "Open"),
    ]
    TARGET2_TYPE_CHOICES = [
        ("cadence", "Cadence"),
        ("none", "None"),
    ]
    POWER_UNIT_CHOICES = [
        ("pct_ftp", "% FTP"),
        ("watts", "Watts"),
    ]

    workout = models.ForeignKey(Workout, on_delete=models.CASCADE, related_name="steps")
    parent = models.ForeignKey("self", null=True, blank=True, on_delete=models.CASCADE, related_name="children")
    order = models.IntegerField(default=0)
    kind = models.CharField(max_length=10, choices=KIND_CHOICES)
    # blank (not null) for `repeat` rows — see repeat_step_has_no_leaf_fields below.
    end_type = models.CharField(max_length=10, choices=END_TYPE_CHOICES, blank=True, default="")
    duration = models.IntegerField(null=True, blank=True)
    distance = models.IntegerField(null=True, blank=True)
    target_type = models.CharField(max_length=10, choices=TARGET_TYPE_CHOICES, blank=True, default="")
    target_low = models.FloatField(null=True, blank=True)
    target_high = models.FloatField(null=True, blank=True)
    # Only meaningful when target_type == "power" - which unit target_low/target_high are in
    # (% of FTP/critical_run_power, or absolute watts). Harmless default on every other row,
    # same convention as target2_type's default of "none" on rows where it doesn't apply.
    power_unit = models.CharField(max_length=10, choices=POWER_UNIT_CHOICES, blank=True, default="pct_ftp")
    target2_type = models.CharField(max_length=10, choices=TARGET2_TYPE_CHOICES, default="none")
    target2_low = models.FloatField(null=True, blank=True)
    target2_high = models.FloatField(null=True, blank=True)
    repeat = models.IntegerField(default=1)
    note = models.TextField(blank=True, default="")

    class Meta:
        ordering = ["order"]
        constraints = [
            models.UniqueConstraint(fields=["workout", "parent", "order"], name="unique_step_order_per_parent"),
            models.CheckConstraint(
                condition=(
                    models.Q(
                        kind="repeat",
                        end_type="",
                        duration__isnull=True,
                        distance__isnull=True,
                        target_type="",
                    )
                    | ~models.Q(kind="repeat")
                ),
                name="repeat_step_has_no_leaf_fields",
            ),
        ]

    def __str__(self) -> str:
        return f"{self.workout_id} step {self.order} ({self.kind})"
