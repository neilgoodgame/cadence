from django.contrib.postgres.fields import ArrayField
from django.db import models

from accounts.models import User
from core.models import PrefixedIDModel
from gear.models import Bike, Shoe
from workouts.models import Workout


class Activity(PrefixedIDModel):
    id_prefix = "act"

    SPORT_CHOICES = [
        ("bike", "Bike"),
        ("run", "Run"),
        ("swim", "Swim"),
        ("walk", "Walk"),
        ("row", "Row"),
        # Only created by multisport FIT imports: the parent spans the whole file
        # ("multisport") and each leg, transitions included, is a child activity
        # linked via parent_activity.
        ("multisport", "Multisport"),
        ("transition", "Transition"),
    ]
    ENVIRONMENT_CHOICES = [
        ("outdoor", "Outdoor"),
        ("indoor", "Indoor"),
    ]
    DISTANCE_SOURCE_CHOICES = [
        ("gps", "GPS"),
        ("footpod", "Footpod"),
        ("trainer", "Trainer"),
        ("manual", "Manual"),
    ]
    # Which of the two candidate running-power readings this run's Record.power was actually
    # resolved from - see uploads/processing.py::_select_running_power_source. Only ever set for
    # FIT-sourced run activities where the file's own ambiguity (native vs Stryd developer
    # field) made a choice necessary; blank for every other sport/format, where there's no such
    # choice to record. Recomputes (best efforts, derived stats, threshold history) compare this
    # against the athlete's *current* running_power_source preference, not just at ingest -
    # so this is a record of what was true about the file, not a frozen policy snapshot.
    POWER_SOURCE_CHOICES = [
        ("stryd", "Stryd"),
        ("native", "Native"),
    ]
    # Issue #503 (phase 1): which source (pedals/trainer) this bike activity's current primary
    # power stream was last attached/swapped from, if any - unlike power_source above, never set
    # at parse time (a single FIT file has no such ambiguity; only two separate recordings do).
    # Blank for the common case (never had a second source attached), which always matches the
    # athlete's bike_power_source preference - see matches_bike_power_preference below.
    BIKE_POWER_SOURCE_CHOICES = [
        ("pedals", "Pedals"),
        ("trainer", "Trainer"),
    ]
    # Issue #503 (phase 3): HR/cadence equivalents of bike_power_source above - same "null until
    # a manual swap" shape, but there's no classify()-by-device fallback for either: the tag
    # only ever exists if the athlete supplied one at attach time.
    HR_SOURCE_CHOICES = [
        ("strap", "Strap"),
        ("wrist", "Wrist"),
    ]
    CADENCE_SOURCE_CHOICES = [
        ("pedals", "Pedals"),
        ("trainer", "Trainer"),
        ("watch", "Watch"),
    ]

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="activities")
    sport = models.CharField(max_length=10, choices=SPORT_CHOICES)
    environment = models.CharField(max_length=10, choices=ENVIRONMENT_CHOICES, default="outdoor")
    # Always false when environment is indoor — enforced at upload time (Phase 5),
    # just a plain stored field for now.
    has_gps = models.BooleanField(default=False)
    name = models.CharField(max_length=200)
    start_date = models.DateTimeField()
    source = models.CharField(max_length=100, blank=True, default="")
    # Recording device from the file's metadata (FIT file_id), e.g. "Zwift" or
    # "Garmin Edge 830". Empty when the format doesn't carry it (GPX/TCX).
    device = models.CharField(max_length=100, blank=True, default="")
    moving_time = models.IntegerField(default=0)
    # Wall-clock elapsed time (includes pauses) - see issue #498. Null for historical rows
    # backfilled before this field existed and any future ingest path that doesn't set it
    # explicitly; callers should fall back to moving_time rather than treat null as zero.
    elapsed_seconds = models.IntegerField(null=True, blank=True)
    distance_km = models.FloatField(default=0)
    distance_source = models.CharField(max_length=10, choices=DISTANCE_SOURCE_CHOICES, default="gps")
    power_source = models.CharField(max_length=10, choices=POWER_SOURCE_CHOICES, blank=True, default="")
    bike_power_source = models.CharField(max_length=10, choices=BIKE_POWER_SOURCE_CHOICES, blank=True, default="")
    hr_source = models.CharField(max_length=10, choices=HR_SOURCE_CHOICES, blank=True, default="")
    cadence_source = models.CharField(max_length=10, choices=CADENCE_SOURCE_CHOICES, blank=True, default="")
    avg_power = models.IntegerField(null=True, blank=True)
    norm_power = models.IntegerField(null=True, blank=True)
    intensity = models.FloatField(null=True, blank=True)
    tss = models.IntegerField(default=0)
    avg_hr = models.IntegerField(null=True, blank=True)
    max_hr = models.IntegerField(null=True, blank=True)
    ascent = models.IntegerField(null=True, blank=True)
    start_weight_kg = models.FloatField(null=True, blank=True)
    end_weight_kg = models.FloatField(null=True, blank=True)
    fluids_ml = models.IntegerField(null=True, blank=True)
    avg_air_temp = models.FloatField(null=True, blank=True)
    avg_humidity = models.IntegerField(null=True, blank=True)
    # CORE body-temperature sensor developer fields, same "average over the record stream" shape
    # as avg_air_temp/avg_humidity above - added specifically so heat-strain/core-temp questions
    # (e.g. "which sessions had the highest heat strain in the last 3 months") can be answered by
    # one CQL-filtered list_activities call instead of an MCP client fetching every activity's
    # full stream and computing this itself.
    avg_heat_strain = models.FloatField(null=True, blank=True)
    max_heat_strain = models.FloatField(null=True, blank=True)
    avg_core_temp = models.FloatField(null=True, blank=True)
    max_core_temp = models.FloatField(null=True, blank=True)
    avg_skin_temp = models.FloatField(null=True, blank=True)
    max_skin_temp = models.FloatField(null=True, blank=True)
    # Garmin's Firstbeat-derived training load metrics, from a FIT session
    # message (no GPX/TCX equivalent). Device-computed, never user-settable.
    aerobic_training_effect = models.FloatField(null=True, blank=True)
    anaerobic_training_effect = models.FloatField(null=True, blank=True)
    training_effect_label = models.CharField(max_length=20, blank=True, default="")
    # Extended stats (Activity Analysis "Stats" tab). All computed once at ingest from the
    # record stream, same pattern as avg_power/max_hr above - null whenever the source data
    # needed for that one metric wasn't present (e.g. no altitude stream -> no elevation
    # fields; no power meter -> no max_power/calories). Deliberately NOT storing metrics
    # that are pure arithmetic over fields that already exist (avg W/kg, work in kJ, avg
    # speed, HR reserve % all derive from avg_power/moving_time/distance_km/avg_hr plus the
    # athlete's own profile - the frontend computes those directly).
    max_power = models.IntegerField(null=True, blank=True)
    avg_cadence = models.IntegerField(null=True, blank=True)
    max_cadence = models.IntegerField(null=True, blank=True)
    max_speed = models.FloatField(null=True, blank=True, help_text="km/h")
    total_descent = models.IntegerField(null=True, blank=True, help_text="metres")
    elevation_min = models.IntegerField(null=True, blank=True, help_text="metres")
    elevation_max = models.IntegerField(null=True, blank=True, help_text="metres")
    # Power-based estimate only (work_kJ / 0.24, a standard cycling efficiency
    # approximation) - deliberately not falling back to an HR-based estimate for
    # power-less activities, which would be a much rougher guess.
    calories = models.IntegerField(null=True, blank=True, help_text="kcal")
    # Edwards' TRIMP: sum over HR zones of (minutes in zone * zone number 1-5). Chosen
    # over Banister's original formula specifically because it needs no resting-HR
    # baseline - reuses the same HR zone set already computed for hrTSS.
    trimp = models.FloatField(null=True, blank=True)
    # % of power from the left leg, from the FIT record stream's left_right_balance field
    # (dual-sided/balance-capable power meters only - null for everything else, which is
    # most activities). Right % = 100 - this.
    avg_left_balance_pct = models.FloatField(null=True, blank=True)
    # Aerobic decoupling (Pw:HR) - see uploads/processing.py's compute_decoupling. Bike/run
    # with a power stream only; null/false everywhere else. The "steady window" is the
    # activity's own Record stream after the first 10 min (warm-up) - a recording pause of any
    # length already contributes no rows, so it never needs separate handling from a "real"
    # stop. decoupling_pct/ef_first/ef_second/decoupling_halves are null/empty whenever
    # decoupling_qualified is false (not computed, not just hidden) - decoupling_vi/
    # decoupling_if/steady_seconds/decoupling_hot/decoupling_avg_temp/decoupling_avg_core are
    # still populated where computable, since the UI shows them in the qualification checks
    # row even on a not-scored session.
    decoupling_pct = models.FloatField(null=True, blank=True)
    ef_first = models.FloatField(null=True, blank=True, help_text="W/bpm")
    ef_second = models.FloatField(null=True, blank=True, help_text="W/bpm")
    steady_seconds = models.IntegerField(null=True, blank=True)
    decoupling_qualified = models.BooleanField(default=False)
    # List of DECOUPLING_REASON_CHOICES codes - every failing check, not just the first (the
    # UI's checks row shows all four with their own pass/fail).
    decoupling_reasons = models.JSONField(default=list, blank=True)
    decoupling_vi = models.FloatField(null=True, blank=True, help_text="Variability index: NP / avg power")
    decoupling_if = models.FloatField(null=True, blank=True, help_text="Intensity factor: NP / threshold at date")
    decoupling_avg_temp = models.FloatField(null=True, blank=True, help_text="Air °C over the steady window")
    decoupling_avg_core = models.FloatField(null=True, blank=True, help_text="Core °C over the steady window")
    decoupling_avg_skin = models.FloatField(null=True, blank=True, help_text="Skin °C over the steady window")
    # Two independent severity tiers, both athlete-configurable (User.decoupling_warm_air_temp/
    # _skin_temp/decoupling_hot_air_temp/_skin_temp) and both read from air/skin temp only - NOT
    # core temp, which climbs toward 38 C on any long steady session from sustained effort alone
    # (even on a cool day), so it's informational only (decoupling_avg_core above) and no longer
    # gates either flag. decoupling_warm is an OR of its two thresholds (either one elevated is
    # enough to caveat the reading); decoupling_hot is an AND (both have to be elevated - a
    # stricter bar for the reading to be outright unusable). Whenever decoupling_hot is true,
    # decoupling_warm is also true under any sane threshold configuration (hot's air floor is
    # higher than warm's), so the UI shows at most one badge, picking the more severe.
    decoupling_warm = models.BooleanField(default=False)
    decoupling_hot = models.BooleanField(default=False)
    # % of steady-window samples with a non-null HR/power reading, 0-100 - the real numbers
    # behind the "HR 100% · power 100%" checks-row chip and the "HR coverage 72% (needs 90%)"
    # not-scored reason text. Always populated whenever there's a steady window to measure
    # (same "qualified or not" availability as decoupling_vi above).
    decoupling_hr_coverage_pct = models.FloatField(null=True, blank=True)
    decoupling_power_coverage_pct = models.FloatField(null=True, blank=True)
    # [{start_s, end_s, power, hr, ef}, ...] - one entry per half, for the Stats card's halves
    # table. Stored rather than recomputed on read, same "compute once at ingest" convention as
    # avg_power/duration curves above.
    decoupling_halves = models.JSONField(default=list, blank=True)
    workout = models.ForeignKey(Workout, null=True, blank=True, on_delete=models.SET_NULL, related_name="activities")
    bike = models.ForeignKey(Bike, null=True, blank=True, on_delete=models.SET_NULL, related_name="activities")
    shoe = models.ForeignKey(Shoe, null=True, blank=True, on_delete=models.SET_NULL, related_name="activities")
    # Set on the per-leg children of a multisport activity; null everywhere else.
    parent_activity = models.ForeignKey(
        "self", null=True, blank=True, on_delete=models.CASCADE, related_name="child_activities"
    )
    # Set on a duplicate recording of another activity (the "primary"); null everywhere
    # else. Only the primary counts toward training load. SET_NULL on delete: a duplicate
    # is a full activity in its own right, not a dependent like a multisport leg.
    primary_activity = models.ForeignKey(
        "self", null=True, blank=True, on_delete=models.SET_NULL, related_name="duplicate_activities"
    )
    tags: "models.ManyToManyField[Tag, ActivityTag]" = models.ManyToManyField(
        "Tag", through="ActivityTag", related_name="activities", blank=True
    )

    class Meta:
        ordering = ["-start_date"]

    def __str__(self) -> str:
        return self.name

    def matches_running_power_preference(self, athlete: User) -> bool:
        """Whether this activity's own resolved running-power source still matches the
        athlete's *current* running_power_source preference - False only when both are set
        and they disagree. An unset power_source (pre-feature activity, a non-FIT format, or
        a non-run sport) always matches: there's nothing to compare against, so its power is
        trusted exactly as it was before this preference existed. Checked by every consumer
        of running power (best efforts, TSS/derived stats, critical_run_power threshold
        history) before trusting avg_power/Record.power for a run - see
        uploads/processing.py::_select_running_power_source for where power_source itself
        gets set."""
        return not self.power_source or self.power_source == athlete.running_power_source

    def matches_bike_power_preference(self, athlete: User) -> bool:
        """Same shape as matches_running_power_preference, plus: the athlete's preference itself
        can be "any" (no preference), which always matches regardless of this activity's own
        bike_power_source."""
        return (
            athlete.bike_power_source == "any"
            or not self.bike_power_source
            or self.bike_power_source == athlete.bike_power_source
        )


