from django.db import models

from accounts.models import User
from core.models import PrefixedIDModel


class CatalogAuditLogEntry(PrefixedIDModel):
    """One row per shoe-catalog add/remove made from the Admin screen. Scoped to catalog
    changes only - user/admin-flag toggles and coach-grant revokes don't write here.
    """

    id_prefix = "cal"

    ACTION_ADDED = "added"
    ACTION_REMOVED = "removed"
    ACTION_CHOICES = [(ACTION_ADDED, "Added"), (ACTION_REMOVED, "Removed")]

    description = models.CharField(max_length=300)
    action = models.CharField(max_length=10, choices=ACTION_CHOICES)
    # SET_NULL, not CASCADE, so the log entry outlives whichever admin made the change -
    # matching ShoeModel.created_by's precedent for "who did this" fields.
    by = models.ForeignKey(User, null=True, blank=True, on_delete=models.SET_NULL, related_name="catalog_audit_entries")
    created = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created"]

    def __str__(self) -> str:
        return f"{self.action}: {self.description}"
