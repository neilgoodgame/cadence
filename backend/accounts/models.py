from django.contrib.auth.base_user import AbstractBaseUser, BaseUserManager
from django.contrib.auth.models import PermissionsMixin
from django.db import models

from core.models import PrefixedIDModel


class UserManager(BaseUserManager):
    def create_user(self, email, password=None, **extra_fields):
        if not email:
            raise ValueError("Users must have an email address")
        email = self.normalize_email(email)
        user = self.model(email=email, **extra_fields)
        user.set_password(password)
        user.save(using=self._db)
        return user

    def create_superuser(self, email, password=None, **extra_fields):
        extra_fields.setdefault("is_staff", True)
        extra_fields.setdefault("is_superuser", True)
        extra_fields.setdefault("is_active", True)
        extra_fields.setdefault("is_coach", True)
        return self.create_user(email, password, **extra_fields)


class User(PrefixedIDModel, AbstractBaseUser, PermissionsMixin):
    id_prefix = "usr"

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
    is_coach = models.BooleanField(default=False)

    is_active = models.BooleanField(default=True)
    is_staff = models.BooleanField(default=False)
    date_joined = models.DateTimeField(auto_now_add=True)

    objects = UserManager()

    USERNAME_FIELD = "email"
    REQUIRED_FIELDS = ["name"]

    def __str__(self):
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

    def __str__(self):
        return f"{self.name} ({self.prefix}…)"


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

    def __str__(self):
        return f"{self.owner_id} -> {self.grantee_id} ({self.role})"

    def save(self, *args, **kwargs):
        if not self.id:
            from core.models import generate_id

            self.id = generate_id("rel")
        super().save(*args, **kwargs)