class Lap(models.Model):
    """Not fetched by its own id, so it uses a plain BigAutoField per the
    core.models.PrefixedIDModel convention."""

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="laps")
    index = models.IntegerField()
    duration = models.IntegerField()
    distance_km = models.FloatField()
    avg_hr = models.IntegerField(null=True, blank=True)
    avg_power = models.IntegerField(null=True, blank=True)
    # Set only for a lap derived from a matched Workout's own step boundaries
    # (activities/lap_derivation.py) - null for a device-FIT-parsed lap (lap_source="original"),
    # an unmatched activity, or a trailing/leading segment outside the workout's own steps.
    workout_step = models.ForeignKey(
        "workouts.WorkoutStep", null=True, blank=True, on_delete=models.SET_NULL, related_name="laps"
    )
    # 1-based - which repetition of workout_step's containing repeat group this lap came from
    # (workout_step itself is one DB row regardless of how many times it repeats, so multiple
    # laps legitimately share the same workout_step_id). Null when workout_step isn't a
    # repeated step, or when workout_step itself is null.
    repeat_index = models.IntegerField(null=True, blank=True)

    class Meta:
        ordering = ["index"]

    def __str__(self) -> str:
        return f"{self.activity_id} lap {self.index}"


class Tag(PrefixedIDModel):
    id_prefix = "tag"

    ORIGIN_CHOICES = [
        ("manual", "Manual"),
        ("auto", "Auto"),
    ]

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="tags")
    name = models.CharField(max_length=100)
    origin = models.CharField(max_length=10, choices=ORIGIN_CHOICES, default="manual")
    color = models.CharField(max_length=20, blank=True, default="")

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["athlete", "name"], name="unique_athlete_tag_name"),
        ]

    def __str__(self) -> str:
        return self.name


