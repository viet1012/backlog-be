"""Trích xuất hình học vector từ trang PDF (bản vẽ xuất từ phần mềm CAD).

Không raster hoá: đọc trực tiếp lệnh vẽ của PDF => nhanh và chính xác tuyệt đối.
"""
from __future__ import annotations

import math
import re

import pymupdf

from .geometry import (Arc, Line, PageGeometry, Polyline, Style, Text,
                       bezier_to_arc, dedupe_lines, flatten_bezier, merge_arcs)
from .layers import LayerRules, classify_layer

_NUM_RE = re.compile(r"[-+]?\d*\.?\d+")


def _parse_dashes(d) -> tuple[float, ...]:
    """PyMuPDF trả dashes dạng chuỗi '[ 6 3 ] 0' hoặc '[] 0'."""
    if not d:
        return ()
    inside = d[d.find("[") + 1: d.find("]")] if "[" in d else ""
    vals = tuple(round(float(v), 3) for v in _NUM_RE.findall(inside))
    return vals if any(v > 0 for v in vals) else ()


def _rgb(c) -> tuple[int, int, int] | None:
    if c is None:
        return None
    if len(c) == 1:  # grayscale
        c = (c[0], c[0], c[0])
    return tuple(int(round(v * 255)) for v in c[:3])


def unrotated_rect(page: pymupdf.Page) -> pymupdf.Rect:
    return (page.rect * page.derotation_matrix).normalize()


class _Flip:
    """Đổi toạ độ PDF (gốc trên-trái, Y xuống) sang CAD (gốc dưới-trái, Y lên)."""

    def __init__(self, page: pymupdf.Page):
        # get_drawings()/get_text() trả toạ độ trang CHƯA xoay (/Rotate) => dùng khung chưa xoay.
        # Việc xoay theo /Rotate làm sau, ở pipeline (geometry.rotate_page).
        r = unrotated_rect(page)
        self.h = r.height
        self.x0, self.y0 = r.x0, r.y0

    def __call__(self, p) -> tuple[float, float]:
        return (p.x - self.x0, self.h - (p.y - self.y0))


def extract_texts(page: pymupdf.Page, flip: _Flip | None = None) -> list[Text]:
    flip = flip or _Flip(page)
    out: list[Text] = []
    style = Style(layer="TEXT")
    data = page.get_text("dict", flags=pymupdf.TEXTFLAGS_TEXT)
    for block in data["blocks"]:
        for line in block.get("lines", []):
            dx, dy = line["dir"]
            rot = math.degrees(math.atan2(-dy, dx)) % 360.0
            for span in line["spans"]:
                txt = span["text"].strip()
                if not txt:
                    continue
                o = pymupdf.Point(span["origin"])
                # Chiều cao chữ hoa ~ 0.7 * font size: gần với text height trong CAD
                out.append(Text(flip(o), txt, span["size"] * 0.7, rot, style))
    return out


def extract_page(page: pymupdf.Page, rules: LayerRules = LayerRules(),
                 fit_arcs: bool = True) -> PageGeometry:
    flip = _Flip(page)
    r = unrotated_rect(page)
    geo = PageGeometry(r.width, r.height)
    ents = geo.entities

    for path in page.get_drawings():
        ptype = path.get("type", "s")
        filled_only = ptype == "f"
        stroke = path.get("color") if not filled_only else path.get("fill")
        dashes = _parse_dashes(path.get("dashes"))
        width = path.get("width") or 0.0
        layer = classify_layer(width, dashes, filled_only, rules)
        style = Style(_rgb(stroke), round(width, 3), dashes, layer)

        poly: list = []  # dùng cho hình tô đặc (mũi tên...)
        for item in path["items"]:
            kind = item[0]
            if kind == "l":
                p1, p2 = flip(item[1]), flip(item[2])
                if filled_only:
                    poly += [p1, p2]
                else:
                    ents.append(Line(p1, p2, style))
            elif kind == "c":
                pts = [flip(p) for p in item[1:5]]
                if filled_only:
                    poly += flatten_bezier(*pts)
                    continue
                arc = bezier_to_arc(*pts, style) if fit_arcs else None
                if arc is not None:
                    ents.append(arc)
                else:
                    ents.append(Polyline(flatten_bezier(*pts, segments=12), False, style))
            elif kind == "re":
                r = item[1]
                corners = [flip(pymupdf.Point(x, y)) for x, y in
                           ((r.x0, r.y0), (r.x1, r.y0), (r.x1, r.y1), (r.x0, r.y1))]
                ents.append(Polyline(corners, True, style, filled=filled_only))
            elif kind == "qu":
                q = item[1]
                corners = [flip(p) for p in (q.ul, q.ur, q.lr, q.ll)]
                ents.append(Polyline(corners, True, style, filled=filled_only))

        if filled_only and len(poly) >= 3:
            ents.append(Polyline(_dedupe_consecutive(poly), True, style, filled=True))

    geo.entities = dedupe_lines(merge_arcs(ents) if fit_arcs else ents)
    geo.texts = extract_texts(page, flip)
    return geo


def _dedupe_consecutive(pts: list, tol: float = 1e-6) -> list:
    out = [pts[0]]
    for p in pts[1:]:
        if math.dist(p, out[-1]) > tol:
            out.append(p)
    return out
