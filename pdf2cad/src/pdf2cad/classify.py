"""Phân loại trang PDF: vector (xuất từ CAD) hay raster (scan)."""
from __future__ import annotations

from enum import Enum

import pymupdf


class PageKind(str, Enum):
    VECTOR = "vector"
    RASTER = "raster"
    EMPTY = "empty"


def classify_page(page: pymupdf.Page, min_paths: int = 20,
                  raster_coverage: float = 0.5) -> PageKind:
    page_area = abs(page.rect)
    img_area = 0.0
    for info in page.get_image_info():
        img_area += abs(pymupdf.Rect(info["bbox"]) & page.rect)
    n_paths = len(page.get_cdrawings())  # bản C, nhanh hơn get_drawings()

    if n_paths >= min_paths:
        return PageKind.VECTOR
    if page_area and img_area / page_area >= raster_coverage:
        return PageKind.RASTER
    if n_paths > 0 or page.get_text("text").strip():
        return PageKind.VECTOR
    return PageKind.EMPTY