class ActivityTag(models.Model):
    """Join table — no id in its own right, never fetched independently."""

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="activity_tags")
    tag = models.ForeignKey(Tag, on_delete=models.CASCADE, related_name="activity_tags")

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["activity", "tag"], name="unique_activity_tag"),
        ]

    def __str__(self) -> str:
        return f"{self.activity_id} -> {self.tag_id}"


class Record(models.Model):
    """The 1 Hz time-series stream. A natively RANGE-partitioned table on `ts`
    (see the 0003 migration's raw-SQL table recreation, plus activities/tasks.py's
    ensure_record_partitions for rolling the partition range forward over time) —
    `t` (seconds offset from activity.start_date) is kept alongside `ts` purely for
    convenient ordering/indexing without re-deriving it from the activity on every
    read.

    Never fetched by its own id, so the surrogate BigAutoField's uniqueness is
    dropped in favor of (activity, ts) once the partitioning migration runs —
    Postgres requires every unique/PK constraint on a partitioned table to include
    the partitioning column, and id-alone can't satisfy that.
    """

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="records")
    t = models.IntegerField()
    ts = models.DateTimeField()
    power = models.IntegerField(null=True, blank=True)
    heartrate = models.IntegerField(null=True, blank=True)
    cadence = models.IntegerField(null=True, blank=True)
    altitude = models.FloatField(null=True, blank=True)
    lat = models.FloatField(null=True, blank=True)
    lng = models.FloatField(null=True, blank=True)
    speed = models.FloatField(null=True, blank=True)
    distance_km = models.FloatField(null=True, blank=True)
    air_temp = models.FloatField(null=True, blank=True)
    humidity = models.IntegerField(null=True, blank=True)
    skin_temp = models.FloatField(null=True, blank=True)
    core_temp = models.FloatField(null=True, blank=True)
    heat_strain = models.FloatField(null=True, blank=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["activity", "ts"], name="unique_activity_record_ts"),
        ]
        ordering = ["t"]

    def __str__(self) -> str:
        return f"{self.activity_id} @ {self.t}s"


