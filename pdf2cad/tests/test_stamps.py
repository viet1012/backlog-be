"""Bản scan: ký hiệu sửa đổi △n và xoá dấu mộc."""
import cv2
import numpy as np
import pytest

pytest.importorskip("rapidocr")
from pdf2cad import raster as R  # noqa: E402

DPI = 200
PXPT = 72.0 / DPI


def _page():
    img = np.full((1100, 1600), 255, np.uint8)
    # bảng: 1 đường kẻ ngang dài đi xuyên qua vị trí dấu mộc
    cv2.line(img, (100, 700), (1500, 700), 0, 3)
    cv2.putText(img, "40/50/60/70", (420, 300), cv2.FONT_HERSHEY_SIMPLEX, 1.6, 0, 3)
    # ký hiệu sửa đổi △8 đứng ngay trước chuỗi
    P = np.array([[310, 320], [370, 320], [340, 268]], np.int32)      # cạnh 60 px ~ 7.6 mm
    cv2.polylines(img, [P], True, 0, 3)
    cv2.putText(img, "8", (331, 314), cv2.FONT_HERSHEY_SIMPLEX, 0.7, 0, 2)
    # dấu mộc tròn có chữ SPC / MassPro, đè lên đường kẻ
    cv2.circle(img, (1000, 700), 150, 0, 5)
    cv2.putText(img, "MassPro", (880, 640), cv2.FONT_HERSHEY_SIMPLEX, 1.6, 0, 4)
    cv2.putText(img, "SPC", (930, 800), cv2.FONT_HERSHEY_SIMPLEX, 2.2, 0, 5)
    # vòng tròn thật của bản vẽ (không có chữ mộc) phải được giữ
    cv2.circle(img, (300, 900), 110, 0, 3)
    cv2.putText(img, "A", (285, 915), cv2.FONT_HERSHEY_SIMPLEX, 1.2, 0, 3)
    return img


def test_revision_mark():
    img2, marks, texts = R._revision_marks(_page(), PXPT)
    assert [t.text for t in texts] == ["8"] and len(marks) == 1
    assert marks[0].style.layer == "REVISION" and len(marks[0].points) == 3
    assert img2[268:321, 310:371].min() == 255                  # đã xoá khỏi ảnh trước OCR


def test_stamp_removed_lines_kept():
    img = _page()
    out, texts = R._ocr_and_erase(img, PXPT, remove_stamps=True)
    words = " ".join(t.text for t in texts).upper()
    assert "SPC" not in words and "MASSPRO" not in words
    assert "40/50/60/70" in words
    body = np.delete(out[600:820, 880:1120], np.s_[95:106], axis=0)   # bỏ hàng của đường kẻ
    assert (body < 128).sum() < 50                                        # ruột mộc đã xoá
    assert (out[699:702, 860:1140] < 128).mean() > 0.9                    # đường kẻ xuyên qua còn
    assert (out[790:1010, 190:410] < 128).sum() > 500                      # vòng tròn bản vẽ còn


def test_keep_stamps():
    out, texts = R._ocr_and_erase(_page(), PXPT, remove_stamps=False)
    assert "SPC" in " ".join(t.text for t in texts).upper()
