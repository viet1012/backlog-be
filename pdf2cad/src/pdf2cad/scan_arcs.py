"""Bản scan: dựng lại đường tròn / cung tròn từ các đoạn thẳng ngắn.

LSD cắt mỗi cung thành nhiều dây cung ngắn (RASTER), còn phần gần đỉnh / đáy / hai bên của đường tròn
(gần như thẳng ngang / dọc) bị bước lọc hình thái lấy thành các đoạn THIN ngắn. Ở đây:
  1) nối các đoạn đầu–cuối gần nhau, gãy góc nhỏ, cùng chiều cong thành chuỗi;
  2) chuỗi đủ cong -> fit đường tròn (bình phương tối thiểu), sai số nhỏ thì nhận là cung;
  3) gộp các cung cùng tâm / bán kính, nhặt thêm các dây cung ngang / dọc nằm trên đường tròn,
     khe hở nhỏ (≤ 30°) thì nối -> Circle nếu kín, không thì Arc.
Đơn vị: point PDF, trục Y hướng lên (giống Line sau to_cad).
"""
from __future__ import annotations

import math
from typing import Callable

import numpy as np

from .geometry import Arc, Circle, Line, Style

MM = 72.0 / 25.4


def fit_circle(pts: np.ndarray):
    """Fit đường tròn (Kasa). Trả (cx, cy, r, rms, max_err) hoặc None."""
    x, y = pts[:, 0], pts[:, 1]
    A = np.c_[x, y, np.ones_like(x)]
    sol = np.linalg.lstsq(A, -(x * x + y * y), rcond=None)[0]
    cx, cy = -sol[0] / 2, -sol[1] / 2
    r2 = cx * cx + cy * cy - sol[2]
    if r2 <= 0:
        return None
    r = math.sqrt(r2)
    res = np.abs(np.hypot(x - cx, y - cy) - r)
    return float(cx), float(cy), r, float(np.sqrt(np.mean(res ** 2))), float(res.max())


def _samples(p, q, step):
    p, q = np.asarray(p, float), np.asarray(q, float)
    n = max(2, int(np.linalg.norm(q - p) / step) + 1)
    t = np.linspace(0.0, 1.0, n)[:, None]
    return p + (q - p) * t


def _chains(segs: list[tuple[np.ndarray, np.ndarray]], gap: float, max_turn: float) -> list[list[tuple]]:
    """Nối đoạn thành chuỗi: đầu đoạn sau cách đuôi đoạn trước ≤ gap, góc gãy ≤ max_turn (độ),
    cùng chiều cong. Mỗi phần tử chuỗi = (p, q, idx) đã định hướng."""
    used = [False] * len(segs)
    ends = np.array([[*a, *b] for a, b in segs]) if segs else np.zeros((0, 4))
    out = []

    def turn(d1, d2):
        return math.degrees(math.atan2(d1[0] * d2[1] - d1[1] * d2[0], d1 @ d2))

    def grow(chain, sign):
        while True:
            p, q, _ = chain[-1]
            d = (q - p) / (np.linalg.norm(q - p) or 1)
            best = None
            for j in range(len(segs)):
                if used[j]:
                    continue
                for a, b in ((ends[j, :2], ends[j, 2:]), (ends[j, 2:], ends[j, :2])):
                    dist = float(np.hypot(*(a - q)))
                    if dist > gap:
                        continue
                    nd = (b - a) / (np.linalg.norm(b - a) or 1)
                    t = turn(d, nd)
                    if abs(t) > max_turn or (sign and t * sign < -1.0):
                        continue
                    if best is None or dist < best[0]:
                        best = (dist, j, a, b, t)
            if best is None:
                return sign
            _, j, a, b, t = best
            used[j] = True
            chain.append((a, b, j))
            if not sign and abs(t) > 1.0:
                sign = 1 if t > 0 else -1

    for i in range(len(segs)):
        if used[i]:
            continue
        used[i] = True
        fwd = [(ends[i, :2], ends[i, 2:], i)]
        sign = grow(fwd, 0)
        bwd = [(ends[i, 2:], ends[i, :2], i)]
        grow(bwd, -sign)
        chain = [(b, a, j) for a, b, j in reversed(bwd[1:])] + fwd
        out.append(chain)
    return out


def _point_seg_dist(p, a, b) -> float:
    ab = b - a
    t = float(np.clip((p - a) @ ab / max(ab @ ab, 1e-12), 0.0, 1.0))
    return float(np.linalg.norm(p - (a + ab * t)))