class DurationCurve(models.Model):
    """Not fetched by its own id, so it uses a plain BigAutoField per the
    core.models.PrefixedIDModel convention."""

    METRIC_CHOICES = [
        ("power", "Power"),
        ("heartrate", "Heart rate"),
    ]

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="duration_curves")
    metric = models.CharField(max_length=10, choices=METRIC_CHOICES)
    extends_to = models.IntegerField()
    points = models.JSONField(default=dict)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["activity", "metric"], name="unique_activity_curve_metric"),
        ]

    def __str__(self) -> str:
        return f"{self.activity_id} {self.metric} curve"


class BestEffort(models.Model):
    """One row per (athlete, kind, window, activity) — the top-N personal records
    per window, ranked by value. Not fetched by its own id, so it uses a plain
    BigAutoField per the core.models.PrefixedIDModel convention.
    """

    KIND_CHOICES = [
        ("cycling_hr", "Cycling heart rate"),
        ("cycling_power", "Cycling power"),
        ("running_hr", "Running heart rate"),
        ("running_pace", "Running pace"),
        ("running_power", "Running power"),
    ]

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="best_efforts")
    kind = models.CharField(max_length=20, choices=KIND_CHOICES)
    window = models.CharField(max_length=20)
    value = models.FloatField()
    unit = models.CharField(max_length=20)
    date = models.DateField()
    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="best_efforts")

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["athlete", "kind", "window", "activity"], name="unique_athlete_kind_window_activity"
            ),
        ]
        ordering = ["kind", "window", "-value"]

    def __str__(self) -> str:
        return f"{self.athlete_id} {self.kind} {self.window}"


