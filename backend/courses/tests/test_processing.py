from pathlib import Path

from django.test import SimpleTestCase

from courses.processing import (
    GpxProcessingError,
    TrackPoint,
    _detect_climbs,
    _gain_loss,
    _is_loop,
    _simplify_route,
    _smoothed_elevations,
    parse_gpx_track,
    process_track_points,
)

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures"


def _flat_then_hill_then_flat(n: int = 300, hill_gain_m: float = 50.0) -> list[TrackPoint]:
    """A straight line (so Douglas-Peucker has something to simplify) with one clear
    climb in the middle: flat for the first/last third, a steady climb through the
    middle third."""
    points = []
    for i in range(n):
        distance_km = i * 0.01  # 10m steps
        third = n // 3
        if i < third:
            elevation = 100.0
        elif i < 2 * third:
            elevation = 100.0 + hill_gain_m * (i - third) / third
        else:
            elevation = 100.0 + hill_gain_m
        points.append(TrackPoint(lat=51.5 + i * 0.0001, lng=-0.1, elevation=elevation, distance_km=distance_km))
    return points


class SmoothedElevationsTests(SimpleTestCase):
    def test_small_noise_is_smoothed_away(self):
        points = [
            TrackPoint(lat=0, lng=0, elevation=100.0 + (1.0 if i % 2 == 0 else -1.0), distance_km=i * 0.01)
            for i in range(60)
        ]
        smoothed = _smoothed_elevations(points)
        self.assertTrue(all(abs(v - 100.0) < 1.0 for v in smoothed if v is not None))


class GainLossTests(SimpleTestCase):
    def test_noise_under_threshold_does_not_accumulate(self):
        # Oscillates +/-0.5m around 100 (1m peak-to-peak) - under the 2m noise threshold,
        # so the baseline never moves and nothing accumulates.
        values = [100.0 + (0.5 if i % 2 == 0 else -0.5) for i in range(40)]
        gain, loss = _gain_loss(values)
        self.assertEqual((gain, loss), (0, 0))

    def test_real_climb_is_counted(self):
        values = [100.0, 105.0, 110.0, 108.0, 100.0]
        gain, loss = _gain_loss(values)
        self.assertEqual(gain, 10)
        self.assertEqual(loss, 10)


class ClimbDetectionTests(SimpleTestCase):
    def test_detects_one_climb_above_thresholds(self):
        points = _flat_then_hill_then_flat(n=300, hill_gain_m=50.0)
        smoothed = _smoothed_elevations(points)
        climbs = _detect_climbs(points, smoothed)
        self.assertEqual(len(climbs), 1)
        climb = climbs[0]
        self.assertGreaterEqual(climb["gain_m"], 45)
        self.assertGreaterEqual(climb["avg_grade_pct"], 1.5)

    def test_sub_threshold_bump_is_not_a_climb(self):
        points = _flat_then_hill_then_flat(n=300, hill_gain_m=5.0)  # below the 12m gain floor
        smoothed = _smoothed_elevations(points)
        climbs = _detect_climbs(points, smoothed)
        self.assertEqual(climbs, [])


class SimplifyRouteTests(SimpleTestCase):
    def test_straight_line_simplifies_to_two_points(self):
        points = [TrackPoint(lat=51.5 + i * 0.0001, lng=-0.1, elevation=None, distance_km=i * 0.01) for i in range(600)]
        simplified = _simplify_route(points, max_points=500)
        self.assertEqual(len(simplified), 2)

    def test_already_small_route_is_untouched(self):
        points = [TrackPoint(lat=51.5 + i * 0.0001, lng=-0.1, elevation=None, distance_km=i * 0.01) for i in range(10)]
        simplified = _simplify_route(points, max_points=500)
        self.assertEqual(len(simplified), 10)


class IsLoopTests(SimpleTestCase):
    def test_start_equals_finish_is_a_loop(self):
        points = [
            TrackPoint(lat=51.5, lng=-0.1, elevation=None, distance_km=0.0),
            TrackPoint(lat=51.55, lng=-0.1, elevation=None, distance_km=5.0),
            TrackPoint(lat=51.5001, lng=-0.1001, elevation=None, distance_km=10.0),
        ]
        self.assertTrue(_is_loop(points))

    def test_point_to_point_is_not_a_loop(self):
        points = [
            TrackPoint(lat=51.5, lng=-0.1, elevation=None, distance_km=0.0),
            TrackPoint(lat=51.6, lng=-0.2, elevation=None, distance_km=10.0),
        ]
        self.assertFalse(_is_loop(points))


class ParseGpxTrackTests(SimpleTestCase):
    def test_too_small_file_is_rejected(self):
        raw = (FIXTURES / "too_small.gpx").read_bytes()
        with self.assertRaises(GpxProcessingError):
            parse_gpx_track(raw)

    def test_hill_loop_fixture_parses_and_processes(self):
        raw = (FIXTURES / "hill_loop.gpx").read_bytes()
        points = parse_gpx_track(raw)
        self.assertGreater(len(points), 50)

        fields = process_track_points(points)
        self.assertTrue(fields["is_loop"])
        self.assertGreater(fields["distance_km"], 0)
        self.assertGreater(fields["elevation_gain_m"], 0)
        self.assertEqual(len(fields["climbs"]), 1)
        self.assertLessEqual(len(fields["route_polyline"]), 500)
        self.assertLessEqual(len(fields["elevation_profile"]), 201)

    def test_unparseable_bytes_raise(self):
        with self.assertRaises(GpxProcessingError):
            parse_gpx_track(b"not gpx at all")

    def test_oversized_file_is_rejected(self):
        import courses.processing as processing

        raw = b"x" * (processing.MAX_GPX_BYTES + 1)
        with self.assertRaises(GpxProcessingError):
            parse_gpx_track(raw)
