import json
from dataclasses import replace
from pathlib import Path

from django.test import SimpleTestCase

from courses.sections import (
    CLIMB,
    DEFAULT_SETTINGS,
    ELEVATION_QUALITY_MISSING,
    ELEVATION_QUALITY_OK,
    ELEVATION_QUALITY_SPARSE,
    FLAT,
    SectionSettings,
    detect_sections,
)

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures" / "sections"
JAVA_FIXTURES = Path(__file__).resolve().parents[3] / "backend_java" / "src" / "test" / "resources" / "section-fixtures"
ALGORITHMS = ("douglas_peucker", "bottom_up")


def _profile(name: str) -> tuple[list[float], list[float]]:
    data = json.loads((FIXTURES / f"{name}.json").read_text())
    return data["distance_km"], data["elevation_m"]


def _sections(name: str, **overrides) -> list[dict]:
    distances, elevations = _profile(name)
    return detect_sections(distances, elevations, SectionSettings(**overrides)).sections


def _ramp(segments: list[tuple[float, float]], step_m: float = 10.0) -> tuple[list[float], list[float]]:
    """A synthetic profile from (length_m, grade_pct) pieces, starting at 100m."""
    distances, elevations = [0.0], [100.0]
    for length_m, grade_pct in segments:
        for _ in range(int(length_m / step_m)):
            distances.append(distances[-1] + step_m / 1000.0)
            elevations.append(elevations[-1] + step_m * grade_pct / 100.0)
    return distances, elevations


class GoldenFixtureTests(SimpleTestCase):
    """expected.json is what backend_java's CourseSectionsTest also checks against - a
    failure here after a deliberate change means regenerate.py, and the same change in
    CourseSections.java."""

    def test_every_case_matches_the_golden_output(self):
        cases = json.loads((FIXTURES / "expected.json").read_text())["cases"]
        self.assertGreaterEqual(len(cases), 10)
        for case in cases:
            with self.subTest(profile=case["profile"], settings=case["settings"]):
                distances, elevations = _profile(case["profile"])
                result = detect_sections(distances, elevations, SectionSettings(**case["settings"]))
                self.assertEqual(result.elevation_quality, case["elevation_quality"])
                self.assertEqual(result.sections, case["sections"])

    def test_java_copy_of_the_fixtures_is_identical(self):
        if not JAVA_FIXTURES.exists():
            self.skipTest("backend_java not checked out alongside backend")
        for path in FIXTURES.glob("*.json"):
            with self.subTest(file=path.name):
                self.assertEqual((JAVA_FIXTURES / path.name).read_bytes(), path.read_bytes())


class CourseFindingsTests(SimpleTestCase):
    def test_mount_roosevelt_is_a_climb_on_chicago(self):
        for algorithm in ALGORITHMS:
            with self.subTest(algorithm=algorithm):
                finish = [s for s in _sections("chicago", algorithm=algorithm) if s["start_km"] >= 42.2]
                self.assertIn(CLIMB, [s["type"] for s in finish])

    def test_chicago_is_at_least_90_percent_flat(self):
        for algorithm in ALGORITHMS:
            with self.subTest(algorithm=algorithm):
                sections = _sections("chicago", algorithm=algorithm)
                total = sum(s["length_m"] for s in sections)
                flat = sum(s["length_m"] for s in sections if s["type"] == FLAT)
                self.assertGreaterEqual(flat / total, 0.90)

    def test_yorkshire_finish_hill_is_found_on_the_run_and_the_route(self):
        for profile in ("yorkshire", "yorkshire_route"):
            for algorithm in ALGORITHMS:
                with self.subTest(profile=profile, algorithm=algorithm):
                    hills = [
                        s
                        for s in _sections(profile, algorithm=algorithm)
                        if "climb" in s["type"] and s["start_km"] < 41.8 and s["end_km"] > 41.6
                    ]
                    self.assertEqual(len(hills), 1)
                    self.assertGreaterEqual(hills[0]["avg_grade_pct"], 2.5)

    def test_sections_cover_the_whole_track_without_gaps(self):
        for profile in ("chicago", "leeds", "yorkshire", "yorkshire_route", "brighton"):
            for algorithm in ALGORITHMS:
                with self.subTest(profile=profile, algorithm=algorithm):
                    sections = _sections(profile, algorithm=algorithm)
                    self.assertEqual(sections[0]["start_km"], 0.0)
                    for prev, cur in zip(sections, sections[1:], strict=False):
                        self.assertEqual(cur["start_km"], prev["end_km"])
                        self.assertNotEqual(cur["type"], prev["type"])