class ActivityDurability(models.Model):
    """One row per (activity, threshold, window_s) where a qualifying effort exists - best
    mean-max power for `window_s` that *starts* after `threshold` of accumulated fatigue
    (cycling: kJ of work; running: minutes of moving time) has been reached. See
    uploads/processing.py's compute_durability_rows. The `threshold = 0` row is just the
    activity's normal mean-max best (no fatigue gate), which keeps a "fresh" baseline in the
    same query as every fatigued one. Not fetched by its own id, so it uses a plain
    BigAutoField per the core.models.PrefixedIDModel convention (same as BestEffort above).

    Written idempotently (delete + reinsert per activity) by whatever computed it, not
    incrementally updated - see compute_durability_rows's own docstring.
    """

    BASIS_CHOICES = [
        ("kj", "Kilojoules"),
        ("minutes", "Minutes"),
    ]

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="durability_rows")
    # Denormalised from activity.athlete/activity.start_date - this table is queried directly
    # by (athlete, sport, date range) for the Best Efforts Durability view, which would
    # otherwise need a join through Activity for every row.
    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="durability_rows")
    sport = models.CharField(max_length=10, choices=[c for c in Activity.SPORT_CHOICES if c[0] in ("bike", "run")])
    basis = models.CharField(max_length=10, choices=BASIS_CHOICES)
    # 0 (fresh), 1000/2000/3000 (kJ, basis="kj") or 0/60/90 (minutes, basis="minutes").
    threshold = models.IntegerField()
    window_s = models.IntegerField()
    power = models.IntegerField(help_text="W")
    start_offset_s = models.IntegerField()
    activity_date = models.DateField()

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["activity", "threshold", "window_s"], name="unique_activity_durability"),
        ]
        indexes = [
            models.Index(fields=["athlete", "sport", "activity_date"]),
        ]
        verbose_name_plural = "activity durability rows"

    def __str__(self) -> str:
        return f"{self.activity_id} after {self.threshold}{self.basis} {self.window_s}s={self.power}W"


