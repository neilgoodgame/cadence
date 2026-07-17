import tempfile
from datetime import UTC, datetime, timedelta
from unittest.mock import MagicMock, patch

from django.test import SimpleTestCase

from ..fixtures_helpers import build_gpx, build_tcx
from ..parsers import UnsupportedFileType, parse_file
from ..parsers.fit import parse_fit
from ..parsers.gpx import parse_gpx
from ..parsers.tcx import parse_tcx
from .helpers import FIXTURES_DIR, _fit_msg


class GpxParserTests(SimpleTestCase):
    def test_parses_track_points_and_hr_extension(self):
        start = datetime(2026, 6, 1, 8, 0, 0, tzinfo=UTC)
        content = build_gpx(start, sport="running", duration_s=10, hr=150)
        with tempfile.NamedTemporaryFile(suffix=".gpx") as f:
            f.write(content)
            f.flush()
            result = parse_gpx(f.name)

        self.assertEqual(result["sport"], "run")
        self.assertTrue(result["has_gps"])
        self.assertEqual(len(result["samples"]), 10)
        self.assertEqual(result["samples"][0]["heartrate"], 150)
        self.assertEqual(result["start_date"], start)


class TcxParserTests(SimpleTestCase):
    def test_parses_trackpoints_power_and_laps(self):
        start = datetime(2026, 6, 1, 8, 0, 0, tzinfo=UTC)
        content = build_tcx(start, sport="Biking", duration_s=10, power=200, hr=140, distance_m=50)
        with tempfile.NamedTemporaryFile(suffix=".tcx") as f:
            f.write(content)
            f.flush()
            result = parse_tcx(f.name)

        self.assertEqual(result["sport"], "bike")
        self.assertEqual(len(result["samples"]), 10)
        self.assertEqual(result["samples"][0]["power"], 200)
        self.assertEqual(len(result["laps"]), 1)
        self.assertEqual(result["laps"][0]["duration"], 10)


class FitParserMockedTests(SimpleTestCase):
    @patch("uploads.parsers.fit.FitFile")
    def test_parses_gps_semicircles_and_laps(self, mock_fitfile_cls):
        start = datetime(2026, 6, 1, 8, 0, 0)  # naive, like real fitparse timestamps
        lat_semicircles = 642065151
        lng_semicircles = -188432580

        records = [
            _fit_msg(
                timestamp=start,
                position_lat=lat_semicircles,
                position_long=lng_semicircles,
                heart_rate=140,
                power=200,
                cadence=85,
                distance=0.0,
                enhanced_speed=5.0,
                enhanced_altitude=10.0,
            ),
            _fit_msg(
                timestamp=start + timedelta(seconds=1),
                position_lat=lat_semicircles,
                position_long=lng_semicircles,
                heart_rate=141,
                power=205,
                cadence=86,
                distance=5.0,
                enhanced_speed=5.1,
                enhanced_altitude=10.5,
            ),
        ]
        laps = [_fit_msg(total_elapsed_time=2, total_distance=5.0, avg_heart_rate=140, avg_power=202)]
        sessions = [_fit_msg(sport="cycling")]

        instance = MagicMock()
        instance.get_messages.side_effect = lambda name: {
            "record": records,
            "lap": laps,
            "session": sessions,
            "field_description": [],
            "file_id": [],
        }[name]
        mock_fitfile_cls.return_value = instance

        (result,) = parse_fit("/fake/path.fit")

        self.assertEqual(result["sport"], "bike")
        self.assertTrue(result["has_gps"])
        self.assertEqual(result["environment"], "outdoor")
        self.assertEqual(len(result["samples"]), 2)
        self.assertAlmostEqual(result["samples"][0]["lat"], lat_semicircles * (180 / 2**31), places=6)
        self.assertEqual(result["samples"][1]["t"], 1)
        self.assertEqual(result["samples"][1]["power"], 205)
        self.assertEqual(len(result["laps"]), 1)
        self.assertEqual(result["laps"][0]["avg_power"], 202)

    @patch("uploads.parsers.fit.FitFile")
    def test_lap_avg_power_falls_back_to_sample_average_when_lap_message_omits_it(self, mock_fitfile_cls):
        # Third-party run-power meters (e.g. Stryd) write power as a record-level developer
        # field but never fill in the lap message's own avg_power summary field - only a
        # native power meter does that. Confirms the fallback computes it from samples instead.
        start = datetime(2026, 6, 1, 8, 0, 0)
        records = [
            _fit_msg(timestamp=start, heart_rate=140, power=200),
            _fit_msg(timestamp=start + timedelta(seconds=1), heart_rate=141, power=206),
        ]
        laps = [_fit_msg(start_time=start, total_elapsed_time=2, total_distance=5.0, avg_heart_rate=140)]
        sessions = [_fit_msg(sport="running")]

        instance = MagicMock()
        instance.get_messages.side_effect = lambda name: {
            "record": records,
            "lap": laps,
            "session": sessions,
            "field_description": [],
            "file_id": [],
        }[name]
        mock_fitfile_cls.return_value = instance

        (result,) = parse_fit("/fake/path.fit")

        self.assertEqual(len(result["laps"]), 1)
        self.assertEqual(result["laps"][0]["avg_power"], 203)


