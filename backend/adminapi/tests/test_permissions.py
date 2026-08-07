from django.test import TestCase
from rest_framework.test import APIClient

from accounts.models import User

from .helpers import _bearer_client

ENDPOINTS = [
    ("get", "/v1/admin/shoe-catalog"),
    ("post", "/v1/admin/shoe-catalog"),
    ("get", "/v1/admin/users"),
    ("get", "/v1/admin/relationships"),
    ("get", "/v1/admin/audit-log"),
]


class AdminPermissionTests(TestCase):
    def setUp(self):
        self.admin = User.objects.create_user(email="admin@example.cc", password="x", name="Admin", is_admin=True)
        self.coach = User.objects.create_user(email="coach@example.cc", password="x", name="Coach", is_coach=True)
        self.plain = User.objects.create_user(email="plain@example.cc", password="x", name="Plain")

    def test_every_endpoint_rejects_non_admin(self):
        for user in (self.coach, self.plain):
            client = _bearer_client(user)
            for method, path in ENDPOINTS:
                response = getattr(client, method)(path, {}, format="json")
                self.assertEqual(response.status_code, 403, f"{method.upper()} {path} for {user.email}")

    def test_every_endpoint_rejects_unauthenticated(self):
        client = APIClient()
        for method, path in ENDPOINTS:
            response = getattr(client, method)(path, {}, format="json")
            self.assertEqual(response.status_code, 401, f"{method.upper()} {path}")

    def test_admin_can_reach_every_list_endpoint(self):
        client = _bearer_client(self.admin)
        for method, path in ENDPOINTS:
            if method != "get":
                continue
            response = client.get(path)
            self.assertEqual(response.status_code, 200, path)