class ActivityComment(PrefixedIDModel):
    """A comment on an activity, from the athlete or anyone with read access to their data
    (a coach or viewer share) — a lightweight social feature, not a data mutation, so it's
    gated by user_may_read rather than user_may_write (see activities/views.py). Role
    (athlete vs coach) is derived at read time from the relationship to the activity's
    owner, not stored - it can change (a share could be revoked) and storing it would let
    it drift from the truth.
    """

    id_prefix = "cmt"

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="comments")
    author = models.ForeignKey(User, on_delete=models.CASCADE, related_name="activity_comments")
    # Null for a top-level comment. Single-level threading only - a reply's own parent is
    # always null (enforced in the view/MCP tool, not here), so this never points at another
    # reply.
    parent = models.ForeignKey("self", null=True, blank=True, on_delete=models.CASCADE, related_name="replies")
    text = models.TextField()
    created = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["created"]

    def __str__(self) -> str:
        return f"{self.author_id} on {self.activity_id}"


class ActivityElevation(models.Model):
    """An activity's elevation resampled to 10 m from its records (see
    courses.sections.build_elevation_series), for calculating sections on read. Its own table so
    activity lists never load the series; no row yet means it hasn't been built, which
    activities.elevation does on the first sections read."""

    activity = models.OneToOneField(Activity, on_delete=models.CASCADE, primary_key=True, related_name="elevation")
    elevation_quality = models.CharField(max_length=10)
    elevation_series = models.JSONField(default=list)

    def __str__(self) -> str:
        return f"{self.activity_id} elevation ({self.elevation_quality})"


class ActivityIntensityHistogram(models.Model):
    """Per-activity power/heart-rate histograms for the threshold-decay load term (issue #485,
    docs/plans/threshold-decay.md section 2.3) - seconds spent at each whole watt of 30s-smoothed
    power, and at each whole bpm of heart rate. Its own table, same reasoning as
    ActivityElevation: only the decay load calculation needs these, so they shouldn't bloat every
    Activity row/query. No row yet means it hasn't been built, which
    activities.intensity_histogram.ensure_histogram does on first read."""

    activity = models.OneToOneField(
        Activity, on_delete=models.CASCADE, primary_key=True, related_name="intensity_histogram"
    )
    power_30s = models.JSONField(null=True, blank=True)
    heartrate = models.JSONField(null=True, blank=True)

    def __str__(self) -> str:
        return f"{self.activity_id} intensity histogram"


class DuplicateResolutionBatch(PrefixedIDModel):
    """Issue #498's MCP resolve_duplicates_batch(..., dry_run=False) - one call that actually
    applied an action to many overlap pairs at once, recorded so undo_batch can reverse the
    whole thing later. Never created for a dry run (nothing happened, nothing to undo). Always
    scoped to one athlete, same as every other duplicate-resolution path - a batch never spans
    athletes."""

    id_prefix = "drb"

    ACTION_CHOICES = [
        ("mark", "Mark"),
        ("merge", "Merge"),
    ]

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="duplicate_resolution_batches")
    action = models.CharField(max_length=10, choices=ACTION_CHOICES)
    # The acting principal - the athlete itself for a self-triggered batch, a different User
    # for a coach acting on their behalf. Informational only; nothing currently reads it back.
    created_by = models.ForeignKey(User, null=True, blank=True, on_delete=models.SET_NULL, related_name="+")
    created_at = models.DateTimeField(auto_now_add=True)
    undone_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self) -> str:
        return f"{self.athlete_id} batch {self.id} ({self.action})"