class ParseFileDispatchTests(SimpleTestCase):
    def test_unsupported_extension_raises(self):
        with self.assertRaises(UnsupportedFileType):
            parse_file("/fake/path.csv", "data.csv")


class RealFitFixtureParserTests(SimpleTestCase):
    """Real-world FIT files (Garmin + Stryd run-power dev field), not synthetic ones."""

    def test_cycling_indoor_has_native_power_field(self):
        (result,) = parse_fit(str(FIXTURES_DIR / "cycling_indoor.fit"))
        self.assertEqual(result["sport"], "bike")
        self.assertEqual(result["environment"], "indoor")
        self.assertFalse(result["has_gps"])
        self.assertEqual(len(result["samples"]), 4283)
        self.assertEqual(len(result["laps"]), 8)
        self.assertTrue(all(s["power"] is not None for s in result["samples"]))

    def test_cycling_indoor_has_core_sensor_fields_but_no_stryd(self):
        (result,) = parse_fit(str(FIXTURES_DIR / "cycling_indoor.fit"))
        self.assertTrue(any(s["core_temp"] is not None for s in result["samples"]))
        self.assertTrue(any(s["skin_temp"] is not None for s in result["samples"]))
        self.assertTrue(any(s["heat_strain"] is not None for s in result["samples"]))
        self.assertTrue(all(s["air_temp"] is None for s in result["samples"]))
        self.assertTrue(all(s["humidity"] is None for s in result["samples"]))

    def test_running_outdoor_marathon_falls_back_to_stryd_power_field(self):
        (result,) = parse_fit(str(FIXTURES_DIR / "running_outdoor_marathon.fit"))
        self.assertEqual(result["sport"], "run")
        self.assertEqual(result["environment"], "outdoor")
        self.assertTrue(result["has_gps"])
        self.assertEqual(len(result["samples"]), 12241)
        self.assertTrue(all(s["power"] is not None for s in result["samples"]))

    def test_running_outdoor_marathon_has_stryd_env_fields_but_no_core(self):
        (result,) = parse_fit(str(FIXTURES_DIR / "running_outdoor_marathon.fit"))
        self.assertTrue(all(s["air_temp"] is not None for s in result["samples"]))
        self.assertTrue(all(s["humidity"] is not None for s in result["samples"]))
        self.assertTrue(all(s["core_temp"] is None for s in result["samples"]))
        self.assertTrue(all(s["skin_temp"] is None for s in result["samples"]))
        self.assertTrue(all(s["heat_strain"] is None for s in result["samples"]))

    def test_running_outdoor_marathon_laps_get_avg_power_from_stryd_samples(self):
        # This device (Stryd-equipped run) never fills in the lap message's own avg_power
        # field - confirmed directly against the fixture's raw lap messages, all None -
        # so every one of these must come from the sample-average fallback, not the lap
        # message itself.
        (result,) = parse_fit(str(FIXTURES_DIR / "running_outdoor_marathon.fit"))
        self.assertEqual(len(result["laps"]), 5)
        self.assertTrue(all(lap["avg_power"] is not None for lap in result["laps"]))

    def test_running_treadmill_falls_back_to_stryd_power_field(self):
        (result,) = parse_fit(str(FIXTURES_DIR / "running_treadmill.fit"))
        self.assertEqual(result["sport"], "run")
        self.assertEqual(result["environment"], "indoor")
        self.assertFalse(result["has_gps"])
        self.assertEqual(len(result["samples"]), 5299)
        self.assertTrue(all(s["power"] is not None for s in result["samples"]))

    def test_device_from_file_id_manufacturer(self):
        (result,) = parse_fit(str(FIXTURES_DIR / "cycling_indoor.fit"))
        self.assertEqual(result["device"], "Garmin")

    def test_device_skips_zwift_garbage_product_name(self):
        # Zwift's file_id carries a product_name of garbage bytes - here a bare "&" -
        # which must not be appended to the manufacturer.
        (result,) = parse_fit(str(FIXTURES_DIR / "cycling_with_scaled_core_sensor_fields.fit"))
        self.assertEqual(result["device"], "Zwift")

    def test_running_treadmill_has_both_stryd_and_core_fields(self):
        (result,) = parse_fit(str(FIXTURES_DIR / "running_treadmill.fit"))
        self.assertTrue(all(s["air_temp"] is not None for s in result["samples"]))
        self.assertTrue(all(s["humidity"] is not None for s in result["samples"]))
        self.assertTrue(any(s["core_temp"] is not None for s in result["samples"]))
        self.assertTrue(any(s["skin_temp"] is not None for s in result["samples"]))
        self.assertTrue(any(s["heat_strain"] is not None for s in result["samples"]))


