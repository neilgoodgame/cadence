from typing import Any

from django.contrib.auth.base_user import AbstractBaseUser, BaseUserManager
from django.contrib.auth.models import PermissionsMixin
from django.db import models

from core.models import PrefixedIDModel


class UserManager(BaseUserManager["User"]):
    def create_user(self, email: str, password: str | None = None, **extra_fields: Any) -> "User":
        if not email:
            raise ValueError("Users must have an email address")
        email = self.normalize_email(email)
        user = self.model(email=email, **extra_fields)
        user.set_password(password)
        user.save(using=self._db)
        return user

    def create_superuser(self, email: str, password: str | None = None, **extra_fields: Any) -> "User":
        extra_fields.setdefault("is_staff", True)
        extra_fields.setdefault("is_superuser", True)
        extra_fields.setdefault("is_active", True)
        extra_fields.setdefault("is_coach", True)
        return self.create_user(email, password, **extra_fields)


class User(PrefixedIDModel, AbstractBaseUser, PermissionsMixin):
    id_prefix = "usr"

    # How threshold_history.py's _implied_value derives an implied FTP from a bike activity.
    # twenty_min_test is the conventional estimate (best 20-min power * 0.95) - practical since it
    # only needs a 20-minute qualifying effort, but assumes a fixed ~5% fade out to 60 minutes
    # that doesn't hold for every athlete's actual power-duration curve. sixty_min_direct uses the
    # best 60-minute power directly (FTP's own textbook definition, no multiplier), at the cost of
    # only producing a candidate from activities long enough to have a real 60-minute window.
    FTP_CALCULATION_METHOD_CHOICES = [
        ("twenty_min_test", "20-minute test (best 20-min power x 0.95)"),
        ("sixty_min_direct", "60-minute direct (best 60-min power)"),
    ]

    email = models.EmailField(unique=True)
    name = models.CharField(max_length=150)
    handle = models.CharField(max_length=50, unique=True, null=True, blank=True)

    age = models.PositiveSmallIntegerField(null=True, blank=True)
    weight_kg = models.FloatField(null=True, blank=True)
    ftp = models.PositiveIntegerField(null=True, blank=True)
    critical_run_power = models.PositiveIntegerField(null=True, blank=True)
    threshold_pace = models.CharField(max_length=10, blank=True, default="")
    lthr = models.PositiveIntegerField(null=True, blank=True)
    max_hr = models.PositiveIntegerField(null=True, blank=True)
    # Optional - only used for the Karvonen heart-rate-reserve % shown on Activity
    # Analysis's Stats tab. Every other threshold on this model is required for its own
    # feature to work at all (zones, TSS); this one isn't, so it stays null until set.
    resting_hr = models.PositiveIntegerField(null=True, blank=True)
    is_coach = models.BooleanField(default=False)
    # App-level admin flag (the in-app Admin screen) - distinct from is_staff/is_superuser,
    # which gate Django's own built-in /admin/ site and are unrelated to this feature.
    is_admin = models.BooleanField(default=False)
    best_effort_top_n = models.PositiveSmallIntegerField(default=10)
    # Rolling-window threshold determination (see athletes/threshold_history.py): ftp/
    # critical_run_power/threshold_pace above are each the best qualifying effort within the
    # trailing threshold_window_days - not a one-way ratchet, a value drops automatically once
    # its source activity ages out of the window. 112 days = 16 weeks, matching this codebase's
    # existing "16w" best-efforts period bucket (athletes/views.py's BEST_EFFORT_PERIOD_DAYS).
    threshold_window_days = models.PositiveSmallIntegerField(default=112)
    # A candidate activity whose implied value deviates from the athlete's then-current value by
    # more than this percentage is treated as an outlier (e.g. corrupt power-meter data) and
    # excluded from consideration - see threshold_history.py's _within_sanity_band.
    threshold_sanity_pct = models.PositiveSmallIntegerField(default=30)
    ftp_calculation_method = models.CharField(
        max_length=20, choices=FTP_CALCULATION_METHOD_CHOICES, default="twenty_min_test"
    )
    # Which running-power reading to trust when a FIT file carries both: a watch's own
    # accelerometer-based estimate (e.g. Garmin Running Power) and a third-party footpod's
    # (e.g. Stryd). The two commonly disagree substantially - native running-power algorithms
    # tend to read meaningfully higher than Stryd for the same effort - so this is a deliberate
    # choice, not a fallback preference: the non-selected source is completely ignored at parse
    # time (uploads/processing.py::_select_running_power_source), not used when the selected one
    # is momentarily missing. Every consumer of running power downstream (best efforts, TSS/
    # derived stats, critical_run_power's rolling-window threshold) re-checks a run activity's
    # own Activity.power_source against this *current* preference before trusting its power data
    # - not just at ingest time - so switching this later correctly stops an already-imported
    # activity's stale-preference power from counting, without needing to re-upload it.
    RUNNING_POWER_SOURCE_CHOICES = [
        ("stryd", "Stryd"),
        ("native", "Native (e.g. Garmin Running Power)"),
    ]
    running_power_source = models.CharField(max_length=10, choices=RUNNING_POWER_SOURCE_CHOICES, default="stryd")
    # Auto-match naming preferences (uploads/processing.py's attempt_workout_match) - both
    # default off so existing device-derived activity names are untouched unless opted in.
    # append_match_date_to_name only has an effect when rename_matched_activities is also on.
    rename_matched_activities = models.BooleanField(default=False)
    append_match_date_to_name = models.BooleanField(default=False)
    # Independent of the naming preferences above - copies the matched Workout's tags
    # (workouts/models.py's Workout.tags, a plain list of names) onto the activity.
    copy_matched_workout_tags = models.BooleanField(default=False)

    # Gates high-trust actions (full-account export/import - see dataexport/views.py's
    # _require_email_verified) behind a confirmed email address. Every account here goes
    # through the password-signup flow (RegisterView rejects social signup for now), so this
    # always starts false and is only ever flipped by EmailVerificationToken.verify - unlike
    # backend_java's User.emailVerified, which social signups get pre-set to true.
    email_verified = models.BooleanField(default=False)

    is_active = models.BooleanField(default=True)
    is_staff = models.BooleanField(default=False)
    date_joined = models.DateTimeField(auto_now_add=True)

    # Synthetic account with no real inbox and no usable password, created via the "virtual
    # coach" flow (accounts.delegation.create_virtual_coach) for an MCP client to authenticate
    # as. Never logs into the web app - can only authenticate via the delegated personal access
    # token minted alongside it. Restricted to exactly one coach relationship (see
    # ShareListCreateView.post's guard); a real coach is not restricted this way.
    is_virtual = models.BooleanField(default=False)

    objects = UserManager()

    USERNAME_FIELD = "email"
    REQUIRED_FIELDS = ["name"]

    def __str__(self) -> str:
        return self.email


