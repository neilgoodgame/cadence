"""For comparison only: reimplements VeloViewer's route gradient processing, as read from the
scripts its route pages load (vv.p3d filterData, vv.266 gbd/gdd, simplify3d)."""
import json, math, sys
from collections import defaultdict

def filter_data(xs, ys, tau=30):
    n = len(xs)
    xds, yds = xs[3] - xs[0], ys[3] - ys[0]
    xde, yde = xs[-1] - xs[-3], ys[-1] - ys[-3]
    px = [xs[0] + (d - tau) * xds for d in range(tau)] + list(xs) + [xs[-1] + d * xde for d in range(tau)]
    py = [ys[0] + (d - tau) * yds for d in range(tau)] + list(ys) + [ys[-1] + d * yde for d in range(tau)]
    sm = [None] * len(px)
    ys_ = xp = yp = None
    for direction in (-1, 1):
        for i in range(len(px)):
            k = i if direction == 1 else len(px) - 1 - i
            x, y = px[k], py[k]
            if ys_ is None or xp is None or yp is None:
                ys_ = y
            else:
                u = abs(x - xp) / tau
                z = math.exp(-u)
                dy = y - yp
                if u > 0:
                    ys_ = ys_ * z + y * (1 - z) + (dy / u) * ((u + 1) * z - 1)
            yp, xp = y, x
            sm[k] = ys_ if direction == -1 else (sm[k] + ys_) / 2
    return sm[tau:len(px) - tau]

def rdp(pts, tol):
    sq = tol * tol
    n = len(pts); keep = [False] * n; keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    def segd(p, a, b):
        x, y = a; dx, dy = b[0] - x, b[1] - y
        if dx or dy:
            t = ((p[0] - x) * dx + (p[1] - y) * dy) / (dx * dx + dy * dy)
            if t > 1: x, y = b
            elif t > 0: x, y = x + dx * t, y + dy * t
        return (p[0] - x) ** 2 + (p[1] - y) ** 2
    while stack:
        f, l = stack.pop()
        m, idx = 0, None
        for i in range(f + 1, l):
            d = segd(pts[i], pts[f], pts[l])
            if d > m: m, idx = d, i
        if idx is not None and m > sq:
            keep[idx] = True; stack += [(f, idx), (idx, l)]
    return [i for i in range(n) if keep[i]]

src, out = sys.argv[1], sys.argv[2]
raw = json.load(open(src))
if 'streams' in raw:  # VeloViewer route object, distance in metres
    st = {s['type']: s['data'] for s in raw['streams']}; d, e = st['distance'], st['altitude']
    vv_gain = raw.get('elevation_gain')
else:                 # {distance (km), altitude} stream
    d = [x * 1000 for x in raw.get('distance_km', raw.get('distance'))]
    e = raw.get('elevation_m', raw.get('altitude')); vv_gain = None
keep = [0] + [i for i in range(1, len(d)) if d[i] > d[i - 1]]
d, e = [d[i] for i in keep], [e[i] for i in keep]
sm = filter_data(d, e)

# Breakdown histogram: distance per rounded whole-% gradient
hist = defaultdict(float)
for i in range(len(d) - 1):
    dd = d[i + 1] - d[i]
    if dd <= 0: continue
    g = max(-.25, min(.25, (sm[i + 1] - sm[i]) / dd))
    hist[round(100 * g)] += dd
# JS Math.round rounds .5 up; python banker's rounding differs only at exact .5 - negligible here

# Map colouring: simplify to >= 250 points, starting tolerance 10 and halving
pts = list(zip(d, sm))
tol = 10; idx = rdp(pts, tol)
while len(idx) < 250 and tol > 0.01:
    idx = rdp(pts, tol); tol /= 2
pieces = [(d[a] / 1000, d[b] / 1000, 100 * (sm[b] - sm[a]) / (d[b] - d[a]), sm[a], sm[b]) for a, b in zip(idx, idx[1:])]
json.dump({'hist': sorted(hist.items()), 'pieces': pieces, 'smoothed': sm, 'simplify_tol': tol * 2, 'n_pts': len(idx)}, open(out, 'w'))

tot = sum(hist.values())
print(f"route {d[-1]/1000:.2f} km, smoothed gain {sum(max(0, b - a) for a, b in zip(sm, sm[1:])):.0f} m (VV says {vv_gain})")
print("Breakdown (rounded %):")
for g, L in sorted(hist.items()):
    print(f"  {g:+3d}%  {L/1000:6.2f} km  {'#' * int(L / 400)}")
band = lambda lo, hi: sum(L for g, L in hist.items() if lo <= g <= hi) / 1000
print(f"VV buckets -1..+1 (true |g|<1.5%): {band(-1, 1):.1f} km;  bucket 0 only (|g|<0.5%): {band(0, 0):.1f} km")
print(f"simplified to {len(idx)} pts at tol {tol*2:.3f} m; mean piece {d[-1]/len(idx):.0f} m")
