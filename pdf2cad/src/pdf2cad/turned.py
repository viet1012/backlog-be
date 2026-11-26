"""Chi tiết tròn xoay từ bản DXF đã chuyển (PDF/scan): tìm hình chiếu đứng + trục, đọc từng đoạn
(chiều dài, Φ ngoài, Φ lỗ) -> mô hình 3D (tab 3D), và vẽ lại bản DXF 2D theo kích thước đã chỉnh.

Không cần mẫu: dùng cho bản vẽ trục, punch, pillar, bạc... (đối xứng trục). Cách tìm trục:
  - ghép các nét ngang thẳng hàng; bỏ khung giấy;
  - mỗi cặp nét bao (OUTLINE) ngang đối xứng nhau là 1 ứng viên trục y0 = (y_trên + y_dưới)/2;
  - chấm điểm theo chiều dài phần chồng nhau, cộng thêm nếu giữa cặp có gạch mặt cắt 45°, trừ nặng nếu
    có nét đứng cắt ngang trục ở giữa (đặc trưng hàng của bảng / khung tên, chi tiết thì không có).
Bán kính ngoài = cặp nét bao xa trục nhất ở mỗi đoạn; lỗ = nét ngang (bao / khuất) gần trục bên trong.
Kích thước đo trên hình: bản vẽ tỷ lệ FREE thì chỉ đúng tỷ lệ hình dạng -> người dùng chỉnh lại ở tab 3D.
"""
from __future__ import annotations

import math
from pathlib import Path

H_TOL = 0.3          # mm: lệch y tối đa để coi là nét ngang / thẳng hàng


def _segments(msp) -> list[tuple[str, float, float, float, float]]:
    segs = []
    for e in msp:
        t = e.dxftype()
        if t == "LINE":
            a, b = e.dxf.start, e.dxf.end
            segs.append((e.dxf.layer, a.x, a.y, b.x, b.y))
        elif t == "LWPOLYLINE":
            pts = [(p[0], p[1]) for p in e.get_points("xy")]
            if e.closed and pts:
                pts.append(pts[0])
            segs += [(e.dxf.layer, *pts[i], *pts[i + 1]) for i in range(len(pts) - 1)]
    return segs


def _merge_h(hs: list, gap: float) -> list:
    """[(y, x0, x1)] -> ghép đoạn cùng y, cách nhau <= gap."""
    out = []
    for y, x0, x1 in sorted(hs, key=lambda s: (round(s[0] / H_TOL), s[1])):
        if out and abs(out[-1][0] - y) <= H_TOL / 2 and x0 <= out[-1][2] + gap:
            py, px0, px1 = out[-1]
            out[-1] = ((py + y) / 2, px0, max(px1, x1))
        else:
            out.append((y, x0, x1))
    return out


def extract_profile(dxf_path: str | Path) -> dict | None:
    """-> {steps: [{len, D, d}], outer, inner, L, Dmax, axis: {y, x0, x1}} hoặc None nếu không thấy chi tiết
    tròn xoay. Toạ độ x tính từ đầu trái chi tiết, đơn vị mm (như DXF)."""
    import ezdxf
    msp = ezdxf.readfile(str(dxf_path)).modelspace()
    segs = _segments(msp)
    if not segs:
        return None
    X0 = min(min(s[1], s[3]) for s in segs)
    X1 = max(max(s[1], s[3]) for s in segs)
    Y0 = min(min(s[2], s[4]) for s in segs)
    Y1 = max(max(s[2], s[4]) for s in segs)
    pw = X1 - X0
    hO, hT, vO, vT, diag = [], [], [], [], []
    for lay, x1, y1, x2, y2 in segs:
        dx, dy = x2 - x1, y2 - y1
        L = math.hypot(dx, dy)
        if L < 0.2:
            continue
        if abs(dy) <= H_TOL and abs(dy) <= 0.03 * L:
            y = (y1 + y2) / 2
            if L > 0.7 * pw or y < Y0 + 12 or y > Y1 - 12:              # khung giấy
                continue
            (hO if lay == "OUTLINE" else hT).append((y, min(x1, x2), max(x1, x2)))
        elif abs(dx) <= H_TOL and abs(dx) <= 0.03 * L:
            (vO if lay == "OUTLINE" else vT).append(((x1 + x2) / 2, min(y1, y2), max(y1, y2)))
        else:
            a = math.degrees(math.atan2(dy, dx)) % 180
            if L < 40 and (abs(a - 45) < 5 or abs(a - 135) < 5):
                diag.append(((x1 + x2) / 2, (y1 + y2) / 2))
    hO, hT = _merge_h(hO, 1.5), _merge_h(hT, 3.0)            # nét khuất là các gạch rời -> ghép rộng hơn
    texts = [(t.dxf.insert.x, t.dxf.insert.y) for t in msp.query("TEXT MTEXT")]
    found = _find_axis(hO, vO, diag, texts)
    if found is None:            # bản scan: nét bao hay bị xếp vào nét mảnh -> xét cả nét mảnh (cả nét đứng)
        found = _find_axis(_merge_h(hO + hT, 1.5), vO + vT, diag, texts)
    if found is None:
        return None
    y0, chain, lo_x, hi_x = found
    return _profile_from(y0, chain, lo_x, hi_x, hO, hT)


