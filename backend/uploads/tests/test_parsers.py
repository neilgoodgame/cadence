import tempfile
from datetime import datetime, timedelta, timezone
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
        start = datetime(2026, 6, 1, 8, 0, 0, tzinfo=timezone.utc)
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
        start = datetime(2026, 6, 1, 8, 0, 0, tzinfo=timezone.utc)
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
        }[name]
        mock_fitfile_cls.return_value = instance

        result = parse_fit("/fake/path.fit")

        self.assertEqual(result["sport"], "bike")
        self.assertTrue(result["has_gps"])
        self.assertEqual(result["environment"], "outdoor")
        self.assertEqual(len(result["samples"]), 2)
        self.assertAlmostEqual(result["samples"][0]["lat"], lat_semicircles * (180 / 2**31), places=6)
        self.assertEqual(result["samples"][1]["t"], 1)
        self.assertEqual(result["samples"][1]["power"], 205)
        self.assertEqual(len(result["laps"]), 1)
        self.assertEqual(result["laps"][0]["avg_power"], 202)


class ParseFileDispatchTests(SimpleTestCase):
    def test_unsupported_extension_raises(self):
        with self.assertRaises(UnsupportedFileType):
            parse_file("/fake/path.csv", "data.csv")


class RealFitFixtureParserTests(SimpleTestCase):
    """Real-world FIT files (Garmin + Stryd run-power dev field), not synthetic ones."""

    def test_cycling_indoor_has_native_power_field(self):
        result = parse_fit(str(FIXTURES_DIR / "cycling_indoor.fit"))
        self.assertEqual(result["sport"], "bike")
        self.assertEqual(result["environment"], "indoor")
        self.assertFalse(result["has_gps"])
        self.assertEqual(len(result["samples"]), 4283)
        self.assertEqual(len(result["laps"]), 8)
        self.assertTrue(all(s["power"] is not None for s in result["samples"]))

    def test_cycling_indoor_has_core_sensor_fields_but_no_stryd(self):
        result = parse_fit(str(FIXTURES_DIR / "cycling_indoor.fit"))
        self.assertTrue(any(s["core_temp"] is not None for s in result["samples"]))
        self.assertTrue(any(s["skin_temp"] is not None for s in result["samples"]))
        self.assertTrue(any(s["heat_strain"] is not None for s in result["samples"]))
        self.assertTrue(all(s["air_temp"] is None for s in result["samples"]))
        self.assertTrue(all(s["humidity"] is None for s in result["samples"]))

    def test_running_outdoor_marathon_falls_back_to_stryd_power_field(self):
        result = parse_fit(str(FIXTURES_DIR / "running_outdoor_marathon.fit"))
        self.assertEqual(result["sport"], "run")
        self.assertEqual(result["environment"], "outdoor")
        self.assertTrue(result["has_gps"])
        self.assertEqual(len(result["samples"]), 12241)
        self.assertTrue(all(s["power"] is not None for s in result["samples"]))

    def test_running_outdoor_marathon_has_stryd_env_fields_but_no_core(self):
        result = parse_fit(str(FIXTURES_DIR / "running_outdoor_marathon.fit"))
        self.assertTrue(all(s["air_temp"] is not None for s in result["samples"]))
        self.assertTrue(all(s["humidity"] is not None for s in result["samples"]))
        self.assertTrue(all(s["core_temp"] is None for s in result["samples"]))
        self.assertTrue(all(s["skin_temp"] is None for s in result["samples"]))
        self.assertTrue(all(s["heat_strain"] is None for s in result["samples"]))

    def test_running_treadmill_falls_back_to_stryd_power_field(self):
        result = parse_fit(str(FIXTURES_DIR / "running_treadmill.fit"))
        self.assertEqual(result["sport"], "run")
        self.assertEqual(result["environment"], "indoor")
        self.assertFalse(result["has_gps"])
        self.assertEqual(len(result["samples"]), 5299)
        self.assertTrue(all(s["power"] is not None for s in result["samples"]))

    def test_running_treadmill_has_both_stryd_and_core_fields(self):
        result = parse_fit(str(FIXTURES_DIR / "running_treadmill.fit"))
        self.assertTrue(all(s["air_temp"] is not None for s in result["samples"]))
        self.assertTrue(all(s["humidity"] is not None for s in result["samples"]))
        self.assertTrue(any(s["core_temp"] is not None for s in result["samples"]))
        self.assertTrue(any(s["skin_temp"] is not None for s in result["samples"]))
        self.assertTrue(any(s["heat_strain"] is not None for s in result["samples"]))
