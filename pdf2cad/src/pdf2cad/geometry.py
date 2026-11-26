"""Primitive hình học và các thuật toán xử lý.

Quy ước toạ độ: đơn vị point PDF (1/72 inch), trục Y hướng LÊN (đã lật so với PDF).
Việc đổi sang mm và nhân tỷ lệ bản vẽ được làm ở bước ghi DXF.
"""
from __future__ import annotations

import math
from collections import defaultdict
from dataclasses import dataclass, field

Point = tuple[float, float]


@dataclass(frozen=True)
class Style:
    color: tuple[int, int, int] | None = None   # RGB 0-255
    width: float = 0.0                          # độ dày nét (pt)
    dashes: tuple[float, ...] = ()              # mẫu nét đứt, rỗng = nét liền
    layer: str = "0"


@dataclass
class Line:
    p1: Point
    p2: Point
    style: Style


@dataclass
class Arc:
    center: Point
    radius: float
    start: float  # độ, ngược chiều kim đồng hồ (CCW)
    end: float
    style: Style

    @property
    def sweep(self) -> float:
        return (self.end - self.start) % 360.0 or 360.0


@dataclass
class Circle:
    center: Point
    radius: float
    style: Style


@dataclass
class Polyline:
    points: list[Point]
    closed: bool
    style: Style
    filled: bool = False


@dataclass
class Text:
    insert: Point      # điểm baseline bên trái
    text: str
    height: float
    rotation: float    # độ
    style: Style
    width_factor: float = 1.0


@dataclass
class PageGeometry:
    width: float
    height: float
    entities: list = field(default_factory=list)
    texts: list[Text] = field(default_factory=list)

    def counts(self) -> dict[str, int]:
        c: dict[str, int] = defaultdict(int)
        for e in self.entities:
            c[type(e).__name__] += 1
        c["Text"] = len(self.texts)
        return dict(c)


# ---------------------------------------------------------------- Bezier

def bezier_point(p0: Point, p1: Point, p2: Point, p3: Point, t: float) -> Point:
    u = 1.0 - t
    a, b, c, d = u * u * u, 3 * u * u * t, 3 * u * t * t, t * t * t
    return (a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0],
            a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1])


def flatten_bezier(p0, p1, p2, p3, segments: int = 8) -> list[Point]:
    return [bezier_point(p0, p1, p2, p3, i / segments) for i in range(segments + 1)]


def circle_from_3pts(a: Point, b: Point, c: Point) -> tuple[Point, float] | None:
    d = 2 * (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]))
    if abs(d) < 1e-9:
        return None  # 3 điểm thẳng hàng
    a2, b2, c2 = a[0] ** 2 + a[1] ** 2, b[0] ** 2 + b[1] ** 2, c[0] ** 2 + c[1] ** 2
    ux = (a2 * (b[1] - c[1]) + b2 * (c[1] - a[1]) + c2 * (a[1] - b[1])) / d
    uy = (a2 * (c[0] - b[0]) + b2 * (a[0] - c[0]) + c2 * (b[0] - a[0])) / d
    return (ux, uy), math.dist((ux, uy), a)


def _angle(center: Point, p: Point) -> float:
    a = math.degrees(math.atan2(p[1] - center[1], p[0] - center[0])) % 360.0
    return 0.0 if a > 359.999999 else a  # tránh -1e-15 % 360 = 360


