"""Điều phối chuyển đổi: phân loại trang -> trích xuất -> ghi DXF, song song theo trang."""
from __future__ import annotations

import multiprocessing
import os
import time
from collections.abc import Callable
from concurrent.futures import ProcessPoolExecutor, as_completed
from dataclasses import asdict, dataclass, field
from pathlib import Path

import pymupdf

from .classify import PageKind, classify_page
from .dxf_writer import write_dxf
from .layers import LayerRules
from .scale import PT_TO_MM, detect_scale, detect_scale_label

FREE_SCALE_WARNING = ("Tỷ lệ FREE (không theo tỷ lệ): hình vẽ chỉ để tham khảo, "
                      "kích thước đúng là số ghi trên bản vẽ")
from .geometry import dominant_text_turns, rotate_page
from .vector_extract import _Flip, extract_page, extract_texts


IMAGE_EXTS = {".tif", ".tiff", ".png", ".jpg", ".jpeg", ".bmp"}
INPUT_EXTS = {".pdf"} | IMAGE_EXTS


def image_to_pdf(src: str | Path, dst: str | Path) -> Path:
    """Ảnh (TIFF nhiều trang, PNG, JPG...) -> PDF; khổ trang theo DPI ghi trong ảnh."""
    dst = Path(dst)
    with pymupdf.open(src) as img:
        dst.write_bytes(img.convert_to_pdf())
    return dst


def _reading_order(texts) -> list[str]:
    """Sắp chữ theo dòng (trên xuống), trong dòng trái sang phải — để "SCALE" và "1:2" do OCR
    tách rời vẫn đứng cạnh nhau khi dò tỷ lệ."""
    items = sorted((t for t in texts if t.rotation % 180 < 1 or t.rotation % 180 > 179),
                   key=lambda t: -t.insert[1])
    lines: list[list] = []
    for t in items:                                  # cùng dòng: chân chữ lệch < nửa chiều cao
        if lines and abs(lines[-1][0].insert[1] - t.insert[1]) < 0.5 * max(t.height, 1.0):
            lines[-1].append(t)
        else:
            lines.append([t])
    return [t.text for ln in lines for t in sorted(ln, key=lambda t: t.insert[0])]


@dataclass
class ConvertOptions:
    out_dir: str = "out"
    pages: list[int] | None = None      # số trang bắt đầu từ 1; None = tất cả
    scale: float | None = None          # ép tỷ lệ (vd 2.0 cho 1:2); None = tự nhận diện
    dpi: int = 300                      # cho trang scan
    workers: int | None = None          # None = tự chọn theo số CPU
    fit_arcs: bool = True
    raster: bool = True                 # xử lý trang scan bằng OpenCV
    thick_width: float = 0.5            # ngưỡng nét đậm (pt)
    ocr: str = "auto"                   # auto: OCR khi trang không có lớp text | on | off
    ocr_dpi: int = 300
    auto_rotate: bool = True            # xoay bản vẽ theo /Rotate và theo hướng chữ
    remove_stamps: bool = True          # bản scan: xoá dấu mộc (SPC, MassPro, chữ ký...)
    keep_glyphs: bool = False           # giữ nét chữ gốc (TEXT_GLYPH) -> file DXF lớn gấp ~20 lần
    progress_dir: str | None = None     # ghi tiến độ từng trang (web)


@dataclass
class PageReport:
    page: int
    kind: str
    scale: float
    scale_source: str
    counts: dict = field(default_factory=dict)
    output: str | None = None
    seconds: float = 0.0
    error: str | None = None
    warning: str | None = None
    rotated: int = 0                    # độ đã xoay bản vẽ (CCW)

    def as_dict(self):
        return asdict(self)


