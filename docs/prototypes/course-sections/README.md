# Course sections prototype

How the course-section settings in `backend/courses/sections.py` (and `backend_java`'s
`CourseSections`) were chosen. Superseded by those implementations; kept as the record.

The elevation profiles live with the tests in `backend/courses/fixtures/sections/`.

| File | What it is |
| --- | --- |
| `prototype_segment.py` | The original numpy prototype: both algorithms, the tidy pass, per-section stats. |
| `veloviewer_replica.py` | VeloViewer's route gradient processing, reimplemented for comparison only. |
| `*-sections.html` | The review pages for Leeds, Chicago, Yorkshire and Brighton. Open in a browser. The "VV" strip is the VeloViewer replica. |

## Settings and why

| Setting | Value | Why |
| --- | --- | --- |
| Flat band | ±1% | Chosen after reviewing Leeds; ±1.5% hid gentle climbs. |
| Steep threshold | ±4% | Unchanged from the first prototype. |
| Fit tolerance | 2 m | At 3 m, bottom-up merged Chicago's Mount Roosevelt (+6 m) with the drop to the finish. |
| Minimum section length | 250 m | Folds GPS wiggles into neighbours. |
| Keep short sections rising or dropping at least | 5 m | Without it the 250 m rule swallowed Mount Roosevelt (200 m long). |
| Spike removal / smoothing | 30 m median, 100 m mean | Unchanged from the first prototype. |

## Findings

- Both algorithms find the hills runners remember on all four courses. Douglas–Peucker
  gives a few more sections; bottom-up merge gives slightly longer ones.
- On Yorkshire, the athlete's 2023 watch elevation and the public route's elevation found
  the same hills. The watch underestimated the short finish hill (+2.6% against +4.5%).
- VeloViewer doesn't classify sections: it colours each ~150 m piece on a continuous scale.
  With flat taken as ±1%, its method agreed with this prototype on 80–89% of the distance.
- Not captured: long gentle swings such as Brighton km 2–10 (±0.6% over 1.5–5 km). Tighter
  tolerances and counting ups and downs per km didn't separate them from sensor noise.
