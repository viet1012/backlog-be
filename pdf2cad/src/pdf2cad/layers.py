"""Gán layer CAD dựa trên kiểu nét trong PDF (độ dày, nét đứt, tô màu)."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass
class LayerRules:
    thick_width: float = 0.5   # pt; nét >= ngưỡng này coi là nét cơ bản (outline)


# tên layer -> (mã màu ACI, linetype)
LAYER_DEFS = {
    "OUTLINE": (7, "Continuous"),
    "THIN": (8, "Continuous"),
    "HIDDEN": (3, "HIDDEN"),
    "CENTER": (1, "CENTER"),
    "FILL": (8, "Continuous"),
    "TEXT": (2, "Continuous"),
    "RASTER": (5, "Continuous"),
    "TEXT_OCR": (2, "Continuous"),     # chữ tạo lại bằng OCR
    "TEXT_CHECK": (1, "Continuous"),   # chữ OCR độ tin cậy thấp: cần soát
    "TEXT_GLYPH": (9, "Continuous"),
    "REVISION": (1, "Continuous"),     # ký hiệu sửa đổi △n (bản scan)   # nét chữ gốc (hình tô) đã thay bằng TEXT - tắt sẵn
}
LAYERS_OFF = {"TEXT_GLYPH"}


def classify_layer(width: float, dashes: tuple[float, ...], filled_only: bool,
                   rules: LayerRules = LayerRules()) -> str:
    if filled_only:
        return "FILL"            # mũi tên kích thước, ký hiệu tô đặc...
    if dashes:
        # Mẫu có nhiều hơn 2 phần tử (dài-ngắn-chấm-ngắn) => đường tâm; còn lại => nét khuất
        return "CENTER" if len(dashes) > 2 else "HIDDEN"
    return "OUTLINE" if width >= rules.thick_width else "THIN"
