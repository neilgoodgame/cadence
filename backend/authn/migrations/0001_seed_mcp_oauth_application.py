# Unlike the first-party client (created lazily on first use via oauth_utils.get_or_create,
# because something in the real request path - issue_token_pair() - calls that helper),
# nothing in oauth2-toolkit's stock AuthorizationView/TokenView calls back into project code:
# they look Applications up directly from the database by client_id. So the "cadence-mcp" row
# has to exist before the first real request, which makes a data migration the right tool here.
from django.conf import settings
from django.db import migrations

MCP_CLIENT_ID = "cadence-mcp"

# Anthropic's documented OAuth callback for hosted Claude surfaces - not environment-specific.
MCP_REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback"

# Replicated from oauth2_provider's Application model — migrations must be self-contained and
# operate on a frozen historical model, which doesn't carry over class-level constants like
# Application.CLIENT_CONFIDENTIAL / Application.GRANT_AUTHORIZATION_CODE (confirmed live against
# this project's installed django-oauth-toolkit version).
_CLIENT_CONFIDENTIAL = "confidential"
_GRANT_AUTHORIZATION_CODE = "authorization-code"


def seed_mcp_application(apps, schema_editor):
    Application = apps.get_model("oauth2_provider", "Application")
    Application.objects.get_or_create(
        client_id=MCP_CLIENT_ID,
        defaults={
            "name": MCP_CLIENT_ID,
            "client_type": _CLIENT_CONFIDENTIAL,
            "authorization_grant_type": _GRANT_AUTHORIZATION_CODE,
            "redirect_uris": MCP_REDIRECT_URI,
            "client_secret": settings.OAUTH_MCP_CLIENT_SECRET,
            # The real consent screen - unlike the first-party client, which skips it entirely
            # (its own login *is* the consent). Direct equivalent of Java's
            # requireAuthorizationConsent(true).
            "skip_authorization": False,
        },
    )


def unseed_mcp_application(apps, schema_editor):
    Application = apps.get_model("oauth2_provider", "Application")
    Application.objects.filter(client_id=MCP_CLIENT_ID).delete()


class Migration(migrations.Migration):
    initial = True

    dependencies = [
        ("oauth2_provider", "0020_cimd_application_fields"),
    ]

    operations = [
        migrations.RunPython(seed_mcp_application, unseed_mcp_application),
    ]