class MultisportFitFixtureTests(SimpleTestCase):
    """Real multisport file: an outdoor run, a transition, and an indoor ride recorded
    as one session on the device. The parser returns the parent (whole file, sport
    "multisport") first, then one child per session in start order.
    """

    def test_returns_parent_then_one_child_per_session(self):
        parsed = parse_fit(str(FIXTURES_DIR / "multisport.fit"))
        self.assertEqual([p["sport"] for p in parsed], ["multisport", "run", "transition", "bike"])

    def test_children_partition_the_parent_record_stream(self):
        parent, *children = parse_fit(str(FIXTURES_DIR / "multisport.fit"))
        self.assertEqual(len(parent["samples"]), sum(len(c["samples"]) for c in children))

    def test_child_time_and_distance_are_rebased_to_the_leg(self):
        _parent, run, _transition, bike = parse_fit(str(FIXTURES_DIR / "multisport.fit"))
        self.assertEqual(run["samples"][0]["t"], 0)
        self.assertEqual(bike["samples"][0]["t"], 0)
        # The file's distance stream is cumulative across the whole session; each leg's
        # slice must restart near zero and end at the leg's own distance, not the total's.
        run_distances = [s["distance_km"] for s in run["samples"] if s["distance_km"] is not None]
        bike_distances = [s["distance_km"] for s in bike["samples"] if s["distance_km"] is not None]
        self.assertLess(run_distances[0], 0.1)
        self.assertLess(bike_distances[0], 0.1)
        self.assertAlmostEqual(run_distances[-1], 16.1, delta=0.2)
        self.assertAlmostEqual(bike_distances[-1], 30.2, delta=0.2)

    def test_leg_environments_follow_their_own_gps(self):
        parent, run, _transition, bike = parse_fit(str(FIXTURES_DIR / "multisport.fit"))
        self.assertEqual(run["environment"], "outdoor")
        self.assertEqual(bike["environment"], "indoor")
        self.assertEqual(parent["environment"], "outdoor")

    def test_device_shared_by_parent_and_every_leg(self):
        # file_id describes the recording device for the whole file, so the parent and
        # each leg carry the same device.
        parsed = parse_fit(str(FIXTURES_DIR / "multisport.fit"))
        self.assertEqual([p["device"] for p in parsed], ["Garmin"] * 4)


class DeveloperFieldScaleTests(SimpleTestCase):
    """cycling_with_scaled_core_sensor_fields.fit is recorded on a device that actually
    declares a developer field scale (skin_temperature: 100, heat_strain_index: 10) - most
    devices don't, which is exactly why this regressed silently: fitparse==1.2.0 never reads
    a developer field's declared scale/offset from the file's own field_description messages,
    so the unscaled fixtures (cycling_indoor.fit etc.) couldn't have caught it.
    """

    def test_skin_temperature_scale_is_applied(self):
        (result,) = parse_fit(str(FIXTURES_DIR / "cycling_with_scaled_core_sensor_fields.fit"))
        skin_temps = [s["skin_temp"] for s in result["samples"] if s["skin_temp"] is not None]
        self.assertTrue(skin_temps)
        # A raw, unscaled value would be in the thousands (e.g. 3446 instead of 34.46).
        self.assertTrue(all(20 <= t <= 45 for t in skin_temps), skin_temps[:5])

    def test_heat_strain_index_scale_is_applied(self):
        (result,) = parse_fit(str(FIXTURES_DIR / "cycling_with_scaled_core_sensor_fields.fit"))
        heat_strains = [s["heat_strain"] for s in result["samples"] if s["heat_strain"]]
        self.assertTrue(heat_strains)
        # An unscaled value is always a whole number (the raw encoded integer); a correctly
        # scaled one is a multiple of 0.1, so at least one non-integer value confirms the
        # division by the declared scale (10) actually happened.
        self.assertTrue(any(v % 1 != 0 for v in heat_strains), heat_strains[:10])