def _find_axis(hO, vO, diag, texts=()):
    """Ứng viên trục từ cặp nét ngang đối xứng -> (y0, chuỗi cặp, x trái, x phải) hoặc None.
    Trừ điểm cặp có nét đứng cắt ngang trục ở giữa (hàng bảng) hoặc có chữ nằm giữa (khung tên, ô bảng)."""
    cands: dict[int, list] = {}
    for i, a in enumerate(hO):
        for b in hO[i + 1:]:
            lo, hi = (a, b) if a[0] < b[0] else (b, a)
            r = (hi[0] - lo[0]) / 2
            if r < 0.8 or r > 150:
                continue
            ox0, ox1 = max(lo[1], hi[1]), min(lo[2], hi[2])
            if ox1 - ox0 < 2 or abs(lo[1] - hi[1]) > 1.5 or abs(lo[2] - hi[2]) > 1.5:
                continue
            y0 = (hi[0] + lo[0]) / 2
            cands.setdefault(round(y0 / 0.2), []).append((ox0, ox1, r, y0))
    best, best_score = None, 0.0
    for key, pairs in cands.items():
        y0 = sum(p[3] for p in pairs) / len(pairs)
        score = 0.0
        for ox0, ox1, r, _ in pairs:
            w = ox1 - ox0
            crossing = any(ox0 + 1 < x < ox1 - 1 and lo <= y0 - r * 0.5 and hi >= y0 + r * 0.5 for x, lo, hi in vO)
            texty = any(ox0 < tx < ox1 and abs(ty - y0) < r * 0.9 for tx, ty in texts)
            score += w * (0.1 if crossing else 1.0) * (0.1 if texty else 1.0)
        xs0, xs1 = min(p[0] for p in pairs), max(p[1] for p in pairs)
        rmax = max(p[2] for p in pairs)
        nh = sum(1 for x, y in diag if xs0 <= x <= xs1 and abs(y - y0) <= rmax)
        if nh >= 3:
            score *= 3.0
        if score > best_score:
            best, best_score = (y0, pairs), score
    if best is None or best_score < 5:
        return None
    y0, pairs = best
    # chuỗi đoạn liền nhau quanh cặp mạnh nhất (bỏ cặp đối xứng tình cờ ở xa)
    pairs = sorted(pairs, key=lambda p: -(p[1] - p[0]))
    chain = [pairs[0]]
    lo_x, hi_x = pairs[0][0], pairs[0][1]
    grew = True
    while grew:
        grew = False
        for p in pairs:
            if p not in chain and p[0] <= hi_x + 1.0 and p[1] >= lo_x - 1.0:
                chain.append(p)
                lo_x, hi_x = min(lo_x, p[0]), max(hi_x, p[1])
                grew = True
    return y0, chain, lo_x, hi_x


