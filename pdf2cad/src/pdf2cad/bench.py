"""Benchmark: so sánh tốc độ render (PyMuPDF vs pypdfium2) và trích xuất vector."""
from __future__ import annotations

import statistics
import time

import pymupdf


def _timeit(fn, repeat: int) -> float:
    ts = []
    for _ in range(repeat):
        t0 = time.perf_counter()
        fn()
        ts.append(time.perf_counter() - t0)
    return statistics.median(ts)


def run_benchmark(pdf_path: str, dpi: int = 150, repeat: int = 3) -> dict[str, float]:
    res: dict[str, float] = {}
    scale = dpi / 72.0

    def mupdf_render():
        with pymupdf.open(pdf_path) as d:
            for p in d:
                p.get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY)
    res["render_pymupdf_gray"] = _timeit(mupdf_render, repeat)

    try:
        import pypdfium2 as pdfium

        def pdfium_render():
            d = pdfium.PdfDocument(pdf_path)
            for i in range(len(d)):
                d[i].render(scale=scale, grayscale=True)
            d.close()
        res["render_pypdfium2_gray"] = _timeit(pdfium_render, repeat)
    except ImportError:
        res["render_pypdfium2_gray"] = float("nan")

    def mupdf_vectors():
        with pymupdf.open(pdf_path) as d:
            for p in d:
                p.get_drawings()
    res["vectors_get_drawings"] = _timeit(mupdf_vectors, repeat)

    def mupdf_cvectors():
        with pymupdf.open(pdf_path) as d:
            for p in d:
                p.get_cdrawings()
    res["vectors_get_cdrawings"] = _timeit(mupdf_cvectors, repeat)

    from .vector_extract import extract_page

    def full_extract():
        with pymupdf.open(pdf_path) as d:
            for p in d:
                extract_page(p)
    res["full_vector_extract"] = _timeit(full_extract, repeat)
    return res