def _dedupe(segs: list[tuple[np.ndarray, np.ndarray]], tol: float, ang_tol: float = 25.0) -> list[int]:
    """Bỏ đoạn trùng: LSD trả 2 mép của nét dày + mảnh vụn chồng lên nhau -> giữ đoạn dài nhất
    (đoạn ngắn hơn có trung điểm cách đoạn dài <= tol và gần cùng phương thì bỏ). Trả chỉ số giữ lại."""
    lens = [float(np.linalg.norm(b - a)) for a, b in segs]
    mids = [(a + b) / 2 for a, b in segs]
    order = sorted(range(len(segs)), key=lambda i: -lens[i])
    kept: list[int] = []
    for i in order:
        a, b = segs[i]
        d = b - a
        m = mids[i]
        dup = False
        for k in kept:
            mk = mids[k]
            if abs(m[0] - mk[0]) > lens[k] / 2 + tol or abs(m[1] - mk[1]) > lens[k] / 2 + tol:
                continue                                  # lọc nhanh theo khung bao
            c, e = segs[k]
            dk = e - c
            cosang = abs(float(d @ dk)) / (np.linalg.norm(d) * np.linalg.norm(dk) + 1e-12)
            if cosang >= math.cos(math.radians(ang_tol)) and _point_seg_dist(m, c, e) <= tol:
                dup = True
                break
        if not dup:
            kept.append(i)
    return sorted(kept)


def _arc_interval(cx, cy, pts):
    """(góc đầu, độ quét) theo chiều CCW mà dãy điểm (theo thứ tự chuỗi) quét qua."""
    ang = np.degrees(np.arctan2(pts[:, 1] - cy, pts[:, 0] - cx))
    total = float(np.sum((np.diff(ang) + 180.0) % 360.0 - 180.0))
    if total >= 0:
        return float(ang[0]) % 360.0, min(360.0, total)
    return float(ang[-1]) % 360.0, min(360.0, -total)


def _union(intervals: list[tuple[float, float]], bridge: float) -> list[tuple[float, float]]:
    """Hợp các khoảng góc (start, sweep); khe ≤ bridge độ thì nối. Trả [(start, sweep)]."""
    if not intervals:
        return []
    iv = sorted((s % 360.0, w) for s, w in intervals)
    merged = [list(iv[0])]
    for s, w in iv[1:]:
        ms, mw = merged[-1]
        if s <= ms + mw + bridge:
            merged[-1][1] = max(mw, s + w - ms)
        else:
            merged.append([s, w])
    # nối vòng qua 0°
    if len(merged) > 1:
        fs, fw = merged[0]
        ls, lw = merged[-1]
        if fs + 360.0 <= ls + lw + bridge:
            merged[0] = [ls, fs + 360.0 + fw - ls]
            merged.pop()
    return [(s % 360.0, min(w, 360.0)) for s, w in merged]


