"""Tạo PDF bản vẽ mẫu để test.

Trang 1 (vector): tấm 100x60 mm, lỗ Ø20, góc lượn R10, đường tâm, nét khuất, khung tên SCALE 1:2.
Trang 2 (scan giả lập): ảnh render của trang 1 ở 200 dpi, có chút nhiễu.

Kích thước thật (mm) được vẽ ở tỷ lệ 1:2 => trên giấy là 50x30 mm.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pymupdf
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.units import mm
from reportlab.pdfgen import canvas

SCALE = 2.0  # 1:2


def real(v: float) -> float:
    """mm thật -> point trên giấy"""
    return v / SCALE * mm


def make_vector_page(path: Path) -> None:
    c = canvas.Canvas(str(path), pagesize=landscape(A4))
    ox, oy = 60 * mm, 80 * mm  # gốc chi tiết trên giấy

    # Viền ngoài nét đậm: tấm 100x60 có 1 góc lượn R10 (trên-phải)
    c.setLineWidth(0.7)
    p = c.beginPath()
    p.moveTo(ox, oy)
    p.lineTo(ox + real(100), oy)
    p.lineTo(ox + real(100), oy + real(50))
    p.arcTo(ox + real(80), oy + real(40), ox + real(100), oy + real(60), startAng=0, extent=90)
    p.lineTo(ox, oy + real(60))
    p.close()
    c.drawPath(p, stroke=1, fill=0)

    # Lỗ Ø20 tại (30, 30)
    c.circle(ox + real(30), oy + real(30), real(10), stroke=1, fill=0)
    # Rãnh 2 cạnh thẳng
    c.line(ox + real(60), oy + real(10), ox + real(85), oy + real(10))
    c.line(ox + real(60), oy + real(25), ox + real(85), oy + real(25))

    # Nét khuất (đứt đều), nét mảnh
    c.setLineWidth(0.25)
    c.setDash([3, 1.5])
    c.line(ox + real(60), oy + real(17.5), ox + real(85), oy + real(17.5))
    # Đường tâm (dài-ngắn-chấm-ngắn)
    c.setDash([6, 1.5, 1, 1.5])
    c.line(ox + real(15), oy + real(30), ox + real(45), oy + real(30))
    c.line(ox + real(30), oy + real(15), ox + real(30), oy + real(45))
    c.setDash([])

    # Kích thước + mũi tên tô đặc
    y = oy - 8 * mm
    c.line(ox, y, ox + real(100), y)
    for x, d in ((ox, 1), (ox + real(100), -1)):
        a = c.beginPath()
        a.moveTo(x, y)
        a.lineTo(x + d * 2.5 * mm, y + 0.8 * mm)
        a.lineTo(x + d * 2.5 * mm, y - 0.8 * mm)
        a.close()
        c.drawPath(a, stroke=0, fill=1)
    c.setFont("Helvetica", 9)
    c.drawCentredString(ox + real(50), y + 1.5 * mm, "100")
    c.drawString(ox + real(30) + real(12), oy + real(40), "Ø20")

    # Khung tên
    w, h = landscape(A4)
    c.setLineWidth(0.7)
    c.rect(w - 110 * mm, 10 * mm, 100 * mm, 25 * mm)
    c.setFont("Helvetica", 10)
    c.drawString(w - 105 * mm, 26 * mm, "PART: SAMPLE PLATE  DWG-0001")
    c.drawString(w - 105 * mm, 16 * mm, "SCALE")
    c.drawString(w - 85 * mm, 16 * mm, "1:2")
    c.save()


def append_scanned_page(path: Path, dpi: int = 200) -> None:
    doc = pymupdf.open(path)
    pix = doc[0].get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY)
    arr = np.frombuffer(pix.samples, np.uint8).reshape(pix.height, pix.width).copy()
    rng = np.random.default_rng(0)
    noise = rng.random(arr.shape) < 0.002          # nhiễu muối tiêu nhẹ như ảnh scan
    arr[noise] = 0
    img = pymupdf.Pixmap(pymupdf.csGRAY, pix.width, pix.height, arr.tobytes(), False)
    page = doc.new_page(width=doc[0].rect.width, height=doc[0].rect.height)
    page.insert_image(page.rect, pixmap=img)
    tmp = path.with_suffix(".tmp.pdf")
    doc.save(tmp)
    doc.close()
    tmp.replace(path)


def main(out: str = "samples/sample_drawing.pdf") -> Path:
    path = Path(out)
    path.parent.mkdir(parents=True, exist_ok=True)
    make_vector_page(path)
    append_scanned_page(path)
    return path


if __name__ == "__main__":
    print(main(*sys.argv[1:]))
