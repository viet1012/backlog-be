"""Xuất sơ đồ kiến trúc pdf2cad hiện tại ra PNG (cùng nội dung với kien-truc-hien-tai.html).

Chạy:  .venv\\Scripts\\python.exe docs\\make_arch_png.py   -> docs\\kien-truc-hien-tai.png
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

S = 2  # hệ số phóng (ảnh nét khi in / trình chiếu)
FD = Path(r"C:\Windows\Fonts")
F = lambda name, size: ImageFont.truetype(str(FD / name), size * S)
f_h1, f_sub = F("segoeuib.ttf", 28), F("segoeui.ttf", 14)
f_h2, f_h3 = F("segoeuib.ttf", 15), F("segoeuib.ttf", 13)
f_txt, f_small, f_mono = F("segoeui.ttf", 12), F("segoeui.ttf", 11), F("consola.ttf", 11)
f_big = F("segoeuib.ttf", 30)

C = dict(bg="#f3f6fa", panel="#ffffff", ink="#0f2238", muted="#5a6b80", line="#d4dde8",
         accent="#1662c4", soft="#e6effb", ok="#1f8a4c", ok_s="#e3f4ea", part="#b7791f",
         part_s="#fbf1df", no="#9aa5b4", no_s="#eef1f5")

ok, part, no = (lambda t: ("ok", t)), (lambda t: ("part", t)), (lambda t: ("no", t))
B = lambda n, title, pct, items, tech: dict(n=n, title=title, pct=pct, items=items, tech=tech)

block1 = B("1", "Input – Kho dữ liệu bản vẽ", 55, [
    ok("PDF vector (lệnh vẽ gốc)"), ok("Scan TIFF/PNG/JPG/BMP, TIFF nhiều trang"),
    ok("Kéo thả web (≤50 file, 200 MB), app desktop, CLI"),
    no("Kết nối thư mục mạng / NAS"), no("Quét hàng loạt kho >10.000 bản vẽ")],
    "classify.py · cli.py · web/app.py")
stages = [
    B("2.1", "Tiền xử lý & chuẩn hoá", 75, [
        ok("Phân loại trang vector / scan"), ok("Render ảnh 300 dpi"),
        ok("Đọc tỷ lệ “SCALE 1:2” từ khung tên, pt → mm"), ok("Tự xoay trang in nghiêng"),
        ok("Xoá dấu mộc, nhận ký hiệu sửa đổi Δn"), part("Deskew/denoise, tách từng hình chiếu")],
        "PyMuPDF · OpenCV · scale.py"),
    B("2.2", "Nhận dạng & hiểu bản vẽ", 45, [
        ok("OCR 4 lượt, chữ dọc, ký hiệu Ø ±"), ok("Chữ kém tin cậy → layer TEXT_CHECK"),
        ok("Đọc khung tên + bảng tham số → JSON (Dạy bot)"),
        part("Hiểu kích thước/dung sai: chỉ khi có mẫu"), no("VLM hiểu hình chiếu, kết cấu")],
        "RapidOCR (ONNX) · ocr.py · learn.py"),
    B("2.3", "Tạo mô hình & xuất file", 45, [
        ok("DXF từ PDF vector: đúng tuyệt đối"), part("DXF từ scan: sai số ~0.5 mm"),
        ok("Vẽ lại theo mẫu tham số (co giãn hình)"), ok("Vẽ mới sạch, DIMENSION thật"),
        no("Dựng 3D STEP / FCStd")], "ezdxf · template.py · gen.py"),
    B("2.4", "Kiểm tra & trả kết quả", 50, [
        ok("Đo lại từng kích thước sau khi vẽ"), ok("Ràng buộc bảng / NOTE, cảnh báo ngoài chuẩn"),
        ok("Báo cáo JSON, log xoay vòng"), no("So sánh tự động với bản gốc"), no("Lưu phiên bản")],
        "template.py · progress.py")]
block3 = B("3", "Output – File CAD", 40, [
    ok("DXF 2D – AutoCAD 2007, mm 1:1, layer chuẩn"), no("STEP (3D)"), no("FCStd (3D)"),
    ok("Xem trước PDF/DXF, bật/tắt layer"), ok("Tải từng file hoặc zip"),
    part("Metadata khung tên: chỉ khi Dạy bot")], "dxf_writer.py · /api/jobs/{id}/zip")
row2 = [
    B("4", "Cơ sở dữ liệu & quản lý dữ liệu", 15, [
        part("File tạm theo job, tự xoá sau 24h"), part("Mẫu lưu dạng thư mục JSON"),
        no("PostgreSQL (metadata, log, feedback)"), no("Vector DB tìm kiếm bản vẽ"),
        no("Quản lý phiên bản")], "~/.pdf2cad_web · templates/learned/"),
    B("5", "Điều phối (Orchestration)", 40, [
        ok("Hàng đợi job + chạy song song theo trang"), ok("API FastAPI: jobs, bot, templates"),
        ok("Bot theo luật: nhớ ngữ cảnh, hỏi lại, “tăng L thêm 10”"),
        no("LLM hiểu yêu cầu tự do"), no("LangChain / Ollama")], "pipeline.py · bot.py · FastAPI"),
    B("6", "Model OCR / AI", 20, [
        ok("PP-OCR nhúng sẵn, chạy offline"), part("GPU DirectML, tự quay về CPU"),
        no("Server model riêng"), no("PaddleOCR-VL, Qwen-VL"),
        no("Model sinh CAD (CADFS, Pen2STEP)")], "rapidocr · onnxruntime(-directml)"),
    B("7", "Hạ tầng & vận hành", 60, [
        ok("Windows service (Task Scheduler), tự chạy lại"), ok("Firewall giới hạn mạng LAN"),
        ok("Gói cài offline, Dockerfile"), ok("Script status / start / stop / update"),
        part("GPU qua DirectML, chưa CUDA"), no("Giám sát tài nguyên, backup, đăng nhập")],
        "deploy/*.bat · Dockerfile"),
    B("8", "Người dùng & feedback", 45, [
        ok("Web nội bộ: tải lên, tiến độ, xem trước, tải về"), ok("App desktop kéo thả"),
        ok("Trợ lý vẽ lại + Dạy bot bản vẽ mới"), no("Đánh giá đúng/sai, ghi chú sửa lỗi"),
        no("Lịch sử lâu dài"), no("Tự học từ feedback")], "web/static · gui.py")]
tech = ["Python 3.10+", "PyMuPDF", "ezdxf", "OpenCV", "NumPy", "RapidOCR (PP-OCR)", "ONNX Runtime",
        "DirectML", "FastAPI", "Uvicorn", "PySide6", "Docker"]
gaps = ["Chưa có 3D (STEP / FCStd)", "Chưa có CSDL: kết quả tự xoá sau 24h",
        "Chưa có LLM/VLM: bot chạy theo luật", "Chưa thu thập feedback người dùng",
        "Chưa đọc trực tiếp kho NAS"]
nexts = ["3D cho chi tiết tròn xoay: gen.py đã có đủ tham số, chỉ cần revolve bằng CadQuery/FreeCAD → STEP.",
         "Lưu lịch sử + feedback: SQLite/PostgreSQL cho job, kết quả, đánh giá đúng/sai.",
         "LLM cục bộ (Ollama + Qwen) hiểu yêu cầu tự do, gọi bot/template hiện có làm công cụ.",
         "Batch từ NAS: quét thư mục mạng, chạy hàng loạt qua pipeline sẵn có."]

W, PAD = 1320 * S, 24 * S
img = Image.new("RGB", (W, 4000 * S), C["bg"])
d = ImageDraw.Draw(img)


def wrap(text, font, width):
    out, cur = [], ""
    for w in text.split(" "):
        t = (cur + " " + w).strip()
        if font.getlength(t) <= width or not cur:
            cur = t
        else:
            out.append(cur)
            cur = w
    return out + [cur]


def lh(font):
    a, b = font.getmetrics()
    return a + b + 2 * S


def icon(x, y, kind):
    r = 8 * S
    fg, bg = C[kind], C[kind + "_s"]
    d.ellipse((x, y, x + 2 * r, y + 2 * r), fill=bg)
    cx, cy = x + r, y + r
    if kind == "ok":
        d.line([(cx - 4 * S, cy), (cx - 1 * S, cy + 3 * S), (cx + 4 * S, cy - 3 * S)], fill=fg, width=2 * S)
    elif kind == "part":
        d.ellipse((cx - 5 * S, cy - 5 * S, cx + 5 * S, cy + 5 * S), outline=fg, width=int(1.5 * S))
        d.pieslice((cx - 5 * S, cy - 5 * S, cx + 5 * S, cy + 5 * S), 90, 270, fill=fg)
    else:
        k = 3.5 * S
        d.line([(cx - k, cy - k), (cx + k, cy + k)], fill=fg, width=2 * S)
        d.line([(cx - k, cy + k), (cx + k, cy - k)], fill=fg, width=2 * S)


def block(x, y, w, b, small=False, draw=True):
    """Vẽ 1 khối; trả về chiều cao. draw=False chỉ đo."""
    p = 12 * S
    iw = w - 2 * p
    yy = y + p
    title = f"{b['n']}. {b['title']}"
    tf = f_h3 if small else f_h2
    for ln in wrap(title, tf, iw):
        if draw:
            d.text((x + p, yy), ln, font=tf, fill=C["ink"] if small else C["accent"])
        yy += lh(tf)
    yy += 4 * S
    if draw:  # thanh tiến độ
        bw = iw - 44 * S
        d.rounded_rectangle((x + p, yy + 4 * S, x + p + bw, yy + 10 * S), 3 * S, fill=C["no_s"])
        d.rounded_rectangle((x + p, yy + 4 * S, x + p + bw * b["pct"] / 100, yy + 10 * S), 3 * S, fill=C["accent"])
        d.text((x + p + iw, yy - 1 * S), f"~{b['pct']}%", font=f_mono, fill=C["muted"], anchor="ra")
    yy += 20 * S
    for kind, t in b["items"]:
        if draw:
            icon(x + p, yy + 1 * S, kind)
        for ln in wrap(t, f_txt, iw - 24 * S):
            if draw:
                d.text((x + p + 24 * S, yy), ln, font=f_txt, fill=C["muted"] if kind == "no" else C["ink"])
            yy += lh(f_txt)
        yy += 4 * S
    yy += 4 * S
    tech_y = yy
    yy += 8 * S
    for ln in wrap(b["tech"], f_mono, iw):
        if draw:
            d.text((x + p, yy), ln, font=f_mono, fill=C["muted"])
        yy += lh(f_mono)
    if draw:
        for xx in range(x + p, x + p + iw, 8 * S):
            d.line([(xx, tech_y), (xx + 4 * S, tech_y)], fill=C["line"], width=S)
    return yy + p - y


def panel(x, y, w, h, fill=None):
    d.rounded_rectangle((x, y, x + w, y + h), 10 * S, fill=fill or C["panel"], outline=C["line"], width=S)


def arrow(x, y, w):
    cy = y
    d.line([(x + 4 * S, cy), (x + w - 8 * S, cy)], fill=C["accent"], width=3 * S)
    d.polygon([(x + w - 4 * S, cy), (x + w - 12 * S, cy - 6 * S), (x + w - 12 * S, cy + 6 * S)], fill=C["accent"])


# ---- tiêu đề
y = PAD
d.text((PAD, y), "pdf2cad – Kiến trúc hiện tại", font=f_h1, fill=C["ink"])
y += lh(f_h1) + 2 * S
d.text((PAD, y), "Đối chiếu code hiện có với sơ đồ “Local AI CAD Agent” | Chạy offline, CPU hoặc GPU (DirectML)",
       font=f_sub, fill=C["muted"])
# ô tổng
bx, bw_, bh = W - PAD - 340 * S, 340 * S, 66 * S
panel(bx, PAD, bw_, bh)
d.text((bx + 16 * S, PAD + 12 * S), "~40%", font=f_big, fill=C["accent"])
for i, ln in enumerate(wrap("Mức đáp ứng ước lượng so với toàn bộ sơ đồ mục tiêu (đánh giá định tính, không phải số đo)",
                            f_small, 220 * S)):
    d.text((bx + 106 * S, PAD + 12 * S + i * lh(f_small)), ln, font=f_small, fill=C["muted"])
y = PAD + bh + 12 * S
lx = PAD
for kind, t in (("ok", "Đã có"), ("part", "Một phần"), ("no", "Chưa có")):
    icon(lx, y + 1 * S, kind)
    d.text((lx + 22 * S, y), t, font=f_txt, fill=C["muted"])
    lx += 22 * S + int(f_txt.getlength(t)) + 22 * S
y += 30 * S

# ---- hàng 1: Input -> Pipeline -> Output
AW = 30 * S
inner = W - 2 * PAD - 2 * AW
w1 = int(inner * 1 / 5.4)
wp = inner - 2 * w1
gap = 10 * S
sw = (wp - 2 * 12 * S - 3 * gap) // 4
head_h = lh(f_h2) + 10 * S
h_stage = max(block(0, 0, sw, s, True, False) for s in stages)
h_row1 = max(block(0, 0, w1, block1, draw=False), block(0, 0, w1, block3, draw=False),
             12 * S + head_h + h_stage + 12 * S)
x = PAD
panel(x, y, w1, h_row1); block(x, y, w1, block1)
x += w1; arrow(x, y + h_row1 // 2, AW); x += AW
panel(x, y, wp, h_row1, C["soft"])
d.text((x + 12 * S, y + 12 * S), "2. Pipeline xử lý (pipeline.py – song song theo trang)", font=f_h2, fill=C["accent"])
sx = x + 12 * S
for i, s in enumerate(stages):
    panel(sx, y + 12 * S + head_h, sw, h_row1 - 24 * S - head_h)
    block(sx, y + 12 * S + head_h, sw, s, True)
    if i < 3:
        arrow(sx + sw - 2 * S, y + 12 * S + head_h + 22 * S, gap + 4 * S)
    sx += sw + gap
x += wp; arrow(x, y + h_row1 // 2, AW); x += AW
panel(x, y, w1, h_row1); block(x, y, w1, block3)
y += h_row1 + 14 * S

# ---- hàng 2: 5 khối nền
g2 = 12 * S
w2 = (W - 2 * PAD - 4 * g2) // 5
h2 = max(block(0, 0, w2, b, draw=False) for b in row2)
for i, b in enumerate(row2):
    xx = PAD + i * (w2 + g2)
    panel(xx, y, w2, h2); block(xx, y, w2, b)
y += h2 + 14 * S

# ---- hàng 3: công nghệ / khoảng trống / đề xuất
ws = [(W - 2 * PAD - 2 * g2) * k // 35 for k in (12, 10, 13)]
p = 14 * S


def col_tech(x, y, w, draw):
    yy = y + p
    if draw: d.text((x + p, yy), "Công nghệ đang dùng", font=f_h2, fill=C["accent"])
    yy += lh(f_h2) + 6 * S
    cx = x + p
    ch = lh(f_mono) + 6 * S
    for t in tech:
        tw = int(f_mono.getlength(t)) + 14 * S
        if cx + tw > x + w - p:
            cx = x + p; yy += ch + 6 * S
        if draw:
            d.rounded_rectangle((cx, yy, cx + tw, yy + ch), 5 * S, fill=C["soft"])
            d.text((cx + 7 * S, yy + 3 * S), t, font=f_mono, fill=C["ink"])
        cx += tw + 6 * S
    yy += ch + 10 * S
    for ln in wrap("Chưa dùng: PyTorch, Hugging Face, LLM/VLM, PostgreSQL, Vector DB, FreeCAD, CUDA.", f_small, w - 2 * p):
        if draw: d.text((x + p, yy), ln, font=f_small, fill=C["muted"])
        yy += lh(f_small)
    return yy + p - y


def col_list(x, y, w, title, items, numbered, draw):
    yy = y + p
    if draw: d.text((x + p, yy), title, font=f_h2, fill=C["accent"])
    yy += lh(f_h2) + 6 * S
    for i, t in enumerate(items, 1):
        mark = f"{i}." if numbered else "•"
        if draw: d.text((x + p, yy), mark, font=f_txt, fill=C["accent"] if numbered else C["ink"])
        for ln in wrap(t, f_txt, w - 2 * p - 20 * S):
            if draw: d.text((x + p + 20 * S, yy), ln, font=f_txt, fill=C["ink"])
            yy += lh(f_txt)
        yy += 4 * S
    return yy + p - y


cols = [lambda x, y, w, dr: col_tech(x, y, w, dr),
        lambda x, y, w, dr: col_list(x, y, w, "Khoảng trống lớn nhất", gaps, False, dr),
        lambda x, y, w, dr: col_list(x, y, w, "Đề xuất bước tiếp theo", nexts, True, dr)]
h3 = max(fn(0, 0, w, False) for fn, w in zip(cols, ws))
xx = PAD
for fn, w in zip(cols, ws):
    panel(xx, y, w, h3); fn(xx, y, w, True); xx += w + g2
y += h3 + 12 * S
d.text((PAD, y), "Nguồn: mã nguồn pdf2cad (src/pdf2cad, deploy/, templates/) tại thời điểm khảo sát. Tỷ lệ % là ước lượng định tính.",
       font=f_small, fill=C["muted"])
y += lh(f_small) + PAD

out = Path(__file__).with_name("kien-truc-hien-tai.png")
img.crop((0, 0, W, y)).save(out, optimize=True)
print(out, img.crop((0, 0, W, y)).size)