def bezier_to_arc(p0, p1, p2, p3, style: Style, rel_tol: float = 0.01,
                  abs_tol: float = 0.05, max_radius: float = 1e5) -> Arc | None:
    """Thử khớp 1 đoạn Bezier bậc 3 thành cung tròn. Trả về None nếu không khớp."""
    mid = bezier_point(p0, p1, p2, p3, 0.5)
    fit = circle_from_3pts(p0, mid, p3)
    if fit is None:
        return None
    center, r = fit
    if r > max_radius or r < 1e-6:
        return None
    tol = max(rel_tol * r, abs_tol)
    for t in (0.125, 0.25, 0.375, 0.625, 0.75, 0.875):
        if abs(math.dist(center, bezier_point(p0, p1, p2, p3, t)) - r) > tol:
            return None
    # Chiều quay: tích có hướng (p0->mid) x (mid->p3) > 0 => CCW
    cross = (mid[0] - p0[0]) * (p3[1] - mid[1]) - (mid[1] - p0[1]) * (p3[0] - mid[0])
    a0, a3 = _angle(center, p0), _angle(center, p3)
    if cross >= 0:
        return Arc(center, r, a0, a3, style)
    return Arc(center, r, a3, a0, style)


# ---------------------------------------------------------------- Hậu xử lý

def merge_arcs(entities: list, pos_tol: float = 0.05, ang_tol: float = 0.5) -> list:
    """Gộp các cung cùng tâm/bán kính/style nối tiếp nhau; đủ 360° thì thành Circle.

    PDF vẽ một đường tròn bằng 4 đoạn Bezier, nên bước này rất quan trọng.
    """
    others, groups = [], defaultdict(list)
    q = lambda v: round(v / pos_tol)
    for e in entities:
        if isinstance(e, Arc):
            groups[(q(e.center[0]), q(e.center[1]), q(e.radius), e.style)].append(e)
        else:
            others.append(e)

    out = others
    for arcs in groups.values():
        arcs.sort(key=lambda a: a.start)
        remaining = arcs[:]
        while remaining:
            cur = remaining.pop(0)
            start, end, total = cur.start, cur.end, cur.sweep
            extended = True
            while extended and total < 360.0 - ang_tol:
                extended = False
                for a in remaining:
                    if _ang_close(a.start, end, ang_tol):
                        end = a.end
                    elif _ang_close(a.end, start, ang_tol):
                        start = a.start
                    else:
                        continue
                    total += a.sweep
                    remaining.remove(a)
                    extended = True
                    break
            if total >= 360.0 - ang_tol:
                out.append(Circle(cur.center, cur.radius, cur.style))
            else:
                out.append(Arc(cur.center, cur.radius, start, end, cur.style))
    return out


def _ang_close(a: float, b: float, tol: float) -> bool:
    d = abs((a - b) % 360.0)
    return min(d, 360.0 - d) <= tol


def dedupe_lines(entities: list, tol: float = 0.01) -> list:
    """Bỏ các đoạn thẳng trùng nhau (PDF hay vẽ chồng nhiều lần)."""
    seen, out = set(), []
    q = lambda p: (round(p[0] / tol), round(p[1] / tol))
    for e in entities:
        if isinstance(e, Line):
            if math.dist(e.p1, e.p2) < tol:
                continue  # đoạn suy biến
            k = tuple(sorted((q(e.p1), q(e.p2)))) + (e.style,)
            if k in seen:
                continue
            seen.add(k)
        out.append(e)
    return out


