import math

from pdf2cad.geometry import (Arc, Circle, Line, Style, bezier_to_arc, circle_from_3pts,
                              merge_arcs, merge_collinear)

K = 0.5522847498  # hằng số xấp xỉ 1/4 đường tròn bằng Bezier
S = Style()


def quarter(cx, cy, r, k):
    """Cung 1/4 thứ k (CCW) dạng Bezier."""
    a0 = math.radians(90 * k)
    a1 = a0 + math.pi / 2
    p0 = (cx + r * math.cos(a0), cy + r * math.sin(a0))
    p3 = (cx + r * math.cos(a1), cy + r * math.sin(a1))
    p1 = (p0[0] - K * r * math.sin(a0), p0[1] + K * r * math.cos(a0))
    p2 = (p3[0] + K * r * math.sin(a1), p3[1] - K * r * math.cos(a1))
    return p0, p1, p2, p3


def test_circle_from_3pts():
    (cx, cy), r = circle_from_3pts((1, 0), (0, 1), (-1, 0))
    assert abs(cx) < 1e-9 and abs(cy) < 1e-9 and abs(r - 1) < 1e-9
    assert circle_from_3pts((0, 0), (1, 1), (2, 2)) is None


def test_bezier_to_arc_quarter():
    arc = bezier_to_arc(*quarter(10, 20, 5, 0), S)
    assert isinstance(arc, Arc)
    assert math.isclose(arc.radius, 5, rel_tol=1e-3)
    assert math.isclose(arc.start, 0, abs_tol=0.1) and math.isclose(arc.end, 90, abs_tol=0.1)


def test_bezier_to_arc_clockwise():
    p0, p1, p2, p3 = quarter(0, 0, 5, 0)
    arc = bezier_to_arc(p3, p2, p1, p0, S)   # đảo chiều => vẫn ra cung CCW 0..90
    assert math.isclose(arc.start, 0, abs_tol=0.1) and math.isclose(arc.end, 90, abs_tol=0.1)


def test_bezier_not_arc():
    assert bezier_to_arc((0, 0), (10, 30), (20, -30), (30, 0), S) is None


def test_merge_four_quarters_to_circle():
    arcs = [bezier_to_arc(*quarter(0, 0, 7, k), S) for k in range(4)]
    out = merge_arcs(arcs)
    assert len(out) == 1 and isinstance(out[0], Circle)
    assert math.isclose(out[0].radius, 7, rel_tol=1e-3)


def test_merge_two_quarters_to_half_arc():
    arcs = [bezier_to_arc(*quarter(0, 0, 7, k), S) for k in (0, 1)]
    out = merge_arcs(arcs)
    assert len(out) == 1 and isinstance(out[0], Arc)
    assert math.isclose(out[0].sweep, 180, abs_tol=0.2)


def test_merge_collinear_double_edge_and_gap():
    lines = [Line((0, 0.0), (50, 0.0), S), Line((0, 1.0), (50, 1.0), S),   # 2 cạnh nét dày
             Line((50.5, 0.5), (100, 0.5), S)]                              # đoạn nối tiếp
    out = merge_collinear(lines, offset_tol=1.2, gap_tol=1.0)
    assert len(out) == 1
    assert math.isclose(math.dist(out[0].p1, out[0].p2), 100, abs_tol=0.01)
    assert math.isclose(out[0].p1[1], 0.5, abs_tol=0.05)
