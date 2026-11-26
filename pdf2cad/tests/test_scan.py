"""Ảnh scan (TIFF/PNG): nhận file ảnh, OCR chữ trên trang scan."""
import ezdxf
import pymupdf
import pytest

from pdf2cad import ConvertOptions, convert


@pytest.fixture(scope="module")
def tiff(sample_pdf, tmp_path_factory):
    PIL = pytest.importorskip("PIL.Image")
    with pymupdf.open(sample_pdf) as d:
        pix = d[0].get_pixmap(dpi=200, colorspace=pymupdf.csGRAY)
    im = PIL.frombytes("L", (pix.w, pix.h), pix.samples).point(lambda v: 255 if v > 160 else 0).convert("1")
    p = tmp_path_factory.mktemp("scan") / "scan drawing.tif"
    im.save(p, compression="tiff_lzw", dpi=(200, 200))
    return p


def test_tiff_page_size_and_raster(tiff, tmp_path):
    with pymupdf.open(tiff) as d:            # khổ giấy theo DPI ảnh
        assert d.page_count == 1
    rep = convert(tiff, ConvertOptions(out_dir=str(tmp_path), ocr="off"))[0]
    assert rep.error is None and rep.kind == "raster"
    assert rep.output.endswith("scan drawing_p001.dxf")   # tên theo file gốc, không theo PDF tạm


def test_tiff_ocr(tiff, tmp_path):
    pytest.importorskip("rapidocr")
    rep = convert(tiff, ConvertOptions(out_dir=str(tmp_path)))[0]
    assert rep.error is None and rep.counts.get("OCR", 0) >= 3
    msp = ezdxf.readfile(rep.output).modelspace()
    texts = " ".join(t.dxf.text for t in msp.query("TEXT"))
    assert "SAMPLE" in texts.upper()
    assert rep.scale == 2.0 and rep.scale_source == "ocr"   # đọc "SCALE 1:2" bằng OCR
