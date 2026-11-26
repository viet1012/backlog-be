"""Tự học bản vẽ mới: từ DXF đã chuyển (có TEXT nhờ OCR) -> mẫu tham số "nháp" cho bot.

1. Khung tên: Drawing Number, Parts Name, Drawing Title.
2. Bảng tham số: hàng tiêu đề (D, H, T, L ...), cột lấy theo đường kẻ dọc, ô gộp nhiều hàng
   lấy theo đường kẻ ngang trong từng cột. Tiêu đề OCR sót/sai ("0" thay "D") được suy ra từ
   bảng khác cùng số cột trên cùng bản vẽ.
3. Mỗi bảng -> 1 mẫu "draft" (vẽ mới bằng gen.py: trụ / trụ có mũ nếu có cột H & T).
Mẫu nháp cần người kiểm tra bảng trước khi dùng cho sản xuất.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

import ezdxf

from .template import expand_values

PARAM_NAMES = {"D", "d", "H", "T", "L", "P", "S", "C", "M", "Y", "K", "W", "B"}
_NUM_RE = re.compile(r"^\(?\d+(?:\.\d+)?\)?$")
_LVALS_RE = re.compile(r"^[\d~/().]+$")


@dataclass
class _T:
    x: float
    y: float
    h: float
    text: str


def _texts(msp) -> list[_T]:
    out = []
    for t in msp.query("TEXT"):
        if t.dxf.layer in ("REVISION", "TEXT_GLYPH"):
            continue
        r = t.dxf.rotation % 360
        if 8 < r < 352:                               # chữ ngang (cho phép lệch scan vài độ)
            continue
        txt = t.dxf.text.strip()
        if txt:
            out.append(_T(t.dxf.insert.x, t.dxf.insert.y, t.dxf.height, txt))
    return out


def _segments(msp):
    hs, vs = [], []
    for e in msp.query("LINE"):
        a, b = e.dxf.start, e.dxf.end
        if abs(a.y - b.y) < 0.3 and abs(a.x - b.x) > 1.5:
            hs.append((min(a.x, b.x), max(a.x, b.x), (a.y + b.y) / 2))
        elif abs(a.x - b.x) < 0.3 and abs(a.y - b.y) > 1.5:
            vs.append(((a.x + b.x) / 2, min(a.y, b.y), max(a.y, b.y)))
    return hs, vs


def _clean_num(s: str) -> float | None:
    s = s.replace(",", ".").replace(" ", "").replace("I", "1").replace("l", "1").replace("O", "0")
    s = s.strip("()")
    try:
        return float(s)
    except ValueError:
        return None


def _clean_lvals(s: str) -> str | None:
    s = s.replace(" ", "").replace("I", "1").replace("l", "1").replace("O", "0").replace("~", "~")
    s = re.sub(r"[^\d~/.()]", "", s)
    s = re.sub(r"\(\d+\)", "", s).strip("/")             # "(40)50~100": giá trị đặc biệt -> bỏ
    if not s or not _LVALS_RE.match(s):
        return None
    try:
        return s if expand_values(s) else None
    except ValueError:
        return None


# ----------------------------------------------------------------- khung tên
def _title_block(texts: list[_T]) -> dict:
    info: dict = {}
    for t in texts:
        m = re.search(r"DR-[A-Z]{2}-\d{5}-\d{2}", t.text.replace(" ", ""))
        if m and t.h >= 2.5:
            info["drawing_no"] = m.group(0)

    def below(label, n=1):
        lab = next((t for t in texts if t.text.lower().replace(" ", "") == label), None)
        if not lab:
            return []
        cand = sorted((t for t in texts if t.y < lab.y - 0.5 and abs((t.x - lab.x)) < 25
                       and lab.y - t.y < 20 * n), key=lambda t: -t.y)
        return [c.text for c in cand[:n]]

    pn = below("partsname")
    if pn:
        # OCR hay nhầm B -> 8 ở cuối mã chữ ("SP8", "A-SH8" -> "SPB", "A-SHB")
        info["parts_name"] = re.sub(r"(?<=[A-Z]{2})8(?![0-9])", "B", pn[0])
    dt = below("drawingtitle", 2)
    if dt:
        info["title1"] = dt[0]
        if len(dt) > 1:
            info["title2"] = dt[1]
    co = next((t.text for t in texts if "CO.,LTD" in t.text.upper().replace(" ", "")), None)
    if co:
        info["company"] = co
    rough = next((t.text for t in sorted(texts, key=lambda t: -t.y)
                  if re.fullmatch(r"\d{1,2}\.\d{1,2}a", t.text.replace(" ", ""))), None)
    if rough:
        info["roughness"] = rough.replace(" ", "")
    return info


# ----------------------------------------------------------------- bảng tham số
def _find_tables(texts: list[_T], hs, vs) -> list[dict]:
    # hàng tiêu đề: có ô "L" / "L(10mm INCREMENT)" và ≥ 1 ô tham số khác cùng hàng
    heads = [t for t in texts if re.match(r"^L(\s*\(|$)", t.text)]
    tables = []
    for lh in heads:
        row = [t for t in texts if abs(t.y - lh.y) < max(1.2, lh.h * 0.6) and lh.x - 60 < t.x <= lh.x]
        names = [t for t in row if t.text in PARAM_NAMES or t.text in ("0", "O") or t is lh]
        if len(names) < 2:
            continue
        # đường kẻ dọc cắt hàng tiêu đề -> ranh giới cột
        xs = sorted({round(x, 1) for x, y0, y1 in vs if y0 - 0.5 <= lh.y + lh.h / 2 <= y1 + 0.5
                     and lh.x - 70 < x < lh.x + 80})
        xs = [x for i, x in enumerate(xs) if i == 0 or x - xs[i - 1] > 2.0]
        left_names = min(t.x for t in names)
        lb = max((x for x in xs if x < left_names), default=left_names - 3)
        rb = min((x for x in xs if x > lh.x + 15), default=lh.x + 60)
        bounds = [x for x in xs if lb <= x <= rb]
        if len(bounds) < 3:
            continue
        cols = []
        for a, b in zip(bounds[:-1], bounds[1:]):
            if b - a < 3:
                continue
            nm = next((t.text for t in names if a - 0.5 <= t.x + 0.3 * t.h <= b), None)
            cols.append({"x0": a, "x1": b, "name": nm})
        tables.append({"head_y": lh.y, "cols": cols, "h": lh.h})
    # bỏ trùng
    out = []
    for tb in tables:
        if not any(abs(tb["head_y"] - o["head_y"]) < 2 and abs(tb["cols"][0]["x0"] - o["cols"][0]["x0"]) < 2
                   for o in out):
            out.append(tb)
    # tiêu đề sót / sai: suy từ bảng khác cùng số cột
    full = [[c["name"] for c in tb["cols"]] for tb in out
            if all(c["name"] in PARAM_NAMES or (c["name"] or "").startswith("L") for c in tb["cols"])]
    for tb in out:
        ref = next((f for f in full if len(f) == len(tb["cols"])), None)
        for i, c in enumerate(tb["cols"]):
            n = c["name"] or ""
            if n.startswith("L"):
                c["name"] = "L"
            elif n in ("0", "O") and (ref is None or ref[i] == "D"):
                c["name"] = "D"
            elif n not in PARAM_NAMES:
                c["name"] = ref[i] if ref else None
    return out


def _read_table(tb: dict, texts: list[_T], hs) -> dict | None:
    cols = tb["cols"]
    if not cols or cols[0]["name"] is None:
        return None
    key = cols[0]
    y_head = tb["head_y"]
    # các hàng: giá trị số ở cột khoá, đi xuống tới khi gặp khoảng trống lớn
    keyv = sorted((t for t in texts if key["x0"] < t.x + t.h * 0.4 < key["x1"] and t.y < y_head - 0.5
                   and _clean_num(t.text) is not None), key=lambda t: -t.y)
    rows = []
    for t in keyv:
        if rows and rows[-1]["y"] - t.y > 4 * max(t.h, 1.5):
            break
        rows.append({"y": t.y + t.h / 2, cols[0]["name"]: _clean_num(t.text)})
    if len(rows) < 2:
        return None
    y_bot = rows[-1]["y"] - 3 * tb["h"]
    pitch = sorted(rows[i]["y"] - rows[i + 1]["y"] for i in range(len(rows) - 1))[len(rows) // 2 - 1] \
        if len(rows) > 2 else 3 * tb["h"]
    for c in cols[1:]:
        name = c["name"]
        if not name:
            continue
        cx = (c["x0"] + c["x1"]) / 2
        # ranh giới ô trong cột này = đường kẻ ngang đi qua giữa cột
        ys = sorted({round(y, 1) for x0, x1, y in hs if x0 <= cx <= x1 and y_bot - 2 <= y <= y_head})
        cells = [t for t in texts if c["x0"] - 1 < t.x < c["x1"] and y_bot < t.y < y_head - 0.5]

        def interval(yc):
            return (max((y for y in ys if y < yc), default=y_bot),
                    min((y for y in ys if y > yc), default=y_head))

        if name == "L":
            # gộp các mảnh chữ cùng ô (L nhiều dòng: "40/50/60/70" + "80/85/90/100")
            for r in rows:
                lo, hi = interval(r["y"])
                inside = sorted((t for t in cells if lo < t.y + t.h / 2 < hi), key=lambda t: (-t.y, t.x))
                parts = [p for p in (_clean_lvals(t.text) for t in inside) if p]
                if parts:
                    r[name] = "/".join(parts)
            continue
        nums = [(t, _clean_num(t.text)) for t in cells if _clean_num(t.text) is not None]
        for t, v in nums:
            yc = t.y + t.h / 2
            lo, hi = interval(yc)
            in_cell = [r for r in rows if lo < r["y"] < hi]
            others = [u for u, _ in nums if u is not t and lo < u.y + u.h / 2 < hi]
            if len(in_cell) >= 2 and not others and hi - lo < 1.6 * pitch * len(in_cell):
                for r in in_cell:                     # ô gộp thật (có đường kẻ bao quanh)
                    r.setdefault(name, v)
            else:
                near = min(rows, key=lambda r: abs(r["y"] - yc))
                if abs(near["y"] - yc) < 0.45 * pitch:  # ô riêng của 1 hàng
                    near[name] = v
    names = [c["name"] for c in cols if c["name"]]
    return {"key": cols[0]["name"], "columns": names, "rows": [{k: v for k, v in r.items() if k != "y"}
                                                                 for r in rows]}


def _material_near(texts: list[_T], tb: dict) -> str | None:
    x0 = tb["cols"][0]["x0"]
    c = [t for t in texts if t.text.upper().startswith("MATERIAL") and 0 < t.y - tb["head_y"] < 15
         and x0 - 20 < t.x < tb["cols"][-1]["x1"]]
    if not c:
        return None
    m = c[0].text.split(":", 1)[-1].strip().replace("II", "11").replace("DI1", "D11")
    return m or None


# ----------------------------------------------------------------- tạo mẫu
def learn_from_dxf(dxf_path: str | Path, out_root: str | Path, source: str | None = None) -> list[dict]:
    """Trả về danh sách {name, title, dir, rows, columns}. Mẫu lưu ở out_root/<name>/template.json."""
    msp = ezdxf.readfile(dxf_path).modelspace()
    texts = _texts(msp)
    hs, vs = _segments(msp)
    tb_info = _title_block(texts)
    dno = tb_info.get("drawing_no") or Path(dxf_path).stem
    out = []
    tables = [t for t in (_read_table(tb, texts, hs) for tb in _find_tables(texts, hs, vs)) if t]
    raw_tables = _find_tables(texts, hs, vs)
    for i, tab in enumerate(tables, 1):
        tb = raw_tables[i - 1] if i - 1 < len(raw_tables) else None
        mat = _material_near(texts, tb) if tb else None
        cols = tab["columns"]
        key = tab["key"]
        rows = [r for r in tab["rows"] if key in r]
        has_head = "H" in cols and "T" in cols
        slug = re.sub(r"[^a-z0-9]+", "-", f"{dno}-{mat or i}".lower()).strip("-")
        title = " · ".join(x for x in (dno, mat or f"bảng {i}",
                                       tb_info.get("title1") or tb_info.get("parts_name")) if x)
        params = {c: {"desc": f"Cột {c} của bảng", "desc_en": f"Column {c} of the table",
                      "desc_ja": f"表の列 {c}", "column": True} for c in cols}
        if "L" in params:
            params["L"] = {"desc": "Chiều dài tổng L", "desc_en": "Overall length L", "desc_ja": "全長 L",
                           "required": True}
        params[key]["desc"] = f"Cột khoá {key} của bảng"
        params[key]["desc_en"] = f"Key column {key} of the table"
        params[key]["desc_ja"] = f"表のキー列 {key}"
        spec = {
            "name": slug, "code": tb_info.get("parts_name", dno).split(",")[0].strip(),
            "title": title, "kind": "generator", "draft": True,
            "source": source or Path(dxf_path).name,
            "table_key": key, "values_step": 10, "params": params,
            "params_show": cols,
            "table": rows,
            "examples": [f"{key}={rows[len(rows) // 2][key]:g} L=" +
                         (str(int(expand_values(rows[len(rows) // 2]['L'])[0]))
                          if isinstance(rows[len(rows) // 2].get('L'), str) else "60")],
            "naming": {"parts_name": f"{tb_info.get('parts_name', dno).split(',')[0].strip()} "
                                     + " ".join(f"{c}{{{c}}}" for c in cols),
                       "drawing_no": f"{dno} (" + " ".join(f"{c}{{{c}}}" for c in cols) + ")"},
            "generator": {"type": "cylinder", "D": key,
                          **({"head": {"D": "H", "L": "T"}} if has_head else {})},
            "drawing": {k: v for k, v in {
                "title1": tb_info.get("title1"), "title2": tb_info.get("title2"),
                "material": mat or "", "company": tb_info.get("company", ""),
                "roughness": tb_info.get("roughness"),
                "notes": ["DRAFT — tự học từ bản vẽ " + dno + ", kiểm tra trước khi dùng"]}.items() if v},
        }
        d = Path(out_root) / slug
        d.mkdir(parents=True, exist_ok=True)
        (d / "template.json").write_text(json.dumps(spec, ensure_ascii=False, indent=1), encoding="utf-8")
        out.append({"name": slug, "title": title, "dir": str(d), "rows": len(rows), "columns": cols})
    return out
