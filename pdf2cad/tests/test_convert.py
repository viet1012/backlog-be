import math

import ezdxf
import pymupdf

from pdf2cad import ConvertOptions, convert
from pdf2cad.classify import PageKind, classify_page
from pdf2cad.pipeline import parse_pages


def _lengths(msp):
    return sorted(round(e.dxf.start.distance(e.dxf.end), 2) for e in msp.query("LINE"))


def test_classify(sample_pdf):
    with pymupdf.open(sample_pdf) as d:
        assert classify_page(d[0]) == PageKind.VECTOR
        assert classify_page(d[1]) == PageKind.RASTER


def test_vector_page_exact(sample_pdf, tmp_path):
    rep = convert(sample_pdf, ConvertOptions(out_dir=str(tmp_path), pages=[1]))[0]
    assert rep.error is None and rep.scale == 2.0 and rep.scale_source == "title-block"
    doc = ezdxf.readfile(rep.output)
    msp = doc.modelspace()
    L = _lengths(msp)
    for v in (100.0, 60.0, 50.0, 90.0):        # kích thước thật (mm) sau khi nhân tỷ lệ
        assert v in L
    circles = list(msp.query("CIRCLE"))
    assert len(circles) == 1 and math.isclose(circles[0].dxf.radius, 10, abs_tol=1e-3)
    arcs = list(msp.query("ARC"))
    assert len(arcs) == 1 and math.isclose(arcs[0].dxf.radius, 10, abs_tol=1e-3)
    layers = {e.dxf.layer for e in msp}
    assert {"OUTLINE", "HIDDEN", "CENTER", "THIN", "FILL", "TEXT"} <= layers
    assert doc.units == 4  # mm
    assert not doc.audit().has_fixes and not doc.audit().has_errors  # AutoCAD mở không báo lỗi


def test_raster_page_approx(sample_pdf, tmp_path):
    rep = convert(sample_pdf, ConvertOptions(out_dir=str(tmp_path), pages=[2], scale=2.0))[0]
    assert rep.error is None and rep.kind == "raster"
    msp = ezdxf.readfile(rep.output).modelspace()
    L = _lengths(msp)
    assert any(abs(v - 100) < 1.0 for v in L)
    assert any(abs(v - 60) < 1.0 for v in L)
    circles = list(msp.query("CIRCLE"))
    assert len(circles) == 1 and abs(circles[0].dxf.radius - 10) < 0.3


def test_parallel(sample_pdf, tmp_path):
    reps = convert(sample_pdf, ConvertOptions(out_dir=str(tmp_path), workers=2))
    assert [r.page for r in reps] == [1, 2] and all(r.output for r in reps)


def test_parse_pages():
    assert parse_pages("1-3,5,3") == [1, 2, 3, 5]
    assert parse_pages(None) is None