def rebuild_arcs(raster: list[Line], axis: list[Line], thickness: Callable[..., float] | None,
                 thick_px: float, styles: dict[str, Style],
                 min_r_mm: float = 0.75, max_r_mm: float = 150.0,
                 centerline: Callable[..., np.ndarray] | None = None):
    """raster: đoạn xiên (LSD). axis: đoạn ngang/dọc đã phân loại (có thể là dây cung của đường tròn).
    thickness(pts_pt, center_pt) -> độ dày nét (px) dọc theo các điểm đó, để chọn OUTLINE / THIN.

    Trả (cung/đường tròn mới, raster còn lại, axis còn lại)."""
    if not raster:
        return [], raster, axis
    gap, step = 0.8 * MM, 0.3 * MM
    # đường tròn nhỏ: các cạnh gần ngang / dọc của "đa giác" bị lấy thành đoạn trục (axis) -> cho các
    # đoạn trục ngắn tham gia nối chuỗi luôn (chỉ số >= len(raster))
    short_axis = [k for k, l in enumerate(axis) if math.dist(l.p1, l.p2) <= 6.0 * MM]
    segs = [(np.array(l.p1, float), np.array(l.p2, float)) for l in raster] + \
           [(np.array(axis[k].p1, float), np.array(axis[k].p2, float)) for k in short_axis]
    n_r = len(raster)
    # 2 mép của nét đậm / mảnh vụn chồng nhau làm chuỗi đi zig-zag giữa 2 mép -> chỉ nối các đoạn đại diện;
    # đoạn trùng bị bỏ vẫn được tính là "đã dùng" nếu đoạn đại diện của nó thuộc 1 cung
    keep = _dedupe(segs, tol=0.5 * MM)
    keep_set = set(keep)
    rep_of = {}
    for i in range(len(segs)):
        if i in keep_set:
            continue
        a, b = segs[i]
        m = (a + b) / 2
        near = [k for k in keep if abs(float(np.linalg.norm((segs[k][0] + segs[k][1]) / 2 - m)))
                <= float(np.linalg.norm(segs[k][1] - segs[k][0])) / 2 + 0.5 * MM]
        best = min(near, key=lambda k: _point_seg_dist(m, *segs[k]), default=None)
        if best is not None and _point_seg_dist(m, *segs[best]) <= 0.5 * MM:
            rep_of.setdefault(best, []).append(i)
    sub = [segs[i] for i in keep]
    fits = []                                  # (cx, cy, r, [(start, sweep)], pts, seg_ids)
    # gãy góc tới 50°: đường tròn nhỏ (lỗ Ø5-8) chỉ còn 8 dây cung (45°); lục giác (60°) không nối được
    for chain0 in _chains(sub, gap, max_turn=50.0):
        chain = [(p, q, keep[j]) for p, q, j in chain0]
        pts = np.vstack([_samples(p, q, step) for p, q, _ in chain])
        L = sum(float(np.linalg.norm(q - p)) for p, q, _ in chain)
        if len(chain) < 3 or L < 1.5 * MM:
            continue
        f = fit_circle(pts)
        if f is None:
            continue
        cx, cy, r, rms, mx = f
        if not (min_r_mm * MM <= r <= max_r_mm * MM):
            continue
        # dây cung lệch khỏi cung 1 khoảng bằng độ võng (Ø5, dây 2 mm: ~0.2 mm); đa giác đều thật
        # (lục giác đai ốc) có độ võng ~0.13 r -> vượt max_err nên không bị nhận nhầm là đường tròn
        if rms > max(0.15 * MM, 0.015 * r) or mx > max(0.3 * MM, 0.05 * r):
            continue
        if all(j >= n_r for _, _, j in chain):
            continue                          # chỉ toàn đoạn ngang / dọc: không đủ bằng chứng là cung
        a0, sweep = _arc_interval(cx, cy, pts)
        if sweep < 40.0:                      # cong quá ít: có thể là đường gãy khúc
            continue
        ids = [j for _, _, j in chain]
        ids += [d for j in ids for d in rep_of.get(j, [])]
        fits.append([cx, cy, r, [(a0, sweep)], pts, ids])

    # gộp cung cùng 1 đường tròn: xét điểm của cung có nằm trên đường tròn của nhóm không (cung ngắn
    # fit riêng thì tâm / bán kính lệch nhiều, nhưng điểm của nó vẫn khớp đường tròn của cung dài);
    # vòng vát mép C0.5 đồng tâm lệch 0.5 mm nên không bị gộp nhầm
    groups: list[list] = []
    for f in sorted(fits, key=lambda f: -(f[2] * sum(w for _, w in f[3]))):
        for g in groups:
            res = np.abs(np.hypot(f[4][:, 0] - g[0], f[4][:, 1] - g[1]) - g[2])
            if float(np.sqrt(np.mean(res ** 2))) <= max(0.15 * MM, 0.02 * g[2]) and res.max() <= max(0.35 * MM, 0.05 * g[2]):
                g[3] += f[3]
                g[4] = np.vstack([g[4], f[4]])
                g[5] += f[5]
                refit = fit_circle(g[4])
                if refit is not None:
                    g[0], g[1], g[2] = refit[0], refit[1], refit[2]
                break
        else:
            groups.append(list(f))

    # LSD trả 2 mép của nét đậm -> 2 vòng đồng tâm lệch nhau ~ bề dày nét (0.25-0.4 mm): gộp lại.
    # Vòng vát mép C0.5 cách 0.5 mm vẫn giữ riêng.
    edge_tol = 0.33 * MM
    merged_groups: list[list] = []
    for g in sorted(groups, key=lambda g: -len(g[4])):
        for h in merged_groups:
            if (math.hypot(g[0] - h[0], g[1] - h[1]) <= max(0.3 * MM, 0.03 * h[2])
                    and abs(g[2] - h[2]) <= edge_tol):
                h[3] += g[3]
                h[4] = np.vstack([h[4], g[4]])
                h[5] += g[5]
                break
        else:
            merged_groups.append(g)
    groups = merged_groups

    # cung ngắn bán kính lệch nằm chồng lên 1 đường tròn lớn hơn (chuỗi lẫn mép nét / đường gióng):
    # phần lớn điểm của nó khớp đường tròn kia -> nhập phần khớp vào, bỏ cung lệch
    groups.sort(key=lambda g: -sum(w for _, w in _union(g[3], 0.0)) * g[2])
    kept: list[list] = []
    for g in groups:
        for h in kept:
            if math.hypot(g[0] - h[0], g[1] - h[1]) > max(1.5 * MM, 0.15 * h[2]):
                continue
            res = np.abs(np.hypot(g[4][:, 0] - h[0], g[4][:, 1] - h[1]) - h[2])
            ok = res <= max(0.3 * MM, 0.03 * h[2])
            # đủ 1 đoạn cung khớp (>= 1.5 mm hoặc 40% số điểm): phần còn lại thường là đường gióng /
            # mép nét bị nối nhầm vào chuỗi
            if ok.sum() >= max(5, 0.4 * len(ok)):
                pts = g[4][ok]
                if len(pts) >= 2:
                    h[3].append(_arc_interval(h[0], h[1], pts))
                    h[4] = np.vstack([h[4], pts])
                h[5] += g[5]
                break
        else:
            kept.append(g)
    groups = kept

    used_raster: set[int] = set()
    dropped_axis: set[int] = set()
    out = []
    for g in groups:
        for j in g[5]:                         # đoạn trục đã nằm trong chuỗi cung -> bỏ khỏi axis
            if j >= n_r:
                dropped_axis.add(short_axis[j - n_r])
        f = fit_circle(g[4])                   # fit lại trên toàn bộ điểm
        if f is None:
            continue
        cx, cy, r = f[0], f[1], f[2]
        tol = max(0.35 * MM, 0.03 * r)
        intervals = list(g[3])
        # dây cung ngang / dọc nằm trên đường tròn (gần đỉnh / đáy / 2 bên) -> thuộc đường tròn
        chord_pts = []
        for k, ln in enumerate(axis):
            if k in dropped_axis:
                continue
            a, b = np.array(ln.p1, float), np.array(ln.p2, float)
            length = float(np.linalg.norm(b - a))
            if length > 0.9 * r or length < 0.3 * MM:
                continue
            m = (a + b) / 2
            sag = r - math.sqrt(max(r * r - (length / 2) ** 2, 0.0))
            ok = (abs(math.dist(a, (cx, cy)) - r) <= tol and abs(math.dist(b, (cx, cy)) - r) <= tol
                  and abs(math.dist(m, (cx, cy)) - (r - sag / 2)) <= tol)
            if not ok:
                continue
            aa = math.degrees(math.atan2(a[1] - cy, a[0] - cx))
            bb = math.degrees(math.atan2(b[1] - cy, b[0] - cx))
            s, w = (aa, (bb - aa) % 360.0) if (bb - aa) % 360.0 <= 180.0 else (bb, (aa - bb) % 360.0)
            intervals.append((s, w))
            dropped_axis.add(k)
            chord_pts.append(_samples(a, b, step))
        cover = _union(intervals, bridge=30.0)
        pts_all = np.vstack([g[4]] + chord_pts)
        if centerline is not None:
            # điểm fit lấy từ mép nét (LSD) -> dời về đường tâm mực thật rồi fit lại: tâm / bán kính
            # chính xác tới phần nhỏ của 1 px thay vì lệch nửa bề dày nét
            c_pts = centerline(pts_all, (cx, cy))
            f2 = fit_circle(c_pts) if len(c_pts) >= 5 else None
            if f2 is not None and math.hypot(f2[0] - cx, f2[1] - cy) <= tol and abs(f2[2] - r) <= tol:
                cx, cy, r = f2[0], f2[1], f2[2]
        style = styles["THIN"]
        if thickness is not None and thick_px > 0:
            style = styles["OUTLINE"] if thickness(pts_all, (cx, cy)) >= thick_px else styles["THIN"]
        for s, w in cover:
            if w >= 330.0:
                out.append(Circle((cx, cy), r, style))
            elif w >= 20.0:
                out.append(Arc((cx, cy), r, s % 360.0, (s + w) % 360.0, style))
        used_raster.update(j for j in g[5] if j < n_r)

    rest_raster = [l for i, l in enumerate(raster) if i not in used_raster]
    rest_axis = [l for k, l in enumerate(axis) if k not in dropped_axis]
    return out, rest_raster, rest_axis