def _profile_from(y0, chain, lo_x, hi_x, hO, hT) -> dict | None:
    def covering(ranges, x):
        return [r for a, b, r in ranges if a - 1e-6 <= x <= b + 1e-6]

    outer_rng = [(p[0], p[1], p[2]) for p in chain]
    # nét khuất (lỗ) trên bản scan là các gạch cách nhau tới vài mm -> ghép rộng (6 mm) khi tính lỗ
    inner_rng = [(s[1], s[2], abs(s[0] - y0)) for s in hO + _merge_h(hT, 6.0) if s[2] > lo_x and s[1] < hi_x]
    xs = sorted({round(min(max(v, lo_x), hi_x), 3) for a, b, _ in outer_rng + inner_rng for v in (a, b)})
    steps = []
    for xa, xb in zip(xs, xs[1:]):
        if xb - xa < 1e-6:
            continue
        xm = (xa + xb) / 2
        ro = max(covering(outer_rng, xm), default=None)
        if ro is None:
            ro = steps[-1]["ro"] if steps else None
        if ro is None:
            continue
        ri = max((r for r in covering(inner_rng, xm) if 0.3 < r < ro - 0.2), default=0.0)
        steps.append({"x0": xa, "x1": xb, "ro": ro, "ri": ri})
    # lấp khoảng "không lỗ" giữa 2 đoạn có lỗ gần bằng nhau (nét khuất đứt quãng trên bản scan)
    total = (steps[-1]["x1"] - steps[0]["x0"]) if steps else 0
    i = 0
    while i < len(steps):
        if steps[i]["ri"] == 0 and 0 < i:
            j = i
            while j < len(steps) and steps[j]["ri"] == 0:
                j += 1
            if j < len(steps):
                a, b = steps[i - 1]["ri"], steps[j]["ri"]
                gap = steps[j - 1]["x1"] - steps[i]["x0"]
                if a > 0 and b > 0 and abs(a - b) <= 0.15 * max(a, b) and gap <= max(6.0, 0.3 * total):
                    for k in range(i, j):
                        steps[k]["ri"] = (a + b) / 2
            i = j
        else:
            i += 1
    # gộp đoạn cùng đường kính (sai lệch nhỏ: nhiễu của bản scan), nuốt đoạn quá ngắn vào đoạn trước
    tol = max(0.05, 0.01 * max((s["ro"] for s in steps), default=0))
    merged = []
    for s in steps:
        same_bore = abs(merged[-1]["ri"] - s["ri"]) < max(tol, 0.08 * max(merged[-1]["ri"], s["ri"])) \
            if merged and merged[-1]["ri"] > 0 and s["ri"] > 0 else merged and abs(merged[-1]["ri"] - s["ri"]) < tol
        if merged and (abs(merged[-1]["ro"] - s["ro"]) < tol and same_bore or s["x1"] - s["x0"] < 0.3):
            if same_bore and merged[-1]["ri"] != s["ri"]:          # trung bình theo chiều dài
                la, lb = merged[-1]["x1"] - merged[-1]["x0"], s["x1"] - s["x0"]
                merged[-1]["ri"] = (merged[-1]["ri"] * la + s["ri"] * lb) / (la + lb)
            merged[-1]["x1"] = s["x1"]
        else:
            merged.append(dict(s))
    if not merged:
        return None
    out_steps = [{"len": round(float(s["x1"] - s["x0"]), 2), "D": round(float(2 * s["ro"]), 2),
                  "d": round(float(2 * s["ri"]), 2)} for s in merged]
    prof = steps_profile(out_steps)
    prof["axis"] = {"y": round(float(y0), 3), "x0": round(float(lo_x), 3), "x1": round(float(hi_x), 3)}
    return prof


def steps_profile(steps: list[dict]) -> dict:
    """[{len, D, d}] (từ trái sang phải) -> mặt cắt nửa cho tab 3D (giống solid3d.profile)."""
    outer, inner, x = [], [], 0.0
    clean = []
    for s in steps:
        L, D, d = float(s.get("len", 0)), float(s.get("D", 0)), float(s.get("d", 0) or 0)
        if L <= 0 or D <= 0:
            continue
        d = min(max(d, 0.0), D - 0.01)
        clean.append({"len": round(L, 4), "D": round(D, 4), "d": round(d, 4)})
        outer += [[round(x, 4), round(D / 2, 4)], [round(x + L, 4), round(D / 2, 4)]]
        inner += [[round(x, 4), round(d / 2, 4)], [round(x + L, 4), round(d / 2, 4)]]
        x += L
    if not any(p[1] > 0 for p in inner):
        inner = []
    return {"steps": clean, "outer": outer, "inner": inner, "cross": [], "L": round(x, 4),
            "Dmax": round(max((s["D"] for s in clean), default=0), 4), "warnings": []}


