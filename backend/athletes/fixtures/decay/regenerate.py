"""Regenerates expected.json (the golden decay-simulation steps both backends must reproduce)
from scenarios.py, and copies it to backend_java's test resources.

Run from backend/: `uv run python athletes/fixtures/decay/regenerate.py`. Only rerun it when
threshold_decay.py deliberately changes output, and port the same change to backend_java's
ThresholdDecay in the same PR - ThresholdDecayParityTest reads the copied file.
"""

import json
import shutil
import sys
from dataclasses import asdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[2]))

from athletes.fixtures.decay.scenarios import build_scenarios  # noqa: E402
from athletes.threshold_decay import simulate  # noqa: E402

JAVA_DIR = HERE.parents[3] / "backend_java" / "src" / "test" / "resources" / "threshold-decay-fixtures"


def _date(d):
    return d.isoformat() if d is not None else None


def main() -> None:
    cases = []
    for scenario in build_scenarios():
        steps = simulate(
            scenario["field"],
            scenario["settings"],
            scenario["seed_anchor_value"],
            scenario["seed_anchor_date"],
            scenario["seed_anchor_kind"],
            scenario["candidates"],
            scenario["manual_entries"],
            scenario["load_by_day"],
            scenario["start"],
            scenario["end"],
        )
        cases.append(
            {
                "name": scenario["name"],
                "field": scenario["field"],
                "settings": asdict(scenario["settings"]),
                "seed_anchor_value": scenario["seed_anchor_value"],
                "seed_anchor_date": _date(scenario["seed_anchor_date"]),
                "seed_anchor_kind": scenario["seed_anchor_kind"],
                "candidates": [asdict(c) for c in scenario["candidates"]],
                "manual_entries": [asdict(m) for m in scenario["manual_entries"]],
                "load_by_day": {_date(d): v for d, v in scenario["load_by_day"].items()},
                "start": _date(scenario["start"]),
                "end": _date(scenario["end"]),
                "steps": [
                    {
                        "value": s.value,
                        "kind": s.kind,
                        "activity_id": s.activity_id,
                        "effective_from": _date(s.effective_from),
                        "current_from": _date(s.current_from),
                    }
                    for s in steps
                ],
            }
        )
        if len(steps) == 0:
            raise AssertionError(f"scenario {scenario['name']!r} produced no steps - check its construction")

    for case in cases:
        for c in case["candidates"]:
            c["date"] = _date(c["date"])
        for m in case["manual_entries"]:
            m["date"] = _date(m["date"])

    (HERE / "expected.json").write_text(json.dumps({"cases": cases}, indent=1) + "\n")
    JAVA_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(HERE / "expected.json", JAVA_DIR / "expected.json")
    print(f"{len(cases)} cases written; copied to {JAVA_DIR}")


if __name__ == "__main__":
    main()
