"""Tối ưu bản scan (nhóm 1): sửa chữ OCR, đọc tỷ lệ ở ô khung tên, dựng đường ngang/dọc, nét đứt."""
import numpy as np
import pytest

from pdf2cad.geometry import Style, Text
from pdf2cad.ocr import postfix_text
from pdf2cad.scale import detect_scale_label

cv2 = pytest.importorskip("cv2")
from pdf2cad import raster as R  # noqa: E402


@pytest.mark.parametrize("raw, fixed, check", [
    ("| |", "11", True),            # 2 vạch = số 11 trong bảng, nhưng cần soát (có thể là khung GD&T)
    ("20~5O/l50", "20~50/150", False),
    ("±0.2", "±0.2", False),
    ("MO", "MO", False),            # chữ thường giữ nguyên
    ("M. FUKASAWA]", "M. FUKASAWA]", True),
    ("I)SQUARENESS:Y", "I)SQUARENESS:Y", True),
    ("SQUARENESS:Y", "SQUARENESS:Y", False),
])
def test_postfix_text(raw, fixed, check):
    assert postfix_text(raw) == (fixed, check)


def _t(s, x, y, h=2.0):
    return Text((x, y), s, h, 0.0, Style(layer="TEXT_OCR"))


def test_scale_label_cell():
    assert detect_scale_label([_t("Scale", 100, 50), _t("1:2", 100, 44)]) == (2.0, "label")
    assert detect_scale_label([_t("Scale", 100, 50), _t("FREE", 100, 44)]) == (None, "free")
    assert detect_scale_label([_t("SCALE", 100, 50), _t("2:1", 110, 50)]) == (0.5, "label")
    assert detect_scale_label([_t("Design", 100, 50), _t("1:2", 100, 44)]) == (None, None)


def test_axis_lines_and_dashes():
    px_mm = 300 / 25.4
    bw = np.zeros((600, 1200), np.uint8)
    cv2.line(bw, (50, 100), (1100, 100), 255, 6)                 # nét đậm ~0.5 mm
    cv2.line(bw, (50, 200), (1100, 200), 255, 2)                 # nét mảnh
    for x in range(50, 1100, 90):                                # nét đứt: gạch 5 mm, khe 2.6 mm
        cv2.line(bw, (x, 300), (x + 59, 300), 255, 2)
    _, segs = R._axis_lines(bw, px_mm)
    solid, dashed, _ = R._group_dashes(segs, px_mm)
    layers = sorted(s.layer for _, s in solid)
    assert layers == ["OUTLINE", "THIN"]
    assert len(dashed) == 1 and dashed[0][1].layer == "HIDDEN"
    x1, y1, x2, y2 = dashed[0][0]
    assert abs(y1 - 300) < 1 and x1 < 60 and x2 > 1000


def _grid(rows=3, cols=3, x0=50, y0=50, cw=200, ch=60):
    img = np.full((y0 * 2 + rows * ch, x0 * 2 + cols * cw), 255, np.uint8)
    for r in range(rows + 1):
        cv2.line(img, (x0, y0 + r * ch), (x0 + cols * cw, y0 + r * ch), 0, 3)
    for c in range(cols + 1):
        cv2.line(img, (x0 + c * cw, y0), (x0 + c * cw, y0 + rows * ch), 0, 3)
    return img


def _lines(img, px_mm):
    _, ink = cv2.threshold(img, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    n = int(3 * px_mm)
    return ink, cv2.morphologyEx(ink, cv2.MORPH_OPEN, np.ones((1, n), np.uint8)) | \
        cv2.morphologyEx(ink, cv2.MORPH_OPEN, np.ones((n, 1), np.uint8))


def test_table_cells():
    px_mm = 300 / 25.4
    img = _grid()
    cv2.rectangle(img, (800, 40), (900, 120), 0, 3)              # khung lẻ loi: không phải ô bảng
    _, grid = _lines(img, px_mm)
    cells = R._table_cells(grid, px_mm)
    assert len(cells) == 9
    assert all(180 < w < 200 and 45 < h < 60 for _, _, w, h in cells)


def test_reocr_splits_word_across_cells(monkeypatch):
    """Chữ OCR dính qua 2 ô ("0.26" + "120~150") -> bỏ, đọc lại từng ô riêng."""
    from pdf2cad import ocr as O
    px_mm = 300 / 25.4
    img = _grid(rows=2, cols=2)                       # ô bảng cần >= 2 ô kề (khung GD&T 1 hàng thì bỏ)
    cv2.putText(img, "120", (100, 95), cv2.FONT_HERSHEY_SIMPLEX, 1.0, 0, 2)
    cv2.putText(img, "0.26", (300, 95), cv2.FONT_HERSHEY_SIMPLEX, 1.0, 0, 2)
    ink, grid = _lines(img, px_mm)
    cells = R._table_cells(grid, px_mm)
    W = type("W", (), {})
    merged = O.OcrWord("1200.26", 0.95, np.array([[90, 70], [400, 70], [400, 100], [90, 100]], float),
                       np.array([1.0, 0.0]))
    reads = iter(["120", "0.26"])
    monkeypatch.setattr(O, "_rec", lambda eng, im: (next(reads), 0.99))
    monkeypatch.setattr(O, "_engine", lambda: W())
    out = R._reocr_cells(img, [merged], sorted(cells), ink, grid, px_mm, 0.6)
    assert sorted(w.text for w in out) == ["0.26", "120"]


def test_erase_text_keeps_table_lines(monkeypatch):
    """Xoá chữ khỏi ảnh nhưng giữ đường kẻ bảng đi qua vùng chữ."""
    from pdf2cad import ocr as O
    px_to_pt = 72 / 300
    img = np.full((400, 800), 255, np.uint8)
    cv2.line(img, (0, 200), (799, 200), 0, 3)                    # đường kẻ bảng
    cv2.putText(img, "20~150", (300, 210), cv2.FONT_HERSHEY_SIMPLEX, 1.2, 0, 2)
    quad = [[290, 175], [470, 175], [470, 220], [290, 220]]
    word = type("W", (), {"text": "20~150", "score": 0.99, "quad": quad, "d": (1.0, 0.0)})()
    monkeypatch.setattr(O, "ocr_image", lambda *a, **k: [word])
    out, texts = R._ocr_and_erase(img, px_to_pt, remove_stamps=False)
    assert [t.text for t in texts] == ["20~150"]
    assert (out[199:202, 0:800] < 128).mean() > 0.95            # đường kẻ còn liền
    assert (out[180:195, 310:460] < 128).sum() < 20               # chữ đã xoá