class DuplicateResolutionEntry(models.Model):
    """One pair resolved by a DuplicateResolutionBatch. `outcome` mirrors
    OverlapResolution's own vocabulary. `primary_activity` is set only when outcome="marked",
    `merge` only when outcome="merged" - undo_batch's own check that the activity's current
    primary (or the merge's own active state) still matches before reverting, so a pair
    re-resolved (or hand-edited) since this batch applied is never silently clobbered."""

    OUTCOME_CHOICES = [
        ("marked", "Marked"),
        ("flagged", "Flagged"),
        ("merged", "Merged"),
        ("merge_skipped_low_score", "Merge skipped (low score)"),
    ]

    batch = models.ForeignKey(DuplicateResolutionBatch, on_delete=models.CASCADE, related_name="entries")
    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="+")
    other_activity = models.ForeignKey(Activity, null=True, blank=True, on_delete=models.SET_NULL, related_name="+")
    outcome = models.CharField(max_length=25, choices=OUTCOME_CHOICES)
    primary_activity = models.ForeignKey(Activity, null=True, blank=True, on_delete=models.SET_NULL, related_name="+")
    # String reference, not Merge directly - Merge is defined later in this file.
    merge = models.ForeignKey("Merge", null=True, blank=True, on_delete=models.SET_NULL, related_name="+")

    def __str__(self) -> str:
        return f"{self.batch_id}: {self.activity_id} {self.outcome}"


class Merge(PrefixedIDModel):
    """Issue #501: records one applied merge, so it's auditable and - when the target's upload
    file is still stored - reversible via merge_service.unmerge. Never created for a preview;
    nothing happened, nothing to record. ``source_activity`` is nullable/SET_NULL rather than
    CASCADE: a merge that hid its source (merge_laps aside) leaves the source as a real,
    undeleted activity (same "keep the hidden copy, don't delete it" reversibility #498's own
    duplicate rows get), but if an athlete deletes it outright anyway, this audit row survives
    as a record of what happened even though undo is no longer possible."""

    id_prefix = "mrg"

    NULL_POLICY_CHOICES = [
        ("overwrite", "Overwrite"),
        ("keep_original", "Keep original"),
    ]

    target_activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="merges_into")
    source_activity = models.ForeignKey(
        Activity, null=True, blank=True, on_delete=models.SET_NULL, related_name="merges_from"
    )
    fields = models.JSONField()
    null_policy = models.CharField(max_length=20, choices=NULL_POLICY_CHOICES)
    offset_seconds = models.IntegerField()
    alignment_score = models.FloatField(null=True, blank=True)
    # {field_name: {"copied": n, "kept_original": n, "nulled": n}}, straight from
    # FieldRewriteCounts/merge_apply.apply_merge_fields's own return value.
    field_counts = models.JSONField()
    # {field_name: value} - the target's own value for each requested *activity-level* field,
    # captured immediately before apply_merge_fields overwrote it. unmerge_service.undo_merge
    # restores from this rather than re-deriving it (e.g. regenerating `name` from sport+date),
    # so a legitimate edit made between the merge and its later undo is never silently
    # discarded. Stream fields aren't snapshotted here - undo restores those by re-parsing the
    # target's own stored upload file instead, which would make snapshotting them here pure
    # redundant storage.
    previous_activity_values = models.JSONField(default=dict, blank=True)
    source_hidden = models.BooleanField(default=False)
    created_at = models.DateTimeField(auto_now_add=True)
    undone_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self) -> str:
        return f"{self.id}: {self.source_activity_id} -> {self.target_activity_id}"


class MergeRule(PrefixedIDModel):
    """Issue #501 (phase 4): a saved per-athlete, per-sport rule for automatic merging - which
    side plays target/source, which fields to copy, and the null policy. Consulted by
    overlap_detection.resolve_pair/preview_resolution when resolving an on_overlap="merge" pair;
    no row for a sport means merge_rule_service's built-in default applies instead (or, for a
    sport with no built-in default, that automatic merging simply doesn't apply - the pair is
    flagged).

    target_pattern/source_pattern are "device:<prefix>" strings (e.g. "device:zwift"), matched
    case-insensitively as a prefix against Activity.device - see merge_rule_service.matches().
    Only device: patterns are supported today; the prefix exists so other pattern kinds could be
    added later without a schema change. Mirrors backend_java's MergeRule entity - keep the two
    in sync when changing either."""

    id_prefix = "mrl"

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="merge_rules")
    sport = models.CharField(max_length=10, choices=Activity.SPORT_CHOICES)
    target_pattern = models.CharField(max_length=100)
    source_pattern = models.CharField(max_length=100)
    fields = models.JSONField()
    null_policy = models.CharField(max_length=20, choices=Merge.NULL_POLICY_CHOICES)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [models.UniqueConstraint(fields=["athlete", "sport"], name="unique_merge_rule_per_athlete_sport")]
        ordering = ["sport"]

    def __str__(self) -> str:
        return f"{self.athlete_id}: {self.sport}"


