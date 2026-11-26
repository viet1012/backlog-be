"""OCR cho PDF có chữ bị outline (chữ là hình tô đặc, không có lớp text)."""
import math

import ezdxf
import pymupdf
import pytest

pytest.importorskip("rapidocr")
mpl_textpath = pytest.importorskip("matplotlib.textpath")

from pdf2cad import ConvertOptions, convert  # noqa: E402
from pdf2cad.ocr import to_cad_text  # noqa: E402


def _outlined_text(page, text, x, y, size, rotate=0):
    """Vẽ chữ dưới dạng hình tô đặc (giống PDF in bằng Print to PDF / Solid Edge)."""
    from matplotlib.font_manager import FontProperties
    tp = mpl_textpath.TextPath((0, 0), text, size=size, prop=FontProperties(family="DejaVu Sans"))
    a = math.radians(rotate)
    for poly in tp.to_polygons():
        pts = []
        for px, py in poly:
            rx, ry = px * math.cos(a) - py * math.sin(a), px * math.sin(a) + py * math.cos(a)
            pts.append(pymupdf.Point(x + rx, y - ry))      # PDF: Y hướng xuống
        sh = page.new_shape()
        sh.draw_polyline(pts)
        sh.finish(fill=(0, 0, 0), color=None, closePath=True)
        sh.commit()


@pytest.fixture(scope="module")
def outlined_pdf(tmp_path_factory):
    path = tmp_path_factory.mktemp("ocr") / "outlined.pdf"
    doc = pymupdf.open()
    page = doc.new_page(width=842, height=595)              # A4 ngang
    page.draw_rect(pymupdf.Rect(20, 20, 822, 575), width=1)
    _outlined_text(page, "PUNCH BLANK", 100, 150, 28)
    _outlined_text(page, "DR-TE-20352-00", 100, 250, 22)
    _outlined_text(page, "DETAIL B", 700, 450, 20, rotate=90)  # chữ dọc, đọc từ dưới lên
    doc.save(path)
    return path


def test_ocr_outlined_text(outlined_pdf, tmp_path):
    with pymupdf.open(outlined_pdf) as d:
        assert d[0].get_text().strip() == ""                 # đúng là không có lớp text
    rep = convert(outlined_pdf, ConvertOptions(out_dir=str(tmp_path)))[0]
    assert rep.error is None and rep.counts.get("OCR", 0) >= 3
    doc = ezdxf.readfile(rep.output)
    msp = doc.modelspace()
    texts = {t.dxf.text.replace(" ", ""): t for t in msp.query("TEXT")}
    assert "PUNCHBLANK" in texts and "DR-TE-20352-00" in texts
    assert texts["PUNCHBLANK"].dxf.rotation == 0
    vertical = "".join(k for k, t in texts.items() if abs(t.dxf.rotation - 90) < 1)
    assert "ETAIL" in vertical                               # OCR có thể tách "DE" + "TAIL B"
    assert texts["PUNCHBLANK"].dxf.style == "PDF2CAD"
    # mặc định bỏ nét chữ gốc đã thay bằng TEXT -> không còn HATCH chữ, file nhỏ
    assert not list(msp.query("HATCH"))
    # chữ chồng đúng vị trí nét gốc (PUNCH BLANK bắt đầu x=100pt, baseline y=595-150pt)
    ins = texts["PUNCHBLANK"].dxf.insert
    assert abs(ins.x - 100 * 25.4 / 72) < 1.5 and abs(ins.y - 445 * 25.4 / 72) < 1.5


def test_keep_glyphs(outlined_pdf, tmp_path):
    rep = convert(outlined_pdf, ConvertOptions(out_dir=str(tmp_path), keep_glyphs=True))[0]
    doc = ezdxf.readfile(rep.output)
    # giữ nét chữ gốc trên layer TEXT_GLYPH (tắt sẵn) để đối chiếu
    assert {h.dxf.layer for h in doc.modelspace().query("HATCH")} == {"TEXT_GLYPH"}
    assert doc.layers.get("TEXT_GLYPH").is_off()


def test_ocr_off(outlined_pdf, tmp_path):
    rep = convert(outlined_pdf, ConvertOptions(out_dir=str(tmp_path), ocr="off"))[0]
    assert "OCR" not in rep.counts and rep.counts.get("Text", 0) == 0


def test_rotated_page_flip(tmp_path):
    """Trang có /Rotate: toạ độ phải tính theo khung chưa xoay rồi mới xoay."""
    p = tmp_path / "rot.pdf"
    doc = pymupdf.open()
    pg = doc.new_page(width=600, height=400)
    for i in range(25):
        pg.draw_line((10, 20 + i), (110, 20 + i))
    pg.set_rotation(90)
    doc.save(p)
    rep = convert(p, ConvertOptions(out_dir=str(tmp_path), ocr="off"))[0]
    lines = list(ezdxf.readfile(rep.output).modelspace().query("LINE"))
    assert len(lines) == 25 and rep.rotated == 270
    # sau khi xoay 90° theo chiều kim đồng hồ, đường ngang thành đường dọc dài 100pt
    L = lines[0]
    assert abs(L.dxf.start.x - L.dxf.end.x) < 1e-6
    assert abs(abs(L.dxf.start.y - L.dxf.end.y) - 100 * 25.4 / 72) < 1e-3


def test_to_cad_text():
    assert to_cad_text("Φd3") == "%%cd3"
    assert to_cad_text("±0.1") == "%%p0.1"
    assert to_cad_text("60〜63HRC") == "60~63HRC"
    assert to_cad_text("I.5") == "1.5"
    assert to_cad_text("DSJC-B（INSPECTION)") == "DSJC-B(INSPECTION)"
