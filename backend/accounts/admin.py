from django.contrib import admin
from django.contrib.auth.admin import UserAdmin as DjangoUserAdmin

from .models import PersonalAccessToken, User, UserRelationship


@admin.register(User)
class UserAdmin(DjangoUserAdmin):
    ordering = ("email",)
    list_display = ("email", "name", "is_coach", "is_staff", "is_active")
    search_fields = ("email", "name")
    fieldsets = (
        (None, {"fields": ("email", "password")}),
        (
            "Profile",
            {
                "fields": (
                    "name",
                    "age",
                    "weight_kg",
                    "ftp",
                    "critical_run_power",
                    "threshold_pace",
                    "lthr",
                    "max_hr",
                    "is_coach",
                )
            },
        ),
        ("Permissions", {"fields": ("is_active", "is_staff", "is_superuser", "groups", "user_permissions")}),
    )
    add_fieldsets = ((None, {"fields": ("email", "name", "password1", "password2")}),)


@admin.register(PersonalAccessToken)
class PersonalAccessTokenAdmin(admin.ModelAdmin):
    list_display = ("name", "prefix", "user", "created", "expires_at", "last_used")
    search_fields = ("name", "prefix", "user__email")


@admin.register(UserRelationship)
class UserRelationshipAdmin(admin.ModelAdmin):
    list_display = ("owner", "grantee", "role", "status", "created")
    list_filter = ("role", "status")
