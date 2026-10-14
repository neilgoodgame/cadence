"""Regenerates expected.json (the golden sections both backends must reproduce) from the
profiles in this directory, and copies every fixture to backend_java's test resources.

Run from backend/: `uv run python courses/fixtures/sections/regenerate.py`. Only rerun it
when courses/sections.py deliberately changes output, and port the same change to
backend_java's CourseSections in the same PR - CourseSectionsTest reads the copied files.
"""

import json
import shutil
import sys
from dataclasses import asdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[2]))

from courses.sections import (  # noqa: E402
    SectionSettings,
    build_elevation_series,
    detect_sections,
    profile_from_series,
)

JAVA_DIR = HERE.parents[3] / "backend_java" / "src" / "test" / "resources" / "section-fixtures"

PROFILES = ["chicago", "leeds", "yorkshire", "yorkshire_route", "brighton"]
NON_DEFAULT = [
    ("chicago", {"algorithm": "douglas_peucker", "flat_max_pct": 0.5}),
    ("leeds", {"algorithm": "bottom_up", "fit_tolerance_m": 3.0, "min_section_m": 400.0, "keep_min_rise_m": 8.0}),
    (
        "brighton",
        {"algorithm": "douglas_peucker", "resample_step_m": 20.0, "smoothing_window_m": 200.0, "spike_window_m": 0.0},
    ),
    ("yorkshire_route", {"algorithm": "douglas_peucker", "steep_min_pct": 3.0}),
]


def main() -> None:
    cases = []
    runs = [(p, {"algorithm": a}) for p in PROFILES for a in ("douglas_peucker", "bottom_up")] + NON_DEFAULT
    for profile, overrides in runs:
        data = json.loads((HERE / f"{profile}.json").read_text())
        settings = SectionSettings(**overrides)
        result = detect_sections(data["distance_km"], data["elevation_m"], settings)
        cases.append(
            {
                "profile": profile,
                "settings": asdict(settings),
                "elevation_quality": result.elevation_quality,
                "sections": result.sections,
            }
        )
    profiles = []
    for profile, overrides in [(p, {}) for p in PROFILES] + [NON_DEFAULT[2]]:
        data = json.loads((HERE / f"{profile}.json").read_text())
        settings = SectionSettings(**overrides)
        _, series = build_elevation_series(data["distance_km"], data["elevation_m"])
        profiles.append(
            {"profile": profile, "settings": asdict(settings), "points": profile_from_series(series, settings)}
        )
    (HERE / "expected.json").write_text(json.dumps({"cases": cases, "profiles": profiles}, indent=1) + "\n")
    JAVA_DIR.mkdir(parents=True, exist_ok=True)
    for name in [f"{p}.json" for p in PROFILES] + ["expected.json"]:
        shutil.copyfile(HERE / name, JAVA_DIR / name)
    print(f"{len(cases)} cases and {len(profiles)} profiles written; copied to {JAVA_DIR}")


if __name__ == "__main__":
    main()
