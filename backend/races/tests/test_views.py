from datetime import date

from django.test import TestCase

from accounts.models import User, UserRelationship
from activities.models import Tag
from activities.tests.helpers import _bearer_client, _delegated_client, _make_activity
from courses.models import Course
from races.models import Race, RaceComment


class RaceCrudTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")

    def test_create_race_defaults_priority_to_a(self):
        response = _bearer_client(self.athlete).post(
            "/v1/races", {"name": "Spring Marathon", "date": "2026-04-12", "sport": "run"}, format="json"
        )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["priority"], "A")

    def test_create_race_with_priority_and_course(self):
        course = Course.objects.create(athlete=self.athlete, name="Valencia", source="gpx", distance_km=42.2)
        response = _bearer_client(self.athlete).post(
            "/v1/races",
            {
                "name": "Valencia Marathon",
                "date": "2026-12-06",
                "sport": "run",
                "priority": "B",
                "course_id": course.id,
            },
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(data["priority"], "B")
        self.assertEqual(data["course_id"], course.id)

    def test_create_and_update_race_key_session_tag_id_round_trips(self):
        tag = Tag.objects.create(athlete=self.athlete, name="Marathon key")
        response = _bearer_client(self.athlete).post(
            "/v1/races",
            {"name": "Goal Marathon", "date": "2026-10-12", "sport": "run", "key_session_tag_id": tag.id},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(data["key_session_tag_id"], tag.id)

        response = _bearer_client(self.athlete).patch(
            f"/v1/races/{data['id']}", {"key_session_tag_id": None}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertIsNone(response.json()["key_session_tag_id"])

    def test_create_and_update_race_target_power_round_trips(self):
        response = _bearer_client(self.athlete).post(
            "/v1/races",
            {"name": "Goal Marathon", "date": "2026-10-12", "sport": "run", "target_power": 250},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(data["target_power"], 250)

        response = _bearer_client(self.athlete).patch(f"/v1/races/{data['id']}", {"target_power": 275}, format="json")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["target_power"], 275)

    def test_list_includes_computed_block_fields_for_a_race(self):
        Race.objects.create(
            athlete=self.athlete, name="Goal Marathon", date=date(2026, 10, 12), priority="A", sport="run"
        )
        response = _bearer_client(self.athlete).get("/v1/races")
        self.assertEqual(response.status_code, 200)
        race_data = response.json()["data"][0]
        self.assertIsNone(race_data["parent_race_id"])
        self.assertEqual(race_data["block_weeks"], 16)
        self.assertIsNotNone(race_data["block_start"])

    def test_list_includes_parent_race_id_for_b_race_in_a_block(self):
        a_race = Race.objects.create(athlete=self.athlete, name="Goal Marathon", date=date(2026, 10, 12), priority="A")
        Race.objects.create(athlete=self.athlete, name="Tune-up Half", date=date(2026, 9, 14), priority="B")

        response = _bearer_client(self.athlete).get("/v1/races")
        by_name = {r["name"]: r for r in response.json()["data"]}
        self.assertEqual(by_name["Tune-up Half"]["parent_race_id"], a_race.id)

    def test_distance_bucket_default_overrides_the_flat_preference_on_create(self):
        self.athlete.race_block_week_defaults = {**self.athlete.race_block_week_defaults, "marathon": 20}
        self.athlete.save()
        response = _bearer_client(self.athlete).post(
            "/v1/races",
            {"name": "Goal Marathon", "date": "2026-10-12", "sport": "run", "distance_km": 42.195},
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["block_weeks"], 20)

    def test_explicit_block_plan_round_trips_and_drives_the_computed_block(self):
        response = _bearer_client(self.athlete).post(
            "/v1/races",
            {
                "name": "Goal Marathon",
                "date": "2026-10-12",
                "sport": "run",
                "block_weeks_requested": 20,
                "base_weeks_requested": 8,
                "build_weeks_requested": 7,
                "peak_weeks_requested": 2,
                "taper_weeks_requested": 3,
            },
            format="json",
        )
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertEqual(data["block_weeks_requested"], 20)
        self.assertEqual(data["block_weeks"], 20)

        block = _bearer_client(self.athlete).get(f"/v1/races/{data['id']}/block").json()
        self.assertEqual(block["weeks_requested"], 20)
        self.assertEqual(
            block["phases"],
            [
                {"name": "Base", "start_week": 1, "end_week": 8},
                {"name": "Build", "start_week": 9, "end_week": 15},
                {"name": "Peak", "start_week": 16, "end_week": 17},
                {"name": "Taper", "start_week": 18, "end_week": 20},
            ],
        )

    def test_partial_phase_weeks_are_rejected(self):
        response = _bearer_client(self.athlete).post(
            "/v1/races",
            {"name": "Goal Marathon", "date": "2026-10-12", "sport": "run", "base_weeks_requested": 5},
            format="json",
        )
        self.assertEqual(response.status_code, 400)

    def test_phase_weeks_not_summing_to_block_weeks_are_rejected(self):
        response = _bearer_client(self.athlete).post(
            "/v1/races",
            {
                "name": "Goal Marathon",
                "date": "2026-10-12",
                "sport": "run",
                "block_weeks_requested": 20,
                "base_weeks_requested": 5,
                "build_weeks_requested": 5,
                "peak_weeks_requested": 2,
                "taper_weeks_requested": 3,
            },
            format="json",
        )
        self.assertEqual(response.status_code, 400)

    def test_block_plan_fields_are_rejected_on_a_bc_race(self):
        response = _bearer_client(self.athlete).post(
            "/v1/races",
            {
                "name": "Tune-up Half",
                "date": "2026-09-14",
                "sport": "run",
                "priority": "B",
                "block_weeks_requested": 10,
            },
            format="json",
        )
        self.assertEqual(response.status_code, 400)


class RaceBlockAndSplitsViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.race = Race.objects.create(
            athlete=self.athlete,
            name="Goal Marathon",
            date=date(2026, 10, 12),
            priority="A",
            sport="run",
            distance_km=42.2,
        )

    def test_block_endpoint_returns_weeks(self):
        response = _bearer_client(self.athlete).get(f"/v1/races/{self.race.id}/block")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.json()["weeks"]), 16)

    def test_block_endpoint_denies_outsider(self):
        client = _delegated_client(self.outsider, self.athlete, ["activities:read"])
        response = client.get(f"/v1/races/{self.race.id}/block")
        self.assertEqual(response.status_code, 403)

    def test_splits_endpoint_rejects_unknown_mode(self):
        response = _bearer_client(self.athlete).get(f"/v1/races/{self.race.id}/splits", {"mode": "weird"})
        self.assertEqual(response.status_code, 400)

    def test_splits_endpoint_defaults_to_laps(self):
        response = _bearer_client(self.athlete).get(f"/v1/races/{self.race.id}/splits")
        self.assertEqual(response.status_code, 200)
        self.assertIn("has_laps", response.json())

    def test_custom_splits_endpoint_rejects_malformed_steps(self):
        activity = _make_activity(self.athlete, distance_km=10.0, moving_time=3000)
        race = Race.objects.create(athlete=self.athlete, name="Linked race", date=self.race.date, activity=activity)
        response = _bearer_client(self.athlete).post(
            f"/v1/races/{race.id}/splits", {"steps": [{"end_type": "time"}]}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_custom_splits_endpoint_denies_outsider(self):
        client = _delegated_client(self.outsider, self.athlete, ["activities:read"])
        response = client.post(
            f"/v1/races/{self.race.id}/splits",
            {"steps": [{"end_type": "distance", "distance_km": 1.0}]},
            format="json",
        )
        self.assertEqual(response.status_code, 403)


class RaceCommentTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.coach = User.objects.create_user(email="coach@example.cc", password="x", name="Coach")
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.coach,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.race = Race.objects.create(
            athlete=self.athlete, name="Goal Marathon", date=date(2026, 10, 12), priority="A"
        )

    def test_athlete_can_post_and_list_comments(self):
        response = _bearer_client(self.athlete).post(
            f"/v1/races/{self.race.id}/comments", {"text": "Feeling strong this week."}, format="json"
        )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["role"], "athlete")

        listing = _bearer_client(self.athlete).get(f"/v1/races/{self.race.id}/comments")
        self.assertEqual(len(listing.json()["data"]), 1)

    def test_coach_with_grant_posts_as_coach_role(self):
        client = _delegated_client(self.coach, self.athlete, ["activities:read", "activities:write", "coach"])
        response = client.post(
            f"/v1/races/{self.race.id}/comments", {"text": "Great block so far.", "week": 3}, format="json"
        )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["role"], "coach")
        self.assertEqual(response.json()["week"], 3)

    def test_outsider_without_grant_cannot_comment(self):
        client = _delegated_client(self.outsider, self.athlete, ["activities:read"])
        response = client.post(f"/v1/races/{self.race.id}/comments", {"text": "Uninvited."}, format="json")
        self.assertEqual(response.status_code, 403)

    def test_reply_to_a_reply_is_rejected(self):
        top_level = RaceComment.objects.create(race=self.race, author=self.athlete, role="athlete", text="Top level")
        reply = RaceComment.objects.create(
            race=self.race, author=self.athlete, role="athlete", text="A reply", parent=top_level
        )

        response = _bearer_client(self.athlete).post(
            f"/v1/races/{self.race.id}/comments", {"text": "Reply to a reply", "parent_id": reply.id}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_author_can_delete_own_comment(self):
        comment = RaceComment.objects.create(race=self.race, author=self.athlete, role="athlete", text="Delete me")
        response = _bearer_client(self.athlete).delete(f"/v1/races/{self.race.id}/comments/{comment.id}")
        self.assertEqual(response.status_code, 204)

    def test_cannot_delete_someone_elses_comment(self):
        comment = RaceComment.objects.create(race=self.race, author=self.athlete, role="athlete", text="Not yours")
        client = _delegated_client(self.coach, self.athlete, ["activities:read", "coach"])
        response = client.delete(f"/v1/races/{self.race.id}/comments/{comment.id}")
        self.assertEqual(response.status_code, 403)