class SyntheticProfileTests(SimpleTestCase):
    def test_flat_climb_flat(self):
        distances, elevations = _ramp([(3000, 0.0), (1000, 3.0), (3000, 0.0)])
        for algorithm in ALGORITHMS:
            with self.subTest(algorithm=algorithm):
                sections = detect_sections(
                    distances, elevations, replace(DEFAULT_SETTINGS, algorithm=algorithm)
                ).sections
                self.assertEqual([s["type"] for s in sections], [FLAT, CLIMB, FLAT])
                self.assertAlmostEqual(sections[1]["avg_grade_pct"], 3.0, delta=0.3)
                self.assertAlmostEqual(sections[1]["start_km"], 3.0, delta=0.1)

    def test_short_sharp_hill_survives_only_above_keep_min_rise(self):
        # 120m at +6% = a ~7m hill; smoothing widens it to ~220m, still under the 250m
        # minimum section.
        distances, elevations = _ramp([(2000, 0.0), (120, 6.0), (2000, 0.0)])
        kept = detect_sections(distances, elevations, DEFAULT_SETTINGS).sections
        self.assertTrue(any("climb" in s["type"] for s in kept))
        folded = detect_sections(distances, elevations, replace(DEFAULT_SETTINGS, keep_min_rise_m=10.0)).sections
        self.assertFalse(any("climb" in s["type"] for s in folded))


class ElevationQualityTests(SimpleTestCase):
    def test_no_elevation_is_missing(self):
        distances = [i * 0.01 for i in range(500)]
        result = detect_sections(distances, [None] * 500)
        self.assertEqual(result.elevation_quality, ELEVATION_QUALITY_MISSING)
        self.assertEqual(result.sections, [])

    def test_all_zero_elevation_is_missing(self):
        distances = [i * 0.01 for i in range(500)]
        self.assertEqual(detect_sections(distances, [0.0] * 500).elevation_quality, ELEVATION_QUALITY_MISSING)

    def test_points_far_apart_are_sparse(self):
        distances, elevations = _ramp([(3000, 0.0), (1000, 3.0), (3000, 0.0)], step_m=250.0)
        result = detect_sections(distances, elevations)
        self.assertEqual(result.elevation_quality, ELEVATION_QUALITY_SPARSE)
        self.assertEqual(result.sections, [])

    def test_points_without_elevation_are_skipped(self):
        distances, elevations = _ramp([(3000, 0.0), (1000, 3.0), (3000, 0.0)])
        with_gaps = [None if i % 3 == 0 else e for i, e in enumerate(elevations)]
        result = detect_sections(distances, with_gaps)
        self.assertEqual(result.elevation_quality, ELEVATION_QUALITY_OK)
        self.assertEqual([s["type"] for s in result.sections], [FLAT, CLIMB, FLAT])


class SettingsValidationTests(SimpleTestCase):
    def test_defaults_are_valid(self):
        DEFAULT_SETTINGS.validate()

    def test_unknown_algorithm_is_rejected(self):
        with self.assertRaisesMessage(ValueError, "algorithm"):
            SectionSettings(algorithm="kmeans").validate()

    def test_out_of_range_value_is_rejected(self):
        with self.assertRaisesMessage(ValueError, "fit_tolerance_m"):
            SectionSettings(fit_tolerance_m=0.1).validate()

    def test_steep_threshold_must_exceed_flat_band(self):
        with self.assertRaisesMessage(ValueError, "steep_min_pct"):
            SectionSettings(flat_max_pct=3.0, steep_min_pct=2.5).validate()
