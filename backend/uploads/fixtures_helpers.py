"""Small in-memory GPX/TCX builders + a zip helper, shared by uploads/tests.py.

Named to avoid pytest's test-file globs (test_*.py / *_tests.py / tests.py) so
it's imported as plain support code, never collected as a test module itself.
"""

import io
import zipfile
from datetime import timedelta

GPX_HEADER = (
    '<?xml version="1.0" encoding="UTF-8"?>'
    '<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1" '
    'xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">'
)


def build_gpx(start, sport="running", duration_s=60, hr=150, lat0=37.0, lon0=-122.0):
    parts = [GPX_HEADER, f"<trk><type>{sport}</type><trkseg>"]
    for i in range(duration_s):
        t = start + timedelta(seconds=i)
        lat = lat0 + i * 0.0001
        parts.append(
            f'<trkpt lat="{lat:.6f}" lon="{lon0:.6f}">'
            f"<ele>10</ele><time>{t.strftime('%Y-%m-%dT%H:%M:%SZ')}</time>"
            f"<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>{hr}</gpxtpx:hr>"
            "</gpxtpx:TrackPointExtension></extensions></trkpt>"
        )
    parts.append("</trkseg></trk></gpx>")
    return "".join(parts).encode("utf-8")


def build_tcx(start, sport="Biking", duration_s=300, power=200, hr=140, distance_m=1500):
    points = []
    for i in range(duration_s):
        t = start + timedelta(seconds=i)
        dist = distance_m * (i + 1) / duration_s
        points.append(
            f"<Trackpoint><Time>{t.strftime('%Y-%m-%dT%H:%M:%SZ')}</Time>"
            f"<DistanceMeters>{dist:.2f}</DistanceMeters>"
            f"<HeartRateBpm><Value>{hr}</Value></HeartRateBpm>"
            '<Extensions><ns3:TPX xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2">'
            f"<ns3:Watts>{power}</ns3:Watts></ns3:TPX></Extensions></Trackpoint>"
        )
    body = "".join(points)
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">'
        "<Activities>"
        f'<Activity Sport="{sport}">'
        f'<Lap StartTime="{start.strftime("%Y-%m-%dT%H:%M:%SZ")}">'
        f"<TotalTimeSeconds>{duration_s}</TotalTimeSeconds>"
        f"<DistanceMeters>{distance_m}</DistanceMeters>"
        f"<AverageHeartRateBpm><Value>{hr}</Value></AverageHeartRateBpm>"
        f"<Track>{body}</Track>"
        "</Lap></Activity></Activities></TrainingCenterDatabase>"
    ).encode("utf-8")


def build_zip(named_contents):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, content in named_contents.items():
            zf.writestr(name, content)
    return buf.getvalue()
