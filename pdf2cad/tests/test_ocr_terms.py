"""Sửa chữ OCR theo ngữ cảnh bản vẽ (lỗi thật đo trên samples/*.tif, đối chiếu ảnh gốc)."""
import pytest

from pdf2cad.geometry import Style, Text
from pdf2cad.ocr import LAYER_OCR, harmonize, postfix_text


@pytest.mark.parametrize("raw, fixed", [
    ("MATERIAL:SKDII", "MATERIAL:SKD11"),              # font CAD: số 1 như chữ I
    ("NO CHÀMFER", "NO CHAMFER"),                      # vết bẩn đọc thành dấu
    ("L (1Omm INCREMENTS)", "L (10mm INCREMENTS)"),
    ("D≤HC<H => 0.Imm INCREMENTS", "D≤HC<H => 0.1mm INCREMENTS"),
    ("2≤TC<T => 0.1mm INCREWENTS", "2≤TC<T => 0.1mm INCREMENTS"),
    ("APPROVEQ", "APPROVED"),
    ("GERERAL TOLERANCE", "GENERAL TOLERANCE"),
    ("-BREAK SHARP EDGES WITHOUT NOTES CO.1~O.2", "-BREAK SHARP EDGES WITHOUT NOTES C0.1~0.2"),
    ("NOTES CO〜0.1", "NOTES C0〜0.1"),
    ("<JIS B 0405-n>", "<JIS B 0405-m>"),
    ("<JIS B O405-m>", "<JIS B 0405-m>"),
    ("O.01 INCREMENTS", "0.01 INCREMENTS"),
])
def test_fix_terms(raw, fixed):
    assert postfix_text(raw)[0] == fixed


@pytest.mark.parametrize("text", [
    "SAIGON PRECISION Co.,Ltd.", "TOLERANCES", "DSJC-B(INSPECTION)", "DR-TE-00001-11", "SHB,A-SHB",
    "SUPPORT PILLAR-TAP TYPE", "QUENCHING+TEMPERING", "60~63HRC", "MATERIAL:HAP40", "T. NGHI", "DRAWN",
])
def test_fix_terms_keeps_correct_text(text):
    assert postfix_text(text)[0] == text


def _t(s):
    return Text((0, 0), s, 2.0, 0, Style(layer=LAYER_OCR))


def test_harmonize_page_codes():
    texts = [_t("SPLB-B"), _t("SPLB-B (%%c12~%%c60)"), _t("SPLB-8_BLANK"),
             _t("SPB, A-SPB"), _t("SPB/SHB"), _t("SP8,SHB"), _t("M0011561"), _t("M0011567")]
    assert harmonize(texts) == 2
    assert texts[2].text == "SPLB-B_BLANK" and texts[5].text == "SPB,SHB"
    assert texts[6].text == "M0011561" and texts[7].text == "M0011567"   # khác chữ số thật: giữ nguyên


def test_trimmed_junk():
    from pdf2cad.raster import _trimmed_junk
    assert _trimmed_junk("15.05.2013]", "15.05.2013")
    assert _trimmed_junk("20~150/120~1501", "20~150/120~150")
    assert not _trimmed_junk("L (1Omm INCREMENTS)", "L(IOMm INCREMENTS)")