class ActivitySource(PrefixedIDModel):
    """Issue #503 (phases 1-3): a non-primary recorded stream kept alongside an activity - e.g.
    the Garmin/pedal power copy of a ride whose Zwift/Kickr copy is primary. The primary stream
    itself is never represented here - it stays in Record's own columns, so every existing
    calculator/curve/best-effort/threshold path is unchanged. One row per stream (not one row per
    second) to keep storage small - see the issue's own sizing rationale. Mirrors backend_java's
    ActivitySource entity - keep the two in sync.

    origin="attached" is one added via POST .../merge's attach copy_mode. origin="file" covers
    two cases, both from the activity's own single upload rather than a second activity: a
    former primary snapshotted lazily by activity_source_service.set_primary_source the first
    time it's swapped away (phase 1), and - for a run whose FIT file carries both a native and a
    Stryd power reading - the non-preferred candidate kept at parse time (phase 2, see
    activity_source_service.create_parse_time_running_power_source), so a later preference
    change can re-designate it instead of only filtering activities out."""

    id_prefix = "src"

    ORIGIN_CHOICES = [
        ("file", "File"),
        ("attached", "Attached"),
    ]

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="sources")
    label = models.CharField(max_length=200, blank=True, default="")
    device = models.CharField(max_length=100, blank=True, default="")
    # Which field this source provides a stream for - "power", "heartrate", or "cadence" as of
    # phase 3; stored as a plain string (not a fields-whitelist choice) for the same reason
    # merge's own `fields` list is, so a later phase needs no schema change.
    sensor_kind = models.CharField(max_length=20)
    origin = models.CharField(max_length=10, choices=ORIGIN_CHOICES)
    # Issue #503 (phase 3, generalized from phase 2's running_power_kind): a free-form tag on
    # what kind of sensor this stream came from, meaning depends on sensor_kind - "native"/
    # "stryd" for a running power source (set automatically at parse time), "strap"/"wrist" for
    # heartrate, "pedals"/"trainer"/"watch" for cadence (both manually supplied by the athlete at
    # attach time - see activity_source_service.attach_stream - there's no automatic
    # classification signal for either). Blank for a bike power source (which distinguishes
    # pedals/trainer by `device` via activity_source_service.classify instead - a run's two
    # candidates come from the *same* device, so there's nothing to classify by device string
    # there) and for any source never tagged. No `choices=` - the valid value set depends on the
    # owning row's sensor_kind, which a single CharField can't express cleanly.
    source_kind = models.CharField(max_length=10, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["created_at"]

    def __str__(self) -> str:
        return f"{self.id}: {self.activity_id} {self.sensor_kind}"


class ActivityStream(models.Model):
    """One stored stream, belonging to an ActivitySource - see that model's own docstring. Not
    fetched by its own id, so it uses a plain BigAutoField per the core.models.PrefixedIDModel
    convention. primary_of is always False for a row here today - a primary stream lives in
    Record instead, never here; the column exists so a future phase can represent "this is the
    primary" uniformly across Record/ActivityStream without a schema change, per the issue's own
    design."""

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="streams")
    field = models.CharField(max_length=20)
    source = models.ForeignKey(ActivitySource, on_delete=models.CASCADE, related_name="streams")
    t0 = models.IntegerField()
    # Element is nullable - a second with no source data at that offset - same "missing sample,
    # not a 0" convention Record's own nullable stream columns use.
    values = ArrayField(models.SmallIntegerField(null=True))
    primary_of = models.BooleanField(default=False)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["activity", "field", "source"], name="unique_activity_stream_source"),
        ]

    def __str__(self) -> str:
        return f"{self.activity_id} {self.field} <- {self.source_id}"