def merge_collinear(lines: list[Line], angle_tol: float = 1.5, offset_tol: float = 1.0,
                    gap_tol: float = 1.0) -> list[Line]:
    """Gộp các đoạn thẳng gần song song & gần trùng nhau thành 1 đường (dùng cho ảnh scan).

    - Gộp 2 cạnh của một nét vẽ dày thành 1 đường giữa (offset <= offset_tol).
    - Nối các đoạn bị đứt trên cùng một đường (khe hở <= gap_tol).
    Đơn vị: point. Độ phức tạp O(n log n) nhờ chia bucket theo góc rồi sort theo rho.
    """
    buckets: dict[int, list] = defaultdict(list)
    for ln in lines:
        (x1, y1), (x2, y2) = ln.p1, ln.p2
        th = math.degrees(math.atan2(y2 - y1, x2 - x1)) % 180.0
        if th > 180.0 - angle_tol / 2:
            th -= 180.0                         # gần ngang: gom về quanh 0°
        buckets[round(th / angle_tol)].append((th, ln))

    out: list[Line] = []
    for items in buckets.values():
        th = sum(t for t, _ in items) / len(items)
        dx, dy = math.cos(math.radians(th)), math.sin(math.radians(th))
        nx, ny = -dy, dx
        rows = []
        for _, ln in items:
            rho = (ln.p1[0] + ln.p2[0]) / 2 * nx + (ln.p1[1] + ln.p2[1]) / 2 * ny
            t1 = ln.p1[0] * dx + ln.p1[1] * dy
            t2 = ln.p2[0] * dx + ln.p2[1] * dy
            rows.append((rho, min(t1, t2), max(t1, t2), ln.style))
        rows.sort(key=lambda r: r[0])

        # Gom theo rho (khoảng cách vuông góc)
        groups, cur = [], [rows[0]]
        for r in rows[1:]:
            if r[0] - cur[-1][0] <= offset_tol:
                cur.append(r)
            else:
                groups.append(cur)
                cur = [r]
        groups.append(cur)

        for g in groups:
            g.sort(key=lambda r: r[1])
            spans, a, b, members = [], g[0][1], g[0][2], [g[0]]
            for r in g[1:]:
                if r[1] <= b + gap_tol:
                    b = max(b, r[2])
                    members.append(r)
                else:
                    spans.append((a, b, members))
                    a, b, members = r[1], r[2], [r]
            spans.append((a, b, members))
            for a, b, mem in spans:
                w = sum(m[2] - m[1] for m in mem) or 1.0
                rho = sum(m[0] * (m[2] - m[1]) for m in mem) / w
                p1 = (a * dx + rho * nx, a * dy + rho * ny)
                p2 = (b * dx + rho * nx, b * dy + rho * ny)
                out.append(Line(p1, p2, mem[0][3]))
    return out


# ---------------------------------------------------------------- xoay cả trang
def rotate_page(geo: PageGeometry, quarter_turns: int) -> PageGeometry:
    """Xoay toàn bộ hình học trang ngược chiều kim đồng hồ quarter_turns × 90°,
    rồi tịnh tiến để gốc toạ độ vẫn ở góc dưới-trái (khổ giấy đổi chiều nếu xoay 90/270)."""
    k = quarter_turns % 4
    if k == 0:
        return geo
    W, H = geo.width, geo.height
    if k == 1:
        f = lambda p: (H - p[1], p[0])
        geo.width, geo.height = H, W
    elif k == 2:
        f = lambda p: (W - p[0], H - p[1])
    else:
        f = lambda p: (p[1], W - p[0])
        geo.width, geo.height = H, W
    da = 90.0 * k
    for e in geo.entities:
        if isinstance(e, Line):
            e.p1, e.p2 = f(e.p1), f(e.p2)
        elif isinstance(e, (Arc, Circle)):
            e.center = f(e.center)
            if isinstance(e, Arc):
                e.start, e.end = (e.start + da) % 360.0, (e.end + da) % 360.0
        elif isinstance(e, Polyline):
            e.points = [f(p) for p in e.points]
    for t in geo.texts:
        t.insert = f(t.insert)
        t.rotation = (t.rotation + da) % 360.0
    return geo


def dominant_text_turns(texts: list[Text], min_share: float = 0.6) -> int:
    """Số lần xoay 90° (CCW) để phần lớn chữ (tính theo số ký tự) nằm ngang đọc trái->phải.
    Trả 0 nếu không có hướng nào chiếm >= min_share."""
    w = [0.0] * 4
    for t in texts:
        q = int(round(t.rotation / 90.0)) % 4
        if abs(((t.rotation - q * 90.0) + 180) % 360 - 180) <= 10:
            w[q] += len(t.text)
    total = sum(w)
    if not total:
        return 0
    q = max(range(4), key=lambda i: w[i])
    return (-q) % 4 if w[q] / total >= min_share else 0
