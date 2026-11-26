"""Giao diện dòng lệnh: pdf2cad convert | inspect | bench"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

import pymupdf

from .classify import classify_page
from .pipeline import ConvertOptions, convert, parse_pages
from .scale import detect_scale
from .vector_extract import extract_texts


def _cmd_convert(a) -> int:
    opt = ConvertOptions(out_dir=a.out, pages=parse_pages(a.pages), scale=a.scale,
                         dpi=a.dpi, workers=a.workers, fit_arcs=not a.no_arcs,
                         raster=not a.no_raster, thick_width=a.thick,
                         ocr=a.ocr, ocr_dpi=a.ocr_dpi, auto_rotate=not a.no_rotate,
                         remove_stamps=not a.keep_stamps, keep_glyphs=a.keep_glyphs)
    t0 = time.perf_counter()
    reports = convert(a.pdf, opt)
    total = time.perf_counter() - t0
    if a.json:
        print(json.dumps([r.as_dict() for r in reports], ensure_ascii=False, indent=2))
    else:
        for r in reports:
            if r.error:
                print(f"[p{r.page}] LỖI: {r.error}")
            else:
                print(f"[p{r.page}] {r.kind:6s} scale={r.scale:g} ({r.scale_source}) "
                      f"{r.seconds:.2f}s -> {r.output or '(bỏ qua)'}  {r.counts}"
                      + (f"  xoay {r.rotated}°" if r.rotated else ""))
                if r.warning:
                    print(f"      CẢNH BÁO: {r.warning}")
        print(f"Tổng: {len(reports)} trang, {total:.2f}s")
    return 1 if any(r.error for r in reports) else 0


def _cmd_inspect(a) -> int:
    with pymupdf.open(a.pdf) as doc:
        for page in doc:
            texts = [t.text for t in extract_texts(page)]
            print(f"p{page.number + 1}: {classify_page(page).value:6s} "
                  f"size={page.rect.width:.0f}x{page.rect.height:.0f}pt "
                  f"paths={len(page.get_cdrawings())} texts={len(texts)} "
                  f"scale={detect_scale(texts)}")
    return 0


def _cmd_bench_ocr(a) -> int:
    """Đo tốc độ OCR trên máy này với từng thiết bị (cpu / gpu)."""
    import os
    import subprocess
    code = (
        "import time, pymupdf, sys; import pdf2cad.ocr as o\n"
        "from pdf2cad.vector_extract import extract_page, _Flip\n"
        "o._engine(); doc = pymupdf.open(sys.argv[1]); page = doc[int(sys.argv[2]) - 1]\n"
        "g = extract_page(page); t = time.perf_counter(); n = o.apply_ocr(page, g, _Flip(page))\n"
        "print(f'{o.ENGINE_INFO[\"device\"]:4s} {time.perf_counter() - t:6.1f} s  {n} chữ  "
        "{o.ENGINE_INFO.get(\"providers\", [\"CPUExecutionProvider\"])}')\n")
    for dev in a.device:
        env = {**os.environ, "PDF2CAD_OCR_DEVICE": dev}
        print(f"[{dev}] ", end="", flush=True)
        r = subprocess.run([sys.executable, "-c", code, a.pdf, str(a.page)], env=env,
                           capture_output=True, text=True)
        print((r.stdout.strip() or r.stderr.strip().splitlines()[-1]) if r.returncode == 0 or r.stdout
              else "LỖI: " + r.stderr.strip().splitlines()[-1])
    return 0


def _cmd_bench(a) -> int:
    from .bench import run_benchmark
    for k, v in run_benchmark(a.pdf, a.dpi, a.repeat).items():
        print(f"{k:24s} {v * 1000:9.1f} ms")
    return 0


def _cmd_gui(a) -> int:
    from .gui import main as gui_main
    return gui_main([sys.argv[0], *a.files])


def _cmd_serve(a) -> int:
    from pathlib import Path

    from .web.app import Settings, serve
    st = Settings()
    if a.data_dir:
        st.data_dir = Path(a.data_dir)
    st.max_jobs, st.page_workers = a.jobs, a.workers
    st.max_upload_mb, st.ttl_hours = a.max_upload, a.ttl
    return serve(a.host, a.port, st, open_browser=a.open, log_file=a.log_file)


def _cmd_learn(a) -> int:
    """Dạy bot bản vẽ mới: PDF/TIFF/ảnh -> chuyển DXF (nếu cần) -> đọc khung tên + bảng -> mẫu nháp."""
    from .learn import learn_from_dxf
    from .template import LEARNED_DIR
    src = Path(a.file)
    dxfs = [src] if src.suffix.lower() == ".dxf" else [
        Path(r.output) for r in convert(src, ConvertOptions(out_dir=a.out)) if r.output]
    n = 0
    for d in dxfs:
        for r in learn_from_dxf(d, a.dest or LEARNED_DIR, source=src.name):
            n += 1
            print(f"+ {r['name']:32s} {r['rows']:3d} hàng, cột {', '.join(r['columns'])}  ({r['title']})")
    if not n:
        print("Không tìm thấy bảng tham số trên bản vẽ.")
    return 0 if n else 1


def _cmd_draw(a) -> int:
    from .template import TEMPLATES_DIR, Template, chat, list_templates
    if not a.template:
        for t in list_templates():
            print(f"{t.name:12s} {t.title}")
        return 0
    t = Template.load(Path(a.template) if Path(a.template).exists() else TEMPLATES_DIR / a.template)
    r = chat(t, " ".join(a.request), a.out)
    for n in r["notes"]:
        print("  " + n)
    for e in r["errors"]:
        print("LỖI: " + e)
    if r.get("output"):
        print("Tham số:", "  ".join(f"{k}={v:g}" for k, v in r["params"].items()))
        print("->", r["output"])
    return 1 if r["errors"] else 0


def utf8_stdio() -> None:
    """In tiếng Việt / Nhật an toàn khi output bị chuyển hướng (ống dẫn, công cụ quản lý tiến trình,
    dịch vụ): Windows khi đó dùng bảng mã cp1252 -> UnicodeEncodeError làm dừng server.
    Chuyển stdout/stderr sang UTF-8, ký tự không in được thì thay thế thay vì báo lỗi."""
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)   # hiện từng dòng ngay
        except (AttributeError, ValueError, OSError):
            pass


def main(argv=None) -> int:
    utf8_stdio()
    p = argparse.ArgumentParser(prog="pdf2cad", description="Chuyển bản vẽ PDF sang DXF")
    sub = p.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("convert", help="Chuyển PDF -> DXF (mỗi trang 1 file)")
    c.add_argument("pdf")
    c.add_argument("-o", "--out", default="out", help="Thư mục đầu ra")
    c.add_argument("--pages", help="Ví dụ: 1-3,5")
    c.add_argument("--scale", type=float, help="Ép tỷ lệ thật/giấy, vd 2 cho 1:2")
    c.add_argument("--dpi", type=int, default=300, help="DPI cho trang scan")
    c.add_argument("--workers", type=int, help="Số process song song")
    c.add_argument("--thick", type=float, default=0.5, help="Ngưỡng nét đậm (pt)")
    c.add_argument("--no-arcs", action="store_true", help="Không fit cung tròn")
    c.add_argument("--no-raster", action="store_true", help="Bỏ qua trang scan")
    c.add_argument("--ocr", choices=["auto", "on", "off"], default="auto",
                   help="Nhận dạng chữ khi PDF không có lớp text (chữ bị outline). Mặc định auto")
    c.add_argument("--ocr-dpi", type=int, default=300, help="DPI render cho OCR")
    c.add_argument("--no-rotate", action="store_true",
                   help="Không tự xoay bản vẽ theo /Rotate và hướng chữ")
    c.add_argument("--keep-stamps", action="store_true",
                   help="Bản scan: giữ dấu mộc (mặc định xoá mộc SPC/MassPro/chữ ký)")
    c.add_argument("--keep-glyphs", action="store_true",
                   help="Giữ nét chữ gốc (layer TEXT_GLYPH) để đối chiếu OCR; file DXF lớn hơn ~20 lần")
    c.add_argument("--json", action="store_true", help="In báo cáo dạng JSON")
    c.set_defaults(fn=_cmd_convert)

    i = sub.add_parser("inspect", help="Phân loại từng trang, đếm path, dò tỷ lệ")
    i.add_argument("pdf")
    i.set_defaults(fn=_cmd_inspect)

    bo = sub.add_parser("bench-ocr", help="Đo tốc độ OCR: CPU so với GPU (DirectML)")
    bo.add_argument("pdf")
    bo.add_argument("--page", type=int, default=1)
    bo.add_argument("--device", nargs="+", default=["cpu", "gpu"])
    bo.set_defaults(fn=_cmd_bench_ocr)

    b = sub.add_parser("bench", help="Benchmark tốc độ render/trích xuất")
    b.add_argument("pdf")
    b.add_argument("--dpi", type=int, default=150)
    b.add_argument("--repeat", type=int, default=3)
    b.set_defaults(fn=_cmd_bench)

    g = sub.add_parser("gui", help="Mở giao diện kéo thả")
    g.add_argument("files", nargs="*", help="File/thư mục PDF nạp sẵn")
    g.set_defaults(fn=_cmd_gui)

    w = sub.add_parser("serve", help="Chạy web server (kéo thả PDF trên trình duyệt)")
    w.add_argument("--host", default="127.0.0.1",
                   help="127.0.0.1 = chỉ máy này; 0.0.0.0 = cho cả mạng LAN")
    w.add_argument("--port", type=int, default=int(os.environ.get("PDF2CAD_PORT", 5020)))
    w.add_argument("--jobs", type=int, default=1, help="Số job chạy đồng thời (mặc định 1)")
    w.add_argument("--workers", type=int, help="Số process mỗi job (mặc định = số CPU)")
    w.add_argument("--data-dir", help="Thư mục lưu file tạm (mặc định ~/.pdf2cad_web)")
    w.add_argument("--max-upload", type=int, default=200, help="MB tối đa mỗi lần tải lên")
    w.add_argument("--ttl", type=float, default=24, help="Giờ giữ kết quả trước khi tự xoá")
    w.add_argument("--open", action="store_true", help="Tự mở trình duyệt khi server sẵn sàng")
    w.add_argument("--log-file", default=None, help="Ghi log ra file (dùng khi chạy nền trên server)")
    w.set_defaults(fn=_cmd_serve)

    le = sub.add_parser("learn", help="Dạy bot bản vẽ mới (đọc khung tên + bảng tham số)")
    le.add_argument("file", help="DXF đã chuyển, hoặc PDF/TIFF (sẽ chuyển trước)")
    le.add_argument("-o", "--out", default="out", help="Thư mục DXF trung gian")
    le.add_argument("--dest", help="Thư mục lưu mẫu (mặc định templates/learned)")
    le.set_defaults(fn=_cmd_learn)

    dr = sub.add_parser("draw", help="Vẽ lại bản vẽ từ mẫu tham số theo yêu cầu")
    dr.add_argument("template", nargs="?", help="Tên mẫu (bỏ trống: liệt kê mẫu)")
    dr.add_argument("request", nargs="*", help='Vd: "No.16 L=80 P=15"')
    dr.add_argument("-o", "--out", default="out")
    dr.set_defaults(fn=_cmd_draw)

    a = p.parse_args(argv)
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
