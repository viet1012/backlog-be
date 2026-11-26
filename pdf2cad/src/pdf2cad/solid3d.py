"""Mô hình 3D chi tiết tròn xoay từ bộ tham số mẫu (cho tab 3D trên web).

Trả về MẶT CẮT NỬA chi tiết theo trục x (0 = đầu trái, L = đầu phải), bán kính r >= 0:
  outer: [[x, r], ...]  biên ngoài từ x=0 tới x=L
  inner: [[x, r], ...]  biên lỗ trong từ x=0 tới x=L (rỗng = đặc); r=0 ở đoạn không có lỗ
  cross: [{"x", "d"}]    lỗ ngang xuyên tâm (hiển thị dạng dấu, chưa khoét trong 3D)
Trình duyệt quay mặt cắt quanh trục để dựng khối (WebGL), không cần thư viện CAD.

Nguồn biên dạng:
  - mẫu "generator" (trụ / trụ có mũ / lỗ suốt / vát mép): giống hệt gen.py vẽ 2D;
  - mẫu khác: khối "solid" trong template.json, toạ độ là biểu thức theo tham số, vd
      "solid": {"outer": [["0","H/2"], ["5","H/2"], ...], "inner": [...], "cross": [{"x":"L-S-1","d":"d3"}]}
"""
from __future__ import annotations

from .template import Template


def _ev(expr, vals: dict) -> float:
    if isinstance(expr, (int, float)):
        return float(expr)
    num = {k: float(v) for k, v in vals.items() if isinstance(v, (int, float))}
    return float(eval(str(expr), {"__builtins__": {}}, num))


def _clean(pts: list) -> list:
    """Bỏ điểm trùng liên tiếp, kẹp r >= 0, làm tròn 4 chữ số."""
    out = []
    for x, r in pts:
        p = [round(float(x), 4), round(max(float(r), 0.0), 4)]
        if not out or out[-1] != p:
            out.append(p)
    return out


def _generator_profile(t: Template, vals: dict) -> dict:
    g = t.spec["generator"]
    L = float(vals["L"])
    R = float(vals[g.get("D", "D")]) / 2
    d = float(vals[g["bore"]]) if g.get("bore") and g["bore"] in vals else 0.0
    C = float(vals[g["chamfer"]]) if g.get("chamfer") and g["chamfer"] in vals else 0.0
    bc = float(g.get("bore_chamfer", 0.0)) if d else 0.0
    head = g.get("head")
    if head and head.get("D") in vals and head.get("L") in vals:
        RH, HT = float(vals[head["D"]]) / 2, float(vals[head["L"]])
        outer = [(0, RH), (HT, RH), (HT, R), (L, R)]
    elif C > 0:
        outer = [(0, R - C), (C, R), (L - C, R), (L, R - C)]
    else:
        outer = [(0, R), (L, R)]
    r = d / 2
    inner = [(0, r + bc), (bc, r), (L - bc, r), (L, r + bc)] if d else []
    return {"outer": _clean(outer), "inner": _clean(inner), "cross": []}


def profile(t: Template, vals: dict) -> dict | None:
    """Mặt cắt nửa chi tiết, hoặc None nếu mẫu chưa có mô tả khối 3D."""
    if t.spec.get("kind") == "generator" and t.spec.get("generator", {}).get("type", "cylinder") == "cylinder":
        prof = _generator_profile(t, vals)
    elif t.spec.get("solid"):
        sp = t.spec["solid"]
        prof = {"outer": _clean([(_ev(x, vals), _ev(r, vals)) for x, r in sp["outer"]]),
                "inner": _clean([(_ev(x, vals), _ev(r, vals)) for x, r in sp.get("inner", [])]),
                "cross": [{"x": round(_ev(c["x"], vals), 4), "d": round(_ev(c["d"], vals), 4)}
                          for c in sp.get("cross", [])]}
    else:
        return None
    o = prof["outer"]
    prof["L"] = max(p[0] for p in o) - min(p[0] for p in o)
    prof["Dmax"] = 2 * max(p[1] for p in o)
    # bán kính lỗ không được lớn hơn biên ngoài tại cùng vị trí (tham số sai -> báo, không vẽ khối lỗi)
    import bisect
    xs = [p[0] for p in o]
    prof["warnings"] = []
    for x, r in prof["inner"]:
        i = min(max(bisect.bisect_left(xs, x), 1), len(xs) - 1)
        if r >= max(o[i - 1][1], o[i][1]):
            prof["warnings"].append(f"inner r={r} >= outer at x={x}")
            break
    return prof