def draw_turned(steps: list[dict], out: str | Path, title: str = "TURNED PART", note: str = "") -> Path:
    """Vẽ bản DXF 2D chi tiết tròn xoay từ các đoạn [{len, D, d}]: hình chiếu đứng (nửa trên cắt, có lỗ
    nét khuất), hình chiếu cạnh, kích thước tổng + từng đoạn + từng Φ, khung tên A4. Model 1:1 (mm)."""
    import ezdxf

    from .dxf_writer import DXF_VERSION
    from .gen import SCALES, TEXT_STYLE, _dim, _scale_txt, _Sheet
    from .layers import LAYER_DEFS
    from .template import _fmt
    prof = steps_profile(steps)
    st = prof["steps"]
    if not st:
        raise ValueError("Không có đoạn hợp lệ (chiều dài và Φ phải > 0)")
    L, Dm = prof["L"], prof["Dmax"]
    need_w, need_h = L + Dm + 80, Dm + 70
    k = next((s for s in SCALES if need_w / s <= 245 and need_h / s <= 105), SCALES[-1])

    doc = ezdxf.new(DXF_VERSION, setup=True)
    doc.units = ezdxf.units.MM
    doc.header["$MEASUREMENT"] = 1
    for name, (aci, lt) in {**LAYER_DEFS, "DIM": (3, "Continuous"), "FRAME": (7, "Continuous")}.items():
        if name not in doc.layers:
            doc.layers.add(name, color=aci, linetype=lt if lt in doc.linetypes else "Continuous")
    if "HIDDEN" not in doc.linetypes:
        doc.linetypes.add("HIDDEN", pattern=[9.525, 6.35, -3.175], description="Hidden __ __ __")
        doc.layers.get("HIDDEN").dxf.linetype = "HIDDEN"
    if TEXT_STYLE not in doc.styles:
        doc.styles.add(TEXT_STYLE, font="arial.ttf")
    doc.header["$LTSCALE"] = k
    sh = _Sheet(doc, k)
    m = sh.msp
    W, H = 297 * k, 210 * k
    sh.rect(0, 0, W, H, "FRAME")
    sh.rect(10 * k, 10 * k, W - 10 * k, H - 10 * k, "OUTLINE")
    tx0, ty0 = W - 10 * k - 120 * k, 10 * k
    sh.rect(tx0, ty0, W - 10 * k, ty0 + 18 * k, "OUTLINE")
    sh.line((tx0, ty0 + 9 * k), (W - 10 * k, ty0 + 9 * k), "OUTLINE")
    sh.text(title, tx0 + 60 * k, ty0 + 13.5 * k, 3.5, align="MC", width=0.9)
    sh.text(f"Scale {_scale_txt(k)}   L={_fmt(L)}   %%c{_fmt(Dm)}", tx0 + 60 * k, ty0 + 4.5 * k, 2.8, align="MC")
    if note:
        sh.text(note, 15 * k, 15 * k, 2.6, layer="TEXT_PARAM", width=0.9)

    fx = 20 * k + max((240 * k - (L + 30 * k + Dm)) / 2, 25 * k)
    cy = 120 * k
    # hình chiếu đứng: biên ngoài (trên + dưới), bậc, lỗ nét khuất
    up = [(fx + x, cy + r) for x, r in prof["outer"]]
    dn = [(fx + x, cy - r) for x, r in reversed(prof["outer"])]
    m.add_lwpolyline([(fx, cy)] + up + [(fx + L, cy)] + dn + [(fx, cy)], dxfattribs={"layer": "OUTLINE"})
    x = 0.0
    for a, b in zip(st, st[1:]):                          # nét bậc giữa 2 đoạn
        x += a["len"]
        r = min(a["D"], b["D"]) / 2
        sh.line((fx + x, cy - r), (fx + x, cy + r), "OUTLINE")
    x = 0.0
    for s in st:                                          # lỗ: nét khuất
        if s["d"] > 0:
            for sg in (1, -1):
                sh.line((fx + x, cy + sg * s["d"] / 2), (fx + x + s["len"], cy + sg * s["d"] / 2), "HIDDEN")
        x += s["len"]
    sh.line((fx - 4 * k, cy), (fx + L + 4 * k, cy), "CENTER")
    # hình chiếu cạnh
    sx = fx + L + 30 * k + Dm / 2
    for D in sorted({s["D"] for s in st} | {s["d"] for s in st if s["d"] > 0}):
        m.add_circle((sx, cy), D / 2, dxfattribs={"layer": "OUTLINE"})
    e = Dm / 2 + 3 * k
    sh.line((sx - e, cy), (sx + e, cy), "CENTER")
    sh.line((sx, cy - e), (sx, cy + e), "CENTER")
    # kích thước: tổng chiều dài (dưới), từng đoạn (trên), từng Φ (dọc, so le)
    _dim(sh, (fx, cy - prof["outer"][0][1]), (fx + L, cy - prof["outer"][-1][1]), (fx, cy - Dm / 2 - 16 * k), 0)
    if len(st) > 1:
        x = 0.0
        for s in st:
            _dim(sh, (fx + x, cy + s["D"] / 2), (fx + x + s["len"], cy + s["D"] / 2), (fx, cy + Dm / 2 + 9 * k), 0)
            x += s["len"]
    x, seen = 0.0, set()
    for i, s in enumerate(st):
        xm = fx + x + s["len"] / 2
        if s["D"] not in seen:
            seen.add(s["D"])
            _dim(sh, (xm, cy - s["D"] / 2), (xm, cy + s["D"] / 2), (xm, cy), 90, "%%c<>")
        if s["d"] > 0 and ("d", s["d"]) not in seen:
            seen.add(("d", s["d"]))
            _dim(sh, (fx + x + s["len"] * 0.2, cy - s["d"] / 2), (fx + x + s["len"] * 0.2, cy + s["d"] / 2),
                 (fx + x + s["len"] * 0.2, cy), 90, "%%c<>")
        x += s["len"]
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    doc.saveas(out)
    return out