def _process_page(pdf_path: str, pno: int, opt: ConvertOptions,
                  stem: str | None = None) -> PageReport:
    """Chạy trong process con: mỗi process tự mở file PDF (đối tượng Document không pickle được)."""
    t0 = time.perf_counter()
    stem = stem or Path(pdf_path).stem
    from . import progress
    progress.start(str(Path(opt.progress_dir) / f"{stem}_p{pno:03d}.json")
                   if opt.progress_dir else None)
    try:
        with pymupdf.open(pdf_path) as doc:
            page = doc[pno - 1]
            kind = classify_page(page)

            if opt.scale is not None:
                scale, src = opt.scale, "user"
            else:
                detected = detect_scale([t.text for t in extract_texts(page)])
                scale, src = (detected, "title-block") if detected else (1.0, "default")

            rep = PageReport(pno, kind.value, scale, src)
            n_ocr = 0
            if kind == PageKind.VECTOR:
                progress.stage(0.02, "Đọc nét vẽ vector", 0.3)
                geo = extract_page(page, LayerRules(opt.thick_width), opt.fit_arcs)
                n_fill = sum(1 for e in geo.entities if getattr(e, "filled", False))
                want_ocr = opt.ocr == "on" or (opt.ocr == "auto" and not geo.texts
                                                and n_fill >= 20)
                if want_ocr:
                    from . import ocr
                    if ocr.available():
                        progress.stage(0.3, "Nhận dạng chữ (OCR)", 0.9)
                        n_ocr = ocr.apply_ocr(page, geo, _Flip(page), dpi=opt.ocr_dpi)
                        if opt.scale is None and src == "default":  # đọc tỷ lệ từ chữ OCR
                            det = detect_scale(_reading_order(geo.texts))
                            if det:
                                scale, src = det, "ocr"
                                rep.scale, rep.scale_source = scale, src
                    else:
                        rep.warning = ("Trang không có lớp text (chữ là hình vẽ). "
                                       "Cài OCR: pip install \"pdf2cad[ocr]\"")
                if opt.auto_rotate:
                    turns = (-page.rotation // 90) % 4
                    rotate_page(geo, turns)
                    t2 = dominant_text_turns(geo.texts)
                    rotate_page(geo, t2)
                    rep.rotated = ((turns + t2) % 4) * 90
            elif kind == PageKind.RASTER and opt.raster:
                from . import ocr as _ocr
                from .raster import extract_raster_page
                use_ocr = opt.ocr != "off" and _ocr.available()
                geo = extract_raster_page(page, opt.dpi, ocr=use_ocr,
                                          remove_stamps=opt.remove_stamps)
                n_ocr = len(geo.texts) if use_ocr else 0
                if use_ocr and opt.scale is None and src == "default":
                    det = detect_scale(_reading_order(geo.texts))
                    if det:
                        scale, src = det, "ocr"
                        rep.scale, rep.scale_source = scale, src
                if opt.ocr != "off" and not _ocr.available():
                    rep.warning = 'Trang scan: cài OCR để có chữ sửa được: pip install "pdf2cad[ocr]"'
                if opt.auto_rotate:
                    turns = (-page.rotation // 90) % 4
                    rotate_page(geo, turns)
                    t2 = dominant_text_turns(geo.texts)
                    rotate_page(geo, t2)
                    rep.rotated = ((turns + t2) % 4) * 90
            else:
                rep.seconds = time.perf_counter() - t0
                return rep

            if opt.scale is None and src == "default":   # khung tên dạng ô: "Scale" | "1:2" / "FREE"
                det, how = detect_scale_label(geo.texts)
                if det:
                    scale, src = det, "label"
                    rep.scale, rep.scale_source = scale, src
                elif how == "free":
                    rep.scale_source = "free"
                    rep.warning = rep.warning or FREE_SCALE_WARNING
            progress.stage(0.95, "Ghi file DXF")
            out = Path(opt.out_dir) / f"{stem}_p{pno:03d}.dxf"
            write_dxf(geo, out, factor=PT_TO_MM * scale, keep_glyphs=opt.keep_glyphs)
            rep.counts, rep.output = geo.counts(), str(out)
            if n_ocr:
                rep.counts["OCR"] = n_ocr
    except Exception as e:  # không để 1 trang lỗi làm hỏng cả lô
        rep = PageReport(pno, "error", 1.0, "-", error=f"{type(e).__name__}: {e}")
    rep.seconds = round(time.perf_counter() - t0, 3)
    progress.stage(1.0, "Xong")
    return rep


def convert(pdf_path: str | Path, opt: ConvertOptions | None = None,
            on_page: Callable[[PageReport, int, int], None] | None = None,
            should_stop: Callable[[], bool] | None = None) -> list[PageReport]:
    """Chuyển đổi PDF -> DXF.

    on_page(report, done, total): gọi mỗi khi xong 1 trang (dùng cho thanh tiến độ GUI).
    should_stop(): trả True để dừng; các trang chưa chạy sẽ bị bỏ qua.
    """
    opt = opt or ConvertOptions()
    src_path = Path(pdf_path)
    stem = src_path.stem
    tmpdir = None
    if src_path.suffix.lower() in IMAGE_EXTS:          # TIFF/PNG/JPG -> PDF tạm
        import tempfile
        tmpdir = tempfile.TemporaryDirectory(prefix="pdf2cad_")
        pdf_path = str(image_to_pdf(src_path, Path(tmpdir.name) / f"{stem}.pdf"))
    else:
        pdf_path = str(pdf_path)
    try:
        return _convert_pdf(pdf_path, opt, on_page, should_stop, stem)
    finally:
        if tmpdir is not None:
            tmpdir.cleanup()


def _convert_pdf(pdf_path, opt, on_page, should_stop, stem) -> list[PageReport]:
    with pymupdf.open(pdf_path) as doc:
        n = doc.page_count
    pages = [p for p in (opt.pages or range(1, n + 1)) if 1 <= p <= n]
    total = len(pages)
    stop = should_stop or (lambda: False)
    results: dict[int, PageReport] = {}

    def done(rep: PageReport):
        results[rep.page] = rep
        if on_page:
            on_page(rep, len(results), total)

    workers = opt.workers or min(total, os.cpu_count() or 1)
    if workers <= 1 or total <= 1:
        for p in pages:
            if stop():
                break
            done(_process_page(pdf_path, p, opt, stem))
    else:
        # Song song theo trang. Trên Windows bắt buộc gọi từ trong `if __name__ == "__main__":`
        # Luôn dùng 'spawn' (mặc định trên Windows): an toàn khi gọi từ luồng nền của GUI,
        # tránh deadlock của fork() trong tiến trình đa luồng trên Linux/macOS.
        ctx = multiprocessing.get_context("spawn")
        with ProcessPoolExecutor(max_workers=workers, mp_context=ctx) as ex:
            futures = [ex.submit(_process_page, pdf_path, p, opt, stem) for p in pages]
            for f in as_completed(futures):
                done(f.result())
                if stop():
                    for g in futures:
                        g.cancel()
                    break
    return [results[p] for p in pages if p in results]


def parse_pages(spec: str | None) -> list[int] | None:
    """'1-3,5' -> [1, 2, 3, 5]"""
    if not spec:
        return None
    out: list[int] = []
    for part in spec.split(","):
        part = part.strip()
        if "-" in part:
            a, b = part.split("-", 1)
            out.extend(range(int(a), int(b) + 1))
        elif part:
            out.append(int(part))
    return sorted(set(out))
