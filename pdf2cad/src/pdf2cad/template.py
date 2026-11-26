"""Mẫu tham số (template) dựng từ bản vẽ PDF -> vẽ lại theo kích thước mong muốn.

Ý tưởng: bản vẽ họ sản phẩm (ví dụ DSJC-B) vẽ "Scale FREE", kích thước ghi bằng ký hiệu
(L, S, ØH...) + bảng giá trị. Mẫu lưu:
  - base.dxf      : bản vẽ đã chuyển từ PDF (có TEXT nhờ OCR)
  - template.json : vùng hình chiếu, trục, mỗi ký hiệu kích thước đo từ đâu đến đâu,
                    bảng tham số + quy tắc (NOTE), cách thay chữ ký hiệu bằng số.
Khi vẽ lại: giải hệ để tìm vị trí mới của các "điểm mốc" dọc trục (x) và bán kính (r) sao cho
mọi kích thước đúng giá trị, rồi co giãn tuyến tính từng đoạn toàn bộ hình chiếu => DXF 1:1.
"""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

import os

from .i18n import Bi, en_of, ja_of

# Thư mục mẫu: biến môi trường PDF2CAD_TEMPLATES, mặc định <repo>/templates
TEMPLATES_DIR = Path(os.environ.get("PDF2CAD_TEMPLATES",
                                    Path(__file__).resolve().parents[2] / "templates"))


# ----------------------------------------------------------------- đọc mẫu
@dataclass
class Template:
    name: str
    dir: Path
    spec: dict
    title: str = ""
    params: dict = field(default_factory=dict)

    @classmethod
    def load(cls, path: str | Path) -> "Template":
        path = Path(path)
        d = path if path.is_dir() else path.parent
        spec = json.loads((d / "template.json").read_text(encoding="utf-8"))
        return cls(spec["name"], d, spec, spec.get("title", spec["name"]), spec["params"])

    @property
    def base_dxf(self) -> Path:
        return self.dir / self.spec["base_dxf"]


# Mẫu tự học từ bản vẽ mới (pdf2cad learn / nút "Dạy bot")
LEARNED_DIR = Path(os.environ.get("PDF2CAD_LEARNED", TEMPLATES_DIR / "learned"))


def list_templates(root: Path | None = None, learned: Path | None = None) -> list[Template]:
    out: list[Template] = []
    for r in (Path(root or TEMPLATES_DIR), Path(learned or LEARNED_DIR)):
        if r.exists():
            for p in sorted(r.iterdir()):
                if (p / "template.json").exists():
                    try:
                        out.append(Template.load(p))
                    except Exception:            # mẫu hỏng không làm hỏng cả danh sách
                        pass
    return out


# ----------------------------------------------------------------- tham số & quy tắc
def _fmt(v: float) -> str:
    """8.0 -> '8', 1.50 -> '1.5'"""
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


def _rng(spec) -> tuple[float, float] | None:
    if spec is None:
        return None
    if isinstance(spec, (int, float)):
        return float(spec), float(spec)
    return float(spec[0]), float(spec[1])


# ----------------------------------------------------------------- bảng tham số (tổng quát)
def keys_of(t: "Template") -> list[str]:
    k = t.spec["table_key"]
    return list(k) if isinstance(k, list) else [k]


