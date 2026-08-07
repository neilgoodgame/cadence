"""Grants (or revokes) the in-app Admin screen's is_admin flag for a user.

There's no self-service way to become the first admin - the Admin screen's own Users
tab can only toggle is_admin once you're already an admin - so this is the one-off
bootstrap path, run manually against each environment.

    python manage.py grant_admin --email you@example.com
    python manage.py grant_admin --email you@example.com --revoke
"""

from django.core.management.base import BaseCommand, CommandError

from accounts.models import User


class Command(BaseCommand):
    help = "Grants (or with --revoke, removes) the is_admin flag for a user, by email."

    def add_arguments(self, parser):
        parser.add_argument("--email", required=True, help="Athlete email.")
        parser.add_argument("--revoke", action="store_true", help="Remove admin access instead of granting it.")

    def handle(self, *args, **options):
        try:
            user = User.objects.get(email__iexact=options["email"])
        except User.DoesNotExist as exc:
            raise CommandError(f"No user with email {options['email']!r}") from exc

        user.is_admin = not options["revoke"]
        user.save(update_fields=["is_admin"])

        verb = "Revoked admin access from" if options["revoke"] else "Granted admin access to"
        self.stdout.write(self.style.SUCCESS(f"{verb} {user.email}."))
