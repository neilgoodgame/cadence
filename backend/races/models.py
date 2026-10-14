from django.db import models

from accounts.models import User
from activities.models import Activity
from core.models import PrefixedIDModel

SPORT_CHOICES = [
    ("bike", "Bike"),
    ("run", "Run"),
    ("swim", "Swim"),
    ("multisport", "Multisport"),
]

PRIORITY_CHOICES = [
    ("A", "A - goal race"),
    ("B", "B - tune-up"),
    ("C", "C - tune-up"),
]


class Race(PrefixedIDModel):
    id_prefix = "race"

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="races")
    name = models.CharField(max_length=200)
    date = models.DateField()
    sport = models.CharField(max_length=10, choices=SPORT_CHOICES, blank=True, default="")
    priority = models.CharField(max_length=1, choices=PRIORITY_CHOICES, default="A")
    course = models.ForeignKey("courses.Course", null=True, blank=True, on_delete=models.SET_NULL, related_name="races")
    distance_km = models.FloatField(null=True, blank=True)
    goal_time = models.DurationField(null=True, blank=True)
    result_time = models.DurationField(null=True, blank=True)
    activity = models.OneToOneField(
        Activity,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="race_result",
    )
    url = models.URLField(null=True, blank=True)  # noqa: DJ001 -- API contract uses null, not ""
    results_url = models.URLField(null=True, blank=True)  # noqa: DJ001 -- link to athlete's result page
    notes = models.CharField(max_length=500, blank=True, default="")
    # Overrides the athlete-wide "Key session" tag lookup for this race's key_sessions "tagged"
    # category (races.blocks.key_sessions) - lets different goal races use different tags instead
    # of sharing the one global tag name. Null falls back to that default.
    key_session_tag = models.ForeignKey(
        "activities.Tag", null=True, blank=True, on_delete=models.SET_NULL, related_name="key_session_races"
    )

    class Meta:
        ordering = ["date"]

    def __str__(self) -> str:
        return f"{self.name} ({self.date})"


class RaceComment(PrefixedIDModel):
    """Coach/athlete comments on a race plan - mirrors activities.models.ActivityComment's
    shape and permission model (gated by read access, not write - a lightweight social
    feature). Unlike ActivityComment, `role` is stored rather than computed, because a
    comment is meant to keep showing the role the author had *at the time they posted* even
    if a coaching relationship is later revoked.
    """

    id_prefix = "rcm"

    ROLE_CHOICES = [
        ("athlete", "Athlete"),
        ("coach", "Coach"),
    ]

    race = models.ForeignKey(Race, on_delete=models.CASCADE, related_name="comments")
    author = models.ForeignKey(User, on_delete=models.CASCADE, related_name="race_comments")
    role = models.CharField(max_length=10, choices=ROLE_CHOICES)
    text = models.CharField(max_length=2000)
    week = models.PositiveSmallIntegerField(null=True, blank=True)
    parent = models.ForeignKey("self", null=True, blank=True, on_delete=models.CASCADE, related_name="replies")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["created_at"]

    def __str__(self) -> str:
        return f"{self.race_id} comment by {self.author_id}"