def expand_values(spec: str, step: float = 10.0) -> list[float]:
    """'20~50/55/60~150' -> [20, 30, 40, 50, 55, 60, 70, ..., 150] (bước 10 mm như ghi chú bảng)."""
    out: list[float] = []
    for part in str(spec).replace(" ", "").split("/"):
        if not part:
            continue
        if "~" in part:
            # "15~60" (bước 10): 15, 20, 30, ..., 60 — bước bám theo bội số của 10
            # (khớp cách bảng ghi "20~50/55/60~150": 55 phải ghi riêng)
            a, b = (float(x) for x in part.split("~", 1))
            out.append(a)
            v = (a // step + 1) * step
            while v <= b + 1e-9:
                out.append(round(v, 4))
                v += step
            if abs(out[-1] - b) > 1e-9:
                out.append(b)
        else:
            out.append(float(part))
    return sorted(set(out))


def cell_info(cell, step: float = 10.0) -> dict:
    """Chuẩn hoá ô bảng -> {range, also, values, text, default}."""
    if cell is None:
        return {}
    if isinstance(cell, (int, float)):
        return {"range": (float(cell), float(cell)), "also": [], "values": None,
                "text": _fmt(float(cell))}
    if isinstance(cell, str):
        return {"range": None, "also": [], "values": expand_values(cell, step), "text": cell}
    if isinstance(cell, list):
        r = _rng(cell)
        return {"range": r, "also": [], "values": None,
                "text": _fmt(r[0]) + (f"~{_fmt(r[1])}" if r[1] != r[0] else "")}
    vals = cell.get("values")
    if isinstance(vals, str):
        vals_l = expand_values(vals, cell.get("step", step))
    else:
        vals_l = [float(x) for x in vals] if vals else None
    r = _rng(cell.get("range"))
    also = [float(x) for x in cell.get("also", [])]
    text = (vals if isinstance(vals, str) else "/".join(_fmt(x) for x in vals_l)) if vals_l else (
        (_fmt(r[0]) + (f"~{_fmt(r[1])}" if r[1] != r[0] else "")) if r else "-")
    if also:
        a = ", ".join(_fmt(x) for x in also)
        text = Bi(f"{text} (hoặc {a})", f"{text} (or {a})", f"{text} (または {a})")
    return {"range": r, "also": also, "values": vals_l, "text": text, "default": cell.get("default")}


def cell_allows(ci: dict, v: float) -> bool:
    if not ci:
        return True
    if ci.get("values"):
        return any(abs(v - x) < 1e-6 for x in ci["values"]) or any(abs(v - x) < 1e-6 for x in ci["also"])
    r = ci.get("range")
    return r is None or r[0] - 1e-9 <= v <= r[1] + 1e-9 or any(abs(v - x) < 1e-6 for x in ci["also"])


def cell_first(ci: dict) -> float | None:
    if not ci:
        return None
    if ci.get("default") is not None:
        return float(ci["default"])
    if ci.get("values"):
        return ci["values"][0]
    return ci["range"][0] if ci.get("range") else None


def nearest_allowed(ci: dict, v: float, n: int = 3) -> list[float]:
    pool = list(ci.get("values") or []) + list(ci.get("also") or [])
    if ci.get("range"):
        pool += [ci["range"][0], ci["range"][1]]
    return sorted(sorted(set(pool), key=lambda x: abs(x - v))[:n])


def matching_rows(t: "Template", req: dict, ignore: str | None = None) -> list[dict]:
    keys = keys_of(t)
    return [r for r in t.spec["table"]
            if all(abs(float(r[k]) - float(req[k])) < 1e-9 for k in keys if k in req and k != ignore)]


def candidates(t: "Template", req: dict, key: str) -> list[float]:
    return sorted({float(r[key]) for r in matching_rows(t, req, ignore=key)})


def resolve_params(t: Template, req: dict) -> tuple[dict, list[str], list[str]]:
    """Từ yêu cầu (vd {'No': 16, 'L': 80} hoặc {'D': 30, 'd': 9, 'L': 120}) -> bộ tham số đầy đủ.

    Trả về (values, notes, errors). notes: các giá trị tự chọn/tính; errors: vi phạm quy tắc.
    """
    # thông báo là Bi (str tiếng Việt + .en): bot dò e.startswith("Thiếu ...") trên phần tiếng Việt
    notes, errors = [], []
    keys = keys_of(t)
    for k in keys:
        cands = candidates(t, req, k)
        cs = ", ".join(_fmt(c) for c in cands)
        if k not in req:
            errors.append(Bi(f"Thiếu {k} (chọn 1 trong: {cs})", f"Missing {k} (choose one of: {cs})",
                             f"{k} が未指定です（選択肢: {cs}）"))
            return {}, notes, errors
        if not any(abs(float(req[k]) - c) < 1e-9 for c in cands):
            other = ", ".join(f"{x}={_fmt(float(req[x]))}" for x in keys if x in req and x != k)
            kv = f"{k}={_fmt(float(req[k]))}"
            errors.append(Bi(f"{kv} không có trong bảng" + (f" (với {other})" if len(keys) > 1 else "")
                             + f" — chọn: {cs}",
                             f"{kv} is not in the table" + (f" (with {other})" if len(keys) > 1 else "")
                             + f" — choose: {cs}",
                             f"{kv} は表にありません" + (f"（{other} の場合）" if len(keys) > 1 else "")
                             + f" — 選択肢: {cs}"))
            return {}, notes, errors
    row = matching_rows(t, req)[0]
    step = float(t.spec.get("values_step", 10))
    vals: dict = {k: float(req[k]) for k in keys}
    label = ", ".join(f"{k}={_fmt(vals[k])}" for k in keys)
    for p, pdef in t.params.items():
        if p in keys or pdef.get("derived"):
            continue
        if pdef.get("info"):                          # trường thông tin (vd mã vật liệu)
            if p in row:
                vals[p] = row[p]
            continue
        ci = cell_info(row.get(p), step)
        tv, te, tj = (str(ci["text"]), en_of(ci["text"]), ja_of(ci["text"])) if ci else ("", "", "")
        if p in req:
            v = float(req[p])
            if ci and not cell_allows(ci, v):
                near = ", ".join(_fmt(x) for x in nearest_allowed(ci, v))
                hv = f"; gần nhất: {near}" if ci.get("values") else ""
                he = f"; nearest: {near}" if ci.get("values") else ""
                hj = f"; 最も近い値: {near}" if ci.get("values") else ""
                msg = Bi(f"⚠ {p}={_fmt(v)} ngoài bảng tiêu chuẩn cho {label} (bảng: {tv}{hv})"
                         " — vẫn vẽ theo yêu cầu (hàng đặc biệt)",
                         f"⚠ {p}={_fmt(v)} is outside the standard table for {label} (table: {te}{he})"
                         " — drawn as requested (special row)",
                         f"⚠ {p}={_fmt(v)} は {label} の標準表の範囲外です（表: {tj}{hj}）"
                         " — 指定どおり作図しました（特殊品）")
                (errors if pdef.get("strict") else notes).append(msg)
            vals[p] = v
        elif pdef.get("required"):
            errors.append(Bi(f"Thiếu {p}" + (f" (bảng: {tv})" if ci else ""),
                             f"Missing {p}" + (f" (table: {te})" if ci else ""),
                             f"{p} が未指定です" + (f"（表: {tj}）" if ci else "")))
        elif not ci and pdef.get("column"):          # ô bảng trống (OCR không đọc được) -> hỏi
            errors.append(Bi(f"Thiếu {p} — bảng chưa có {p} cho {label} (OCR không đọc được), nhập {p}=...",
                             f"Missing {p} — the table has no {p} for {label} (OCR could not read it), "
                             f"enter {p}=...",
                             f"{p} が未指定です — {label} の {p} が表にありません（OCR で読み取れず）。"
                             f"{p}=... を入力してください"))
        elif ci:
            v = cell_first(ci)
            vals[p] = v
            if ci.get("values") and len(ci["values"]) > 1 or (
                    ci.get("range") and ci["range"][0] != ci["range"][1]) or ci.get("default") is not None:
                notes.append(Bi(f"{p} chưa chỉ định -> dùng {_fmt(v)} (bảng: {tv})",
                                f"{p} not specified -> using {_fmt(v)} (table: {te})",
                                f"{p} 未指定 -> {_fmt(v)} を使用（表: {tj}）"))
        elif "default" in pdef:
            vals[p] = float(pdef["default"])

    num = {k: v for k, v in vals.items() if isinstance(v, (int, float))}
    # quy tắc NOTE: if <cond> then <param> = value (ghi đè giá trị bảng nếu người dùng không chỉ định)
    for rule in t.spec.get("rules", []):
        try:
            cond = eval(rule["if"], {"__builtins__": {}}, dict(num))
        except Exception:
            continue
        if cond:
            for p, v in rule["then"].items():
                if p in req and abs(float(req[p]) - v) > 1e-9:
                    cur = _fmt(float(req[p]))
                    notes.append(Bi(f"Lưu ý: NOTE '{rule['text']}' gợi ý {p}={_fmt(v)}, "
                                    f"đang dùng {p}={cur} theo yêu cầu",
                                    f"Note: NOTE '{rule['text']}' suggests {p}={_fmt(v)}, "
                                    f"using {p}={cur} as requested",
                                    f"注意: NOTE「{rule['text']}」では {p}={_fmt(v)} ですが、"
                                    f"指定どおり {p}={cur} を使用"))
                elif p not in req:
                    vals[p] = num[p] = float(v)
                    notes.append(Bi(f"{p}={_fmt(v)} theo NOTE: {rule['text']}",
                                    f"{p}={_fmt(v)} per NOTE: {rule['text']}",
                                    f"NOTE により {p}={_fmt(v)}: {rule['text']}"))

    for p, pdef in t.params.items():                  # tham số dẫn xuất (vd S1 = S + 1)
        if pdef.get("derived"):
            vals[p] = num[p] = float(eval(pdef["derived"], {"__builtins__": {}}, dict(num)))

    for c in t.spec.get("checks", []):                # ràng buộc hình học
        try:
            if not eval(c["expr"], {"__builtins__": {}}, dict(num)):
                errors.append(Bi(c["msg"], c.get("msg_en", c["msg"]),
                                 c.get("msg_ja", c.get("msg_en", c["msg"]))))
        except Exception:
            pass
    return vals, notes, errors


# ----------------------------------------------------------------- co giãn từng đoạn
def solve_breakpoints(dims: list[dict], vals: dict, fixed_zero: float | None = None):
    """dims: [{a, b, expr}] (toạ độ trên bản vẽ gốc). Trả về (điểm mốc gốc, vị trí mới)."""
    pts = sorted({round(float(x), 4) for d in dims for x in (d["a"], d["b"])}
                 | ({round(fixed_zero, 4)} if fixed_zero is not None else set()))
    idx = {p: i for i, p in enumerate(pts)}
    n = len(pts)
    rows, rhs, w = [], [], []
    ratios = []
    for d in dims:
        v = float(eval(d["expr"], {"__builtins__": {}}, dict(vals))) if isinstance(d["expr"], str) \
            else float(d["expr"])
        if d.get("half"):
            v /= 2
        a, b = round(float(d["a"]), 4), round(float(d["b"]), 4)
        r = np.zeros(n)
        r[idx[b]], r[idx[a]] = 1, -1
        rows.append(r), rhs.append(v), w.append(1000.0)
        if abs(b - a) > 1e-6:
            ratios.append(v / (b - a))
    s = float(np.median(ratios)) if ratios else 1.0
    for i in range(n - 1):              # đoạn không có kích thước: giữ tỷ lệ chung (ràng buộc yếu)
        r = np.zeros(n)
        r[i + 1], r[i] = 1, -1
        rows.append(r), rhs.append(s * (pts[i + 1] - pts[i])), w.append(1.0)
    anchor = idx[round(fixed_zero, 4)] if fixed_zero is not None else 0
    r = np.zeros(n)
    r[anchor] = 1
    rows.append(r), rhs.append(0.0), w.append(1e6)
    A = np.array(rows) * np.array(w)[:, None]
    X, *_ = np.linalg.lstsq(A, np.array(rhs) * np.array(w), rcond=None)
    return np.array(pts), X


def _warp1d(c: float, P: np.ndarray, X: np.ndarray) -> float:
    """Ánh xạ tuyến tính từng đoạn; ngoài khoảng: giữ khoảng cách tới mốc ngoài cùng."""
    if c <= P[0]:
        return X[0] + (c - P[0])
    if c >= P[-1]:
        return X[-1] + (c - P[-1])
    return float(np.interp(c, P, X))


@dataclass
class Warp:
    box: tuple[float, float, float, float]       # vùng hình chiếu (x0, y0, x1, y1)
    x0: float                                    # gốc trục dọc (mặt đầu)
    axis_y: float                                # tâm trục (chi tiết tròn xoay)
    Px: np.ndarray
    Xx: np.ndarray
    Pr: np.ndarray
    Xr: np.ndarray

    def inside(self, x, y) -> bool:
        b = self.box
        return b[0] <= x <= b[2] and b[1] <= y <= b[3]

    def __call__(self, x: float, y: float) -> tuple[float, float]:
        if not self.inside(x, y):
            return x, y
        nx = self.x0 + _warp1d(x, self.Px, self.Xx)
        dy = y - self.axis_y
        nr = _warp1d(abs(dy), self.Pr, self.Xr)
        return nx, self.axis_y + math.copysign(nr, dy)

    def scale_at(self, x: float, y: float) -> float:
        e = 0.05
        a, b = self(x - e, y), self(x + e, y)
        c, d = self(x, y - e), self(x, y + e)
        return (math.dist(a, b) + math.dist(c, d)) / (4 * e)


def build_warp(t: Template, vals: dict) -> Warp:
    v = t.spec["view"]
    Px, Xx = solve_breakpoints(v["x_dims"], vals, fixed_zero=v["x0"])
    # điểm mốc x tính theo vị trí tương đối với gốc x0
    Pr, Xr = solve_breakpoints(
        [{**d, "a": d["a"] - v["axis_y"], "b": d["b"] - v["axis_y"]} for d in v["r_dims"]],
        vals, fixed_zero=0.0)
    return Warp(tuple(v["box"]), v["x0"], v["axis_y"], Px, Xx - Xx[list(Px).index(round(v["x0"], 4))],
                Pr, Xr)


# ----------------------------------------------------------------- vẽ lại
def _sub_labels(t: Template, text: str, vals: dict) -> str | None:
    for lab in t.spec.get("labels", []):
        if text == lab["text"]:
            return re.sub(r"\{([^}]+)\}",
                          lambda m: _fmt(float(eval(m.group(1), {"__builtins__": {}}, dict(vals)))),
                          lab["to"])
    return None


def _shift_tolerances(msp, replaced):
    """Chữ ký hiệu ("L") thay bằng số ("80") dài hơn => dời dung sai đứng ngay sau nó
    ("+0.26", "+0.16") theo phần dài thêm, tránh chữ đè nhau."""
    from .ocr import _text_width
    texts = list(msp.query("TEXT"))
    done = {id(e) for e, *_ in replaced}
    for e, old, old_wf in replaced:
        h = e.dxf.height
        a = math.radians(e.dxf.rotation)
        d, n = (math.cos(a), math.sin(a)), (-math.sin(a), math.cos(a))
        ow = (_text_width(old, h) or h * len(old) * 0.7) * old_wf
        nw = (_text_width(e.dxf.text, h) or h * len(e.dxf.text) * 0.7) * e.dxf.get("width", 1.0)
        delta = nw - ow
        if abs(delta) < 1e-6:
            continue
        p0 = e.dxf.insert
        for t2 in texts:
            if id(t2) in done or abs((t2.dxf.rotation - e.dxf.rotation + 180) % 360 - 180) > 1:
                continue
            rx, ry = t2.dxf.insert.x - p0.x, t2.dxf.insert.y - p0.y
            along, across = rx * d[0] + ry * d[1], rx * n[0] + ry * n[1]
            if ow - 0.6 * h <= along <= ow + 3 * h and -1.3 * h <= across <= 1.6 * h:
                i = t2.dxf.insert
                t2.dxf.insert = (i.x + delta * d[0], i.y + delta * d[1], 0)


def _fill(fmt: str, vals: dict) -> str:
    return fmt.format(**{k: (_fmt(v) if isinstance(v, float) else v) for k, v in vals.items()})


def generate(t: Template, vals: dict, out: str | Path) -> dict:
    """Vẽ lại bản vẽ theo bộ tham số vals -> DXF. Trả về thông tin kiểm tra."""
    if t.spec.get("kind") == "generator":            # vẽ mới hoàn toàn (bản gốc là ảnh scan)
        import datetime

        from .gen import generate_family
        nm = t.spec.get("naming", {})
        v2 = dict(vals)
        v2["_parts_name"] = _fill(nm.get("parts_name", t.title), vals)
        v2["_drawing_no"] = _fill(nm.get("drawing_no", t.spec.get("drawing", {}).get("drawing_no", "")),
                                  vals)
        v2["_info"] = _fill(nm["info"], vals) if nm.get("info") else ""
        t.spec.setdefault("drawing", {}).setdefault("date", datetime.date.today().isoformat())
        return generate_family(t, v2, out)
    import ezdxf

    warp = build_warp(t, vals)
    doc = ezdxf.readfile(t.base_dxf)
    msp = doc.modelspace()
    W = warp
    replaced: list = []
    for e in msp:
        typ = e.dxftype()
        if typ == "LINE":
            s, en = e.dxf.start, e.dxf.end
            if W.inside(s.x, s.y) or W.inside(en.x, en.y):
                e.dxf.start = (*W(s.x, s.y), 0)
                e.dxf.end = (*W(en.x, en.y), 0)
        elif typ in ("CIRCLE", "ARC"):
            c = e.dxf.center
            if W.inside(c.x, c.y):
                k = W.scale_at(c.x, c.y)
                e.dxf.center = (*W(c.x, c.y), 0)
                e.dxf.radius = e.dxf.radius * k
        elif typ == "LWPOLYLINE":
            pts = list(e.get_points("xyseb"))
            if any(W.inside(p[0], p[1]) for p in pts):
                e.set_points([(*W(p[0], p[1]), *p[2:]) for p in pts], format="xyseb")
        elif typ == "HATCH":
            for path in e.paths:
                if hasattr(path, "vertices") and any(W.inside(v[0], v[1]) for v in path.vertices):
                    path.vertices = [(*W(v[0], v[1]), *v[2:]) for v in path.vertices]
        elif typ == "TEXT":
            i = e.dxf.insert
            if W.inside(i.x, i.y):
                e.dxf.insert = (*W(i.x, i.y), 0)
                new = _sub_labels(t, e.dxf.text, vals)
                if new is not None:
                    replaced.append((e, e.dxf.text, e.dxf.get("width", 1.0)))
                    e.dxf.text = new
                    e.dxf.width = 1.0
                    e.dxf.layer = "TEXT_PARAM"
    _shift_tolerances(msp, replaced)
    # thay chữ ngoài hình chiếu (khung tên...)
    for lab in t.spec.get("title_labels", []):
        for e in msp.query("TEXT"):
            if e.dxf.text == lab["text"]:
                e.dxf.text = re.sub(r"\{([^}]+)\}", lambda m: _fmt(float(
                    eval(m.group(1), {"__builtins__": {}}, dict(vals)))), lab["to"])
                e.dxf.width = min(e.dxf.get("width", 1.0), 0.9)
    if "TEXT_PARAM" not in doc.layers:
        doc.layers.add("TEXT_PARAM", color=6)
    # ghi chú tham số
    note = "  ".join(f"{k}={_fmt(v)}" for k, v in vals.items()
                     if not t.params.get(k, {}).get("derived"))
    nx, ny = t.spec.get("param_note_at", [warp.box[0], warp.box[1] - 4])
    msp.add_text(f"PARAMETERS: {note}", height=2.5,
                 dxfattribs={"insert": (nx, ny), "layer": "TEXT_PARAM", "style": "PDF2CAD"})

    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    from .dxf_writer import DXF_VERSION
    from .layers import LAYERS_OFF
    # nét chữ gốc (TEXT_GLYPH, tắt sẵn) trong base.dxf chiếm ~95% dung lượng: bỏ khi xuất bản vẽ mới
    for name in LAYERS_OFF:
        for e in list(msp.query(f'*[layer=="{name}"]')):
            msp.delete_entity(e)
    doc.dxfversion = DXF_VERSION   # base.dxf có thể là bản CAD mới hơn -> hạ về AutoCAD 2007 khi lưu
    doc.saveas(out)
    # kiểm tra: đo lại từng kích thước sau khi co giãn
    check = []
    for d in t.spec["view"]["x_dims"]:
        want = float(eval(d["expr"], {"__builtins__": {}}, dict(vals))) if isinstance(d["expr"], str) \
            else float(d["expr"])
        got = W(d["b"], t.spec["view"]["axis_y"])[0] - W(d["a"], t.spec["view"]["axis_y"])[0]
        check.append((d.get("name", d["expr"]), want, round(got, 4)))
    for d in t.spec["view"]["r_dims"]:
        want = float(eval(d["expr"], {"__builtins__": {}}, dict(vals)))
        ya = W(t.spec["view"]["x0"] + 1e-3, d["b"])[1] - t.spec["view"]["axis_y"]
        check.append((d.get("name", d["expr"]), want, round(2 * ya, 4)))
    return {"output": str(out), "check": check}


# ----------------------------------------------------------------- hiểu câu chat
_NUM = r"(\d+(?:[.,]\d+)?)"


def parse_request(t: Template, msg: str) -> dict:
    """Hiểu yêu cầu kiểu: 'vẽ DSJC-B No.16 L=80 P 15', 'số 13, dài 70, d1=1.8', 'H 20'."""
    req: dict[str, float] = {}
    m = msg.replace("，", ",")
    key = keys_of(t)[0]
    # khoá phân biệt hoa/thường (D ≠ d); riêng "No" và từ tiếng Việt thì không
    kp = "(?i:No)" if key.lower() == "no" else re.escape(key)
    km = re.search(rf"(?:(?<![A-Za-z0-9]){kp}\.?|(?i:\bsố|\bsize|\bcỡ|\bloại)|Φ|Ø)\s*[:=]?\s*{_NUM}", m)
    if km:
        req[key] = float(km.group(1).replace(",", "."))
    lm = re.search(rf"\b(?:dài|chiều dài|length)\s*[:=]?\s*{_NUM}", m, re.I)
    if lm:
        req["L"] = float(lm.group(1).replace(",", "."))
    names = sorted((p for p in t.params if p != key and not t.params[p].get("derived")
                    and not t.params[p].get("info")),
                   key=len, reverse=True)
    alias = {a: p for p, d in t.params.items() for a in d.get("aliases", [])}
    pat = "|".join(re.escape(x) for x in names + list(alias))
    for mm in re.finditer(rf"(?<![A-Za-z0-9])({pat})\s*(?:=|:|\s)?\s*{_NUM}", m):
        name = alias.get(mm.group(1), mm.group(1))
        req[name] = float(mm.group(2).replace(",", "."))
    return req


def file_stem(t: Template, vals: dict) -> str:
    ks = "_".join(f"{k}{_fmt(float(vals[k]))}" for k in keys_of(t))
    return f"{t.name}_{ks}_L{_fmt(float(vals.get('L', 0)))}"


def chat(t: Template, msg: str, out_dir: str | Path) -> dict:
    req = parse_request(t, msg)
    vals, notes, errors = resolve_params(t, req)
    res = {"request": req, "params": vals, "notes": notes, "errors": errors, "output": None}
    if errors:
        return res
    stem = file_stem(t, vals)
    g = generate(t, vals, Path(out_dir) / f"{stem}.dxf")
    res.update(g)
    return res
