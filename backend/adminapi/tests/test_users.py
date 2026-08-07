from django.test import TestCase

from accounts.models import User

from .helpers import _bearer_client


class AdminUserTests(TestCase):
    def setUp(self):
        self.admin = User.objects.create_user(email="admin@example.cc", password="x", name="Admin", is_admin=True)
        self.client_ = _bearer_client(self.admin)

    def test_list_is_unscoped_across_all_users(self):
        User.objects.create_user(email="a@example.cc", password="x", name="A")
        User.objects.create_user(email="b@example.cc", password="x", name="B")

        response = self.client_.get("/v1/admin/users")
        self.assertEqual(response.status_code, 200)
        emails = {u["email"] for u in response.json()["data"]}
        self.assertEqual(emails, {"admin@example.cc", "a@example.cc", "b@example.cc"})

    def test_search_filters_name_or_email(self):
        User.objects.create_user(email="sam.o@example.com", password="x", name="Sam Ortega")
        User.objects.create_user(email="priya@example.com", password="x", name="Priya Nair")

        response = self.client_.get("/v1/admin/users?q=sam")
        data = response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["name"], "Sam Ortega")

    def test_patch_toggles_only_coach(self):
        user = User.objects.create_user(email="target@example.cc", password="x", name="Target")
        response = self.client_.patch(f"/v1/admin/users/{user.id}", {"is_coach": True}, format="json")
        self.assertEqual(response.status_code, 200)
        user.refresh_from_db()
        self.assertTrue(user.is_coach)
        self.assertFalse(user.is_admin)

    def test_patch_toggles_only_admin(self):
        user = User.objects.create_user(email="target2@example.cc", password="x", name="Target2")
        response = self.client_.patch(f"/v1/admin/users/{user.id}", {"is_admin": True}, format="json")
        self.assertEqual(response.status_code, 200)
        user.refresh_from_db()
        self.assertTrue(user.is_admin)
        self.assertFalse(user.is_coach)

    def test_patch_can_set_both_fields_together(self):
        user = User.objects.create_user(email="target3@example.cc", password="x", name="Target3")
        response = self.client_.patch(f"/v1/admin/users/{user.id}", {"is_coach": True, "is_admin": True}, format="json")
        self.assertEqual(response.status_code, 200)
        user.refresh_from_db()
        self.assertTrue(user.is_coach)
        self.assertTrue(user.is_admin)
