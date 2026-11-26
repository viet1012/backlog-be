"""Ghi PageGeometry ra file DXF (AutoCAD 2007 / R2007, đơn vị mm) bằng ezdxf."""
from __future__ import annotations

from pathlib import Path

import ezdxf
from ezdxf import colors

from .geometry import Arc, Circle, Line, PageGeometry, Polyline
from .layers import LAYER_DEFS, LAYERS_OFF

DXF_VERSION = "R2007"    # AutoCAD 2007 (AC1021): mở được bằng CAD 2007 trở lên, vẫn UTF-8
TEXT_STYLE = "PDF2CAD"   # Arial: hiển thị được tiếng Việt, Φ, ±, ≤ ... (txt.shx thì không)


def write_dxf(geo: PageGeometry, path: str | Path, factor: float,
              keep_colors: bool = True, keep_glyphs: bool = False) -> Path:
    """factor: hệ số nhân từ point PDF sang mm thực (đã gồm tỷ lệ bản vẽ).

    keep_glyphs: giữ nét chữ gốc (layer TEXT_GLYPH, đã được thay bằng TEXT OCR). Mặc định bỏ:
    lớp này chiếm ~95% dung lượng file (hàng trăm nghìn đỉnh HATCH) mà vốn tắt sẵn.
    """
    doc = ezdxf.new(DXF_VERSION, setup=True)  # setup=True nạp sẵn linetype HIDDEN, CENTER...
    doc.units = ezdxf.units.MM
    doc.header["$MEASUREMENT"] = 1  # hệ mét
    if "HIDDEN" not in doc.linetypes:  # ezdxf setup không có HIDDEN (acadiso.lin: A,6.35,-3.175)
        doc.linetypes.add("HIDDEN", pattern=[9.525, 6.35, -3.175],
                          description="Hidden __ __ __ __ __")
    for name, (aci, lt) in LAYER_DEFS.items():
        if name not in doc.layers:
            doc.layers.add(name, color=aci, linetype=lt)
    for name in LAYERS_OFF:
        doc.layers.get(name).off()
    if TEXT_STYLE not in doc.styles:
        doc.styles.add(TEXT_STYLE, font="arial.ttf")
    msp = doc.modelspace()

    S = lambda p: (p[0] * factor, p[1] * factor)

    def attrs(style):
        a = {"layer": style.layer}
        if keep_colors and style.color and style.color not in ((0, 0, 0), (255, 255, 255)):
            a["true_color"] = colors.rgb2int(style.color)
        return a

    for e in geo.entities:
        if not keep_glyphs and e.style.layer in LAYERS_OFF:
            continue
        if isinstance(e, Line):
            msp.add_line(S(e.p1), S(e.p2), dxfattribs=attrs(e.style))
        elif isinstance(e, Circle):
            msp.add_circle(S(e.center), e.radius * factor, dxfattribs=attrs(e.style))
        elif isinstance(e, Arc):
            msp.add_arc(S(e.center), e.radius * factor, e.start, e.end,
                        dxfattribs=attrs(e.style))
        elif isinstance(e, Polyline):
            pts = [S(p) for p in e.points]
            if e.filled:
                hatch = msp.add_hatch(dxfattribs=attrs(e.style))
                hatch.set_solid_fill(color=7)
                hatch.paths.add_polyline_path(pts, is_closed=True)
            else:
                msp.add_lwpolyline(pts, close=e.closed, dxfattribs=attrs(e.style))

    for t in geo.texts:
        msp.add_text(t.text, height=t.height * factor, rotation=t.rotation,
                     dxfattribs={"layer": t.style.layer or "TEXT", "style": TEXT_STYLE,
                                 "width": t.width_factor,
                                 "insert": S(t.insert)})

    # Linetype scale: mẫu nét đứt chuẩn tính theo mm, bản vẽ thật lớn hơn thì tăng LTSCALE
    doc.header["$LTSCALE"] = max(1.0, factor / (25.4 / 72.0))
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    doc.saveas(path)
    return path
