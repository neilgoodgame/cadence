"""Issue #540 (phase 5): agreement_trend_service.compute_agreement_trend - tag resolution
(primary match, stored-source match, no match -> skip), validation, skip-reason tallying,
duplicate/multisport-child exclusion, and dropout-exclusion passthrough. Mirrors backend_java's
AgreementTrendServiceIntegrationTest."""

from datetime import UTC, date, datetime, timedelta

from django.test import TestCase
from rest_framework.exceptions import ValidationError

from accounts.models import User

from ..agreement_trend_service import compute_agreement_trend
from ..models import ActivitySource, ActivityStream, Record
from .helpers import _bearer_client, _make_activity

# 60 paired seconds clears stream_comparison.MIN_OVERLAP_SAMPLES (30) with room to spare.
SAMPLES = 60


class AgreementTrendServiceTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="agreement-trend@example.cc", password="x", name="Athlete")
        self.june = datetime(2026, 6, 3, 9, 0, tzinfo=UTC)

    def _bike_activity(self, device, name, start_date=None):
        return _make_activity(
            self.athlete,
            sport="bike",
            name=name,
            start_date=start_date or self.june,
            moving_time=SAMPLES,
            device=device,
        )

    def _add_power_records(self, activity, count, base_value):
        for t in range(count):
            Record.objects.create(
                activity=activity, t=t, ts=activity.start_date + timedelta(seconds=t), power=base_value + t
            )

    def _add_heartrate_records(self, activity, count, base_value):
        for t in range(count):
            Record.objects.create(
                activity=activity, t=t, ts=activity.start_date + timedelta(seconds=t), heartrate=base_value + t
            )

    def _stored_source(self, activity, field, count, base_value, **source_kwargs):
        source = ActivitySource.objects.create(activity=activity, label="Stored", sensor_kind=field, **source_kwargs)
        ActivityStream.objects.create(
            activity=activity,
            field=field,
            source=source,
            t0=0,
            values=[base_value + t for t in range(count)],
            primary_of=False,
        )
        return source

    def _trend(self, field, device_a, device_b, sport=None, **kwargs):
        return compute_agreement_trend(
            self.athlete.id, field, device_a, device_b, sport, date(2026, 6, 1), date(2026, 6, 30), **kwargs
        )

    def test_primary_tag_resolves_to_the_record_series(self):
        # device "Zwift" classifies as trainer, so the primary (Record) carries tag "trainer".
        activity = self._bike_activity("Zwift", "Ride")
        self._add_power_records(activity, SAMPLES, 200)
        self._stored_source(activity, "power", SAMPLES, 210, device="Edge 530", origin="attached")

        points, skipped = self._trend("power", "trainer", "pedals", sport="bike")

        self.assertEqual(len(points), 1)
        point = points[0]
        self.assertEqual(point.activity_id, activity.id)
        self.assertEqual(point.name, "Ride")
        self.assertEqual(point.date, date(2026, 6, 3))
        self.assertEqual(point.overlap_seconds, SAMPLES)
        # bias = mean(b - a) = mean((210+t) - (200+t))
        self.assertEqual(point.bias, 10.0)
        self.assertEqual(skipped, {"no_device_a": 0, "no_device_b": 0, "insufficient_overlap": 0})

    def test_stored_source_tag_resolves_on_either_side(self):
        activity = self._bike_activity("Zwift", "Ride")
        self._add_power_records(activity, SAMPLES, 200)
        self._stored_source(activity, "power", SAMPLES, 210, device="Edge 530", origin="attached")

        points, _ = self._trend("power", "pedals", "trainer", sport="bike")

        # Same pair, sides swapped - bias flips sign.
        self.assertEqual(len(points), 1)
        self.assertEqual(points[0].bias, -10.0)

    def test_no_match_skips_the_activity(self):
        # Edge 530 classifies as pedals; with no stored source, tag "trainer" resolves to nothing.
        activity = self._bike_activity("Edge 530", "Ride")
        self._add_power_records(activity, SAMPLES, 200)

        points, skipped = self._trend("power", "trainer", "pedals", sport="bike")

        self.assertEqual(points, [])
        self.assertEqual(skipped, {"no_device_a": 1, "no_device_b": 0, "insufficient_overlap": 0})

    def test_most_recently_created_source_wins_for_an_ambiguous_tag(self):
        activity = self._bike_activity("Zwift", "Ride")
        self._add_power_records(activity, SAMPLES, 200)
        older = self._stored_source(activity, "power", SAMPLES, 205, device="Edge 530", origin="attached")
        newer = self._stored_source(activity, "power", SAMPLES, 210, device="Edge 530", origin="attached")
        # auto_now_add timestamps can collide within one test - pin them explicitly.
        ActivitySource.objects.filter(pk=older.pk).update(created_at=datetime(2026, 6, 1, tzinfo=UTC))
        ActivitySource.objects.filter(pk=newer.pk).update(created_at=datetime(2026, 6, 2, tzinfo=UTC))

        points, _ = self._trend("power", "trainer", "pedals", sport="bike")

        # The newer source (+10) wins, not the older one (+5).
        self.assertEqual(points[0].bias, 10.0)

    def test_sport_is_required_for_power(self):
        with self.assertRaises(ValidationError) as cm:
            self._trend("power", "trainer", "pedals")
        self.assertIn("sport", cm.exception.detail)
        with self.assertRaises(ValidationError) as cm:
            self._trend("power", "trainer", "pedals", sport="swim")
        self.assertIn("sport", cm.exception.detail)

    def test_device_tags_are_validated_against_the_field_vocabulary(self):
        with self.assertRaises(ValidationError) as cm:
            self._trend("heartrate", "trainer", "wrist")
        self.assertIn("device_a", cm.exception.detail)
        with self.assertRaises(ValidationError) as cm:
            self._trend("power", "native", "pedals", sport="bike")
        self.assertIn("device_a", cm.exception.detail)

    def test_identical_device_tags_are_rejected(self):
        with self.assertRaises(ValidationError) as cm:
            self._trend("power", "pedals", "pedals", sport="bike")
        self.assertEqual(cm.exception.detail["device_b"], "device_a and device_b must be two different device tags.")

    def test_bad_field_is_rejected(self):
        with self.assertRaises(ValidationError) as cm:
            self._trend("bogus", "a", "b")
        self.assertIn("field", cm.exception.detail)

    def test_skip_reason_tallying(self):
        # Point: Zwift primary (trainer) + pedals source, full overlap.
        good = self._bike_activity("Zwift", "Good")
        self._add_power_records(good, SAMPLES, 200)
        self._stored_source(good, "power", SAMPLES, 210, device="Edge 530", origin="attached")
        # no_device_a: pedals primary, nothing stored as trainer.
        no_a = self._bike_activity("Edge 530", "No A")
        self._add_power_records(no_a, SAMPLES, 200)
        # insufficient_overlap: both tags resolve but only 10 paired seconds.
        short = self._bike_activity("Zwift", "Short")
        self._add_power_records(short, SAMPLES, 200)
        self._stored_source(short, "power", 10, 210, device="Edge 530", origin="attached")
        # no_device_b: trainer primary, nothing stored as pedals.
        no_b = self._bike_activity("Zwift", "No B")
        self._add_power_records(no_b, SAMPLES, 200)

        points, skipped = self._trend("power", "trainer", "pedals", sport="bike")

        self.assertEqual([p.activity_id for p in points], [good.id])
        self.assertEqual(skipped, {"no_device_a": 1, "no_device_b": 1, "insufficient_overlap": 1})

    def test_heartrate_tags_use_source_kind_not_device(self):
        activity = _make_activity(
            self.athlete, sport="run", name="Run", start_date=self.june, moving_time=SAMPLES, hr_source="wrist"
        )
        self._add_heartrate_records(activity, SAMPLES, 150)
        self._stored_source(
            activity, "heartrate", SAMPLES, 155, device="Garmin Edge 830", origin="attached", source_kind="strap"
        )

        # sport is optional for heartrate - omit it.
        points, skipped = self._trend("heartrate", "wrist", "strap")

        self.assertEqual(len(points), 1)
        self.assertEqual(points[0].bias, 5.0)
        self.assertEqual(skipped, {"no_device_a": 0, "no_device_b": 0, "insufficient_overlap": 0})

    def test_run_power_tags_use_source_kind(self):
        activity = _make_activity(
            self.athlete, sport="run", name="Run", start_date=self.june, moving_time=SAMPLES, power_source="stryd"
        )
        self._add_power_records(activity, SAMPLES, 250)
        self._stored_source(
            activity, "power", SAMPLES, 260, device="Garmin Forerunner 965", origin="file", source_kind="native"
        )

        points, _ = self._trend("power", "stryd", "native", sport="run")

        self.assertEqual(len(points), 1)
        self.assertEqual(points[0].bias, 10.0)

    def test_duplicates_and_multisport_children_are_excluded_by_default(self):
        primary = self._bike_activity("Zwift", "Primary")
        self._add_power_records(primary, SAMPLES, 200)
        self._stored_source(primary, "power", SAMPLES, 210, device="Edge 530", origin="attached")
        duplicate = self._bike_activity("Zwift", "Duplicate", start_date=self.june + timedelta(days=1))
        duplicate.primary_activity = primary
        duplicate.save()
        self._add_power_records(duplicate, SAMPLES, 200)
        self._stored_source(duplicate, "power", SAMPLES, 210, device="Edge 530", origin="attached")
        parent = _make_activity(self.athlete, sport="multisport", name="Tri", start_date=self.june + timedelta(days=2))
        child = self._bike_activity("Zwift", "Leg", start_date=self.june + timedelta(days=2))
        child.parent_activity = parent
        child.save()
        self._add_power_records(child, SAMPLES, 200)
        self._stored_source(child, "power", SAMPLES, 210, device="Edge 530", origin="attached")

        points, _ = self._trend("power", "trainer", "pedals", sport="bike")
        self.assertEqual([p.activity_id for p in points], [primary.id])

        points, _ = self._trend("power", "trainer", "pedals", sport="bike", include_duplicates=True)
        self.assertEqual({p.activity_id for p in points}, {primary.id, duplicate.id})

    def test_date_range_filters_activities(self):
        outside = self._bike_activity("Zwift", "January", start_date=datetime(2026, 1, 5, 9, 0, tzinfo=UTC))
        self._add_power_records(outside, SAMPLES, 200)
        self._stored_source(outside, "power", SAMPLES, 210, device="Edge 530", origin="attached")
        inside = self._bike_activity("Zwift", "June")
        self._add_power_records(inside, SAMPLES, 200)
        self._stored_source(inside, "power", SAMPLES, 210, device="Edge 530", origin="attached")

        points, _ = self._trend("power", "trainer", "pedals", sport="bike")

        self.assertEqual([p.activity_id for p in points], [inside.id])

    def test_dropout_exclusion_passthrough(self):
        # Primary (trainer) has a confirmed 2s dropout at t=4,5 - the pedals side reads >100W
        # there, corroborating it as a glitch rather than coasting.
        activity = self._bike_activity("Zwift", "Ride")
        for t in range(SAMPLES):
            Record.objects.create(
                activity=activity,
                t=t,
                ts=activity.start_date + timedelta(seconds=t),
                power=0 if t in (4, 5) else 200 + t,
            )
        self._stored_source(activity, "power", SAMPLES, 210, device="Edge 530", origin="attached")

        points_excluded, _ = self._trend("power", "trainer", "pedals", sport="bike")
        self.assertEqual(points_excluded[0].dropout_seconds_a, 2)
        self.assertEqual(points_excluded[0].dropout_seconds_b, 0)
        # exclude_dropouts defaults to true - the dropout seconds are out of the statistics.
        self.assertEqual(points_excluded[0].overlap_seconds, SAMPLES - 2)
        self.assertEqual(points_excluded[0].bias, 10.0)

        points_included, _ = self._trend("power", "trainer", "pedals", sport="bike", exclude_dropouts=False)
        # Still detected and reported, but now inside the statistics (a=0 there skews bias).
        self.assertEqual(points_included[0].dropout_seconds_a, 2)
        self.assertEqual(points_included[0].overlap_seconds, SAMPLES)
        self.assertGreater(points_included[0].bias, 15.0)

    def test_rest_endpoint_returns_the_trend(self):
        activity = self._bike_activity("Zwift", "Ride")
        self._add_power_records(activity, SAMPLES, 200)
        self._stored_source(activity, "power", SAMPLES, 210, device="Edge 530", origin="attached")

        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/streams/agreement",
            {
                "field": "power",
                "device_a": "trainer",
                "device_b": "pedals",
                "sport": "bike",
                # The view defaults to a trailing-84-day window from today - the fixture is in
                # June, so pin the range explicitly.
                "from_date": "2026-06-01",
                "to_date": "2026-06-30",
            },
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["field"], "power")
        self.assertEqual(body["device_a"], "trainer")
        self.assertEqual(body["sport"], "bike")
        self.assertEqual(len(body["points"]), 1)
        point = body["points"][0]
        self.assertEqual(point["activity_id"], activity.id)
        self.assertEqual(point["date"], "2026-06-03")
        self.assertAlmostEqual(point["bias"], 10.0, places=3)
        self.assertEqual(body["skipped"], {"no_device_a": 0, "no_device_b": 0, "insufficient_overlap": 0})

    def test_rest_endpoint_rejects_a_bad_combination(self):
        # field=power without sport is a 400, not an empty trend.
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/streams/agreement",
            {"field": "power", "device_a": "trainer", "device_b": "pedals"},
        )

        self.assertEqual(response.status_code, 400)