class PersonalAccessToken(PrefixedIDModel):
    id_prefix = "tok"

    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="access_tokens")
    name = models.CharField(max_length=150)
    # First chars of the secret (e.g. "cad_pat_7Qda"), shown in listings for recognition.
    prefix = models.CharField(max_length=20, db_index=True)
    hashed_secret = models.CharField(max_length=64)
    scopes = models.JSONField(default=list)
    created = models.DateTimeField(auto_now_add=True)
    expires_at = models.DateField(null=True, blank=True)
    last_used = models.DateField(null=True, blank=True)
    # Whose data this token authorizes, when it differs from `user` - a coach's (real or
    # virtual) token scoped to a specific athlete via an active UserRelationship at creation
    # time (see accounts.delegation.require_active_coach_access). Null for an ordinary
    # self-scoped token. `delegated_athlete_id` (the plain column) is what auth-path code reads
    # to avoid an extra join on every request; `delegated_athlete` is the full relation.
    delegated_athlete = models.ForeignKey(
        User, on_delete=models.CASCADE, null=True, blank=True, related_name="delegated_tokens"
    )

    def __str__(self) -> str:
        return f"{self.name} ({self.prefix}…)"


class EmailVerificationToken(PrefixedIDModel):
    """A one-time-use token proving control of the email address on a password-based account -
    mirrors backend_java's EmailVerificationToken exactly (same table shape, same hashed-secret
    scheme as PersonalAccessToken above: only the raw value mailed to the athlete can complete
    verification, nothing recoverable from the row itself). See
    accounts/email_verification.py.
    """

    id_prefix = "evt"

    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="email_verification_tokens")
    hashed_secret = models.CharField(max_length=64, unique=True)
    created = models.DateTimeField(auto_now_add=True)
    expires_at = models.DateTimeField()
    used_at = models.DateTimeField(null=True, blank=True)

    def is_usable(self, now) -> bool:
        return self.used_at is None and now < self.expires_at

    def __str__(self) -> str:
        return f"verification token for {self.user_id}"


class UserRelationship(models.Model):
    ROLE_VIEWER = "viewer"
    ROLE_COACH = "coach"
    ROLE_CHOICES = [(ROLE_VIEWER, "Viewer"), (ROLE_COACH, "Coach")]

    STATUS_PENDING = "pending"
    STATUS_ACTIVE = "active"
    STATUS_CHOICES = [(STATUS_PENDING, "Pending"), (STATUS_ACTIVE, "Active")]

    id = models.CharField(primary_key=True, max_length=40, editable=False)
    owner = models.ForeignKey(User, on_delete=models.CASCADE, related_name="shares_granted")
    grantee = models.ForeignKey(User, on_delete=models.CASCADE, related_name="shares_received")
    role = models.CharField(max_length=10, choices=ROLE_CHOICES)
    status = models.CharField(max_length=10, choices=STATUS_CHOICES, default=STATUS_PENDING)
    created = models.DateTimeField(auto_now_add=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["owner", "grantee"], name="unique_owner_grantee"),
        ]

    def __str__(self) -> str:
        return f"{self.owner_id} -> {self.grantee_id} ({self.role})"

    def save(self, *args: Any, **kwargs: Any) -> None:
        if not self.id:
            from core.models import generate_id

            self.id = generate_id("rel")
        super().save(*args, **kwargs)
