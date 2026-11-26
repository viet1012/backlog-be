"""Vector hoá trang PDF scan bằng OpenCV (THỬ NGHIỆM).

Pipeline: render trang -> grayscale -> nhị phân -> dò đoạn thẳng (LSD) + đường tròn (Hough).
Kết quả cần kiểm tra/chỉnh tay; chất lượng phụ thuộc mạnh vào chất lượng scan.
Bước tiếp theo trong roadmap: OCR text, tách khung tên, mô hình DL nhận diện ký hiệu.
"""
from __future__ import annotations

import math
import re

import numpy as np
import pymupdf

from .geometry import Circle, Line, PageGeometry, Polyline, Style, Text, merge_collinear

_STYLE_LINE = Style(layer="RASTER")               # nét xiên / không phân loại được
_STYLE_THICK = Style(layer="OUTLINE")             # nét đậm (đường bao)
_STYLE_THIN = Style(layer="THIN")                 # nét mảnh (kích thước, khung bảng)
_STYLE_HIDDEN = Style(layer="HIDDEN")             # nét đứt đều
_STYLE_CENTER = Style(layer="CENTER")             # chấm gạch (dài - ngắn xen kẽ)
_STYLE_REV = Style(layer="REVISION")

# chữ đặc trưng của dấu mộc. Ngày dạng số (15.05.2013) KHÔNG đủ vì bảng sửa đổi cũng có ngày.
STAMP_STRONG = re.compile(r"SPC|MASS\s*PRO|^PE$", re.I)
_CJK_ONES = {"一": 1, "二": 2, "三": 3}             # OCR đọc 1 / 11 / 111 (vạch đứng) thành chữ Hán
STAMP_MONTH = re.compile(r"(?<![A-Z])(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)(?![A-Z])", re.I)


def _require_cv2():
    try:
        import cv2  # noqa: F401
        return cv2
    except ImportError as e:  # pragma: no cover
        raise RuntimeError("Cần cài OpenCV: pip install 'pdf2cad[raster]'") from e


def render_gray(page: pymupdf.Page, dpi: int = 300) -> np.ndarray:
    """Render thẳng sang grayscale (1 kênh) để giảm 3 lần bộ nhớ và thời gian."""
    pix = page.get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY, alpha=False)
    return np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width)


def extract_raster_page(page: pymupdf.Page, dpi: int = 300,
                        min_line_mm: float = 2.0, detect_circles: bool = True,
                        stroke_mm: float = 0.6, min_circle_mm: float = 2.0,
                        circle_ink: float = 0.75, ocr: bool = False,
                        remove_stamps: bool = True) -> PageGeometry:
    """min_line_mm, stroke_mm, min_circle_mm tính theo mm TRÊN GIẤY (chưa nhân tỷ lệ).

    ocr=True: nhận dạng chữ trước, tạo TEXT (layer TEXT_OCR/TEXT_CHECK) rồi xoá vùng chữ khỏi ảnh
    để bước dò nét không biến chữ thành các vạch vụn.
    """
    cv2 = _require_cv2()
    from . import progress
    progress.stage(0.02, "Đọc ảnh scan")
    img = render_gray(page, dpi)
    h_px = img.shape[0]
    px_to_pt = 72.0 / dpi
    px_per_mm = dpi / 25.4
    img = _deskew(img, px_per_mm)                    # scan lệch vài phần chục độ -> đường xiên nhẹ
    texts: list[Text] = []
    marks: list = []
    if ocr:
        # (1) ký hiệu sửa đổi △n: nhận trước, xoá khỏi ảnh để OCR không đọc lẫn "△8 30/40" -> "830/40"
        progress.stage(0.04, "Tìm ký hiệu sửa đổi △")
        img, marks, mark_texts = _revision_marks(img, px_to_pt)
        # (2) OCR + (3) loại dấu mộc + xoá chữ khỏi ảnh trước khi dò nét
        img, texts = _ocr_and_erase(img, px_to_pt, remove_stamps=remove_stamps)
        texts += mark_texts

    progress.stage(0.92, "Dò nét vẽ")
    # Nhị phân hoá (Otsu) + khử nhiễu hạt nhỏ
    _, bw = cv2.threshold(img, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    bw = cv2.morphologyEx(bw, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))

    def to_cad(x, y):  # pixel -> point PDF, trục Y hướng lên
        return (x * px_to_pt, (h_px - y) * px_to_pt)

    geo = PageGeometry(page.rect.width, page.rect.height)
    geo.texts = texts
    geo.entities += marks

    mm_pt = 72.0 / 25.4
    min_len = min_line_mm * mm_pt
    # 1a) Đường ngang / dọc (khung bảng, đường bao, đường kích thước): lọc hình thái -> mỗi mảng
    #     liền là 1 đoạn thẳng đúng trục, có độ dày thật để phân loại nét đậm / mảnh
    axis_mask, axis_segs = _axis_lines(bw, px_per_mm, gray=img)
    # đoạn ngắn 0.8-2.5 mm: chấm / gạch ngắn của nét chấm gạch, nét khuất (chỉ dùng khi thuộc 1 dãy nét đứt)
    _, short_all = _axis_lines(bw, px_per_mm, min_mm=0.8, gray=img)
    short_segs = [s for s in short_all
                  if ((s[2] - s[0]) if s[5] else (s[3] - s[1])) < 2.5 * px_per_mm - 1]
    # ngưỡng nét đậm theo độ dày thực tế của trang (bản scan 200 dpi: mảnh ~3 px, đậm ~4.5 px)
    thick_px = _thick_threshold(axis_segs, px_per_mm)
    # 1b) Nét đứt: các đoạn thẳng hàng, cách đều -> 1 đường HIDDEN / CENTER
    solid, dashed, used_short = _group_dashes(axis_segs, px_per_mm, thick_px, short_segs)
    by_style: dict[str, list[Line]] = {}
    for (x1, y1, x2, y2), style in solid:
        by_style.setdefault(style.layer, []).append(Line(to_cad(x1, y1), to_cad(x2, y2), style))
    axis_lines: list[Line] = []
    for group in by_style.values():                  # nối khe hở nhỏ (≤ 0.4 mm) trên cùng 1 đường
        for ln in merge_collinear(group, angle_tol=0.5, offset_tol=0.3 * mm_pt, gap_tol=0.4 * mm_pt):
            if math.dist(ln.p1, ln.p2) >= min_len:
                axis_lines.append(ln)
    geo.entities += [Line(to_cad(x1, y1), to_cad(x2, y2), style) for (x1, y1, x2, y2), style in dashed]
    # 1c) Phần còn lại (nét xiên, cung nhỏ): LSD; đoạn gần trục (< 1.5°) ép về đúng trục
    rest = cv2.subtract(bw, cv2.dilate(axis_mask, np.ones((5, 5), np.uint8)))
    if used_short:                                   # chấm của nét chấm gạch đã dùng: không cho LSD nhặt lại
        dots = np.zeros_like(bw)
        for j in used_short:
            x1, y1, x2, y2, wd, _ = short_segs[j]
            cv2.line(dots, (int(x1), int(y1)), (int(x2), int(y2)), 255, max(1, int(round(wd))) + 4)
        rest = cv2.subtract(rest, dots)
    lsd = cv2.createLineSegmentDetector(cv2.LSD_REFINE_STD)
    lines = lsd.detect(255 - rest)[0]
    raw: list[Line] = []
    if lines is not None:
        for x1, y1, x2, y2 in lines.reshape(-1, 4):
            x1, y1, x2, y2 = _snap_axis(float(x1), float(y1), float(x2), float(y2))
            raw.append(Line(to_cad(x1, y1), to_cad(x2, y2), _STYLE_LINE))
    # LSD trả 2 cạnh cho mỗi nét dày + nhiều đoạn đứt => gộp lại thành đường giữa
    merged = merge_collinear(raw, angle_tol=1.5, offset_tol=stroke_mm * mm_pt,
                             gap_tol=stroke_mm * mm_pt)

    # 1d) Cung / đường tròn: chuỗi dây cung LSD (kể cả đoạn ngắn) + đoạn ngang/dọc nằm trên đường tròn
    fitted: list = []
    if detect_circles:
        from .scan_arcs import rebuild_arcs

        def thickness(pts_pt: np.ndarray, center_pt) -> float:
            """độ dày nét (px) dọc đường tròn: tổng độ đậm theo phương bán kính"""
            xs, ys = pts_pt[:, 0] / px_to_pt, h_px - pts_pt[:, 1] / px_to_pt
            cx, cy = center_pt[0] / px_to_pt, h_px - center_pt[1] / px_to_pt
            nx, ny = xs - cx, ys - cy
            nn = np.hypot(nx, ny) + 1e-9
            return gray_width(img, xs, ys, nx / nn, ny / nn)

        def centerline(pts_pt: np.ndarray, center_pt) -> np.ndarray:
            """dời từng điểm theo phương bán kính về trọng tâm độ đậm của nét (đường tâm mực)"""
            xs, ys = pts_pt[:, 0] / px_to_pt, h_px - pts_pt[:, 1] / px_to_pt
            cx, cy = center_pt[0] / px_to_pt, h_px - center_pt[1] / px_to_pt
            nx, ny = xs - cx, ys - cy
            nn = np.hypot(nx, ny) + 1e-9
            nx, ny = nx / nn, ny / nn
            t = np.arange(-6, 7, dtype=float)[None, :]
            X = np.clip(np.round(xs[:, None] + nx[:, None] * t).astype(int), 0, img.shape[1] - 1)
            Y = np.clip(np.round(ys[:, None] + ny[:, None] * t).astype(int), 0, img.shape[0] - 1)
            dark = (255.0 - img[Y, X]) / 255.0
            mass = dark.sum(axis=1)
            ok = mass >= 1.0                        # có nét thật quanh điểm
            off = (dark * t).sum(axis=1) / np.maximum(mass, 1e-9)
            xs2, ys2 = xs + nx * off, ys + ny * off
            return np.c_[xs2[ok] * px_to_pt, (h_px - ys2[ok]) * px_to_pt]

        # đoạn vụn < 0.8 mm (nhiễu, mép nét) làm đứt chuỗi dây cung -> không đưa vào nối chuỗi
        tiny = [l for l in merged if math.dist(l.p1, l.p2) < 0.8 * mm_pt]
        fitted, merged, axis_lines = rebuild_arcs(
            [l for l in merged if math.dist(l.p1, l.p2) >= 0.8 * mm_pt], axis_lines, thickness, thick_px,
            {"OUTLINE": _STYLE_THICK, "THIN": _STYLE_THIN}, centerline=centerline)
        merged += tiny
    geo.entities += axis_lines
    geo.entities += [l for l in merged if math.dist(l.p1, l.p2) >= min_len]
    geo.entities += fitted

    # 2) Đường tròn (Hough) – bỏ qua đường tròn đã dựng ở bước 1d
    if detect_circles:
        blur = cv2.medianBlur(img, 5)
        circles = cv2.HoughCircles(
            blur, cv2.HOUGH_GRADIENT_ALT, dp=1.5, minDist=px_per_mm * 2,
            param1=300, param2=0.85,
            minRadius=int(px_per_mm * min_circle_mm), maxRadius=int(px_per_mm * 300))
        if circles is not None:
            for x, y, r in circles.reshape(-1, 3):
                c, rr = to_cad(x, y), r * px_to_pt
                if any(isinstance(f, Circle) and math.dist(f.center, c) < max(0.5 * mm_pt, 0.05 * rr)
                       and abs(f.radius - rr) < max(0.5 * mm_pt, 0.05 * rr) for f in fitted):
                    continue
                # Kiểm tra lại: phần lớn chu vi phải có mực (loại chữ O, số 0, nhiễu)
                if _ink_ratio(bw, x, y, r) >= circle_ink:
                    geo.entities.append(Circle(c, rr, _STYLE_LINE))
    return geo


def _deskew(img: np.ndarray, px_per_mm: float, max_deg: float = 2.0, min_deg: float = 0.2) -> np.ndarray:
    """Đo góc nghiêng từ các đường dài gần ngang/dọc (khung, bảng) rồi xoay ảnh cho thẳng.

    Lệch < min_deg thì giữ nguyên: xoay (nội suy) làm mờ cạnh chữ nên OCR đọc khác đi, trong khi
    bước dò đường ngang/dọc vẫn chịu được lệch nhỏ.
    """
    import cv2
    _, bw = cv2.threshold(img, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    seg = cv2.createLineSegmentDetector(cv2.LSD_REFINE_NONE).detect(255 - bw)[0]
    if seg is None:
        return img
    s = seg.reshape(-1, 4).astype(float)
    dx, dy = s[:, 2] - s[:, 0], s[:, 3] - s[:, 1]
    length = np.hypot(dx, dy)
    ang = np.degrees(np.arctan2(dy, dx))
    dev = (ang + 45.0) % 90.0 - 45.0                 # lệch so với trục gần nhất, (-45, 45]
    ok = (length > 30 * px_per_mm) & (np.abs(dev) < max_deg)
    if ok.sum() < 3:
        return img
    order = np.argsort(dev[ok])                      # trung vị có trọng số theo chiều dài
    cw = np.cumsum(length[ok][order])
    angle = float(dev[ok][order][np.searchsorted(cw, cw[-1] / 2)])
    if abs(angle) < min_deg:
        return img
    h, w = img.shape
    M = cv2.getRotationMatrix2D((w / 2, h / 2), angle, 1.0)
    return cv2.warpAffine(img, M, (w, h), flags=cv2.INTER_LINEAR, borderValue=255)


def stroke_dist(bw: np.ndarray) -> np.ndarray:
    """Khoảng cách tới nền của từng điểm mực, lấy max trong ô 3x3 (điểm lấy mẫu lệch 1 px khỏi tâm
    nét vẫn đo đúng). Độ dày nét ≈ 2 * giá trị tại tâm nét - 1."""
    import cv2
    return cv2.dilate(cv2.distanceTransform(bw, cv2.DIST_L2, 3), np.ones((3, 3), np.uint8))


def gray_width(gray: np.ndarray, xs, ys, nx, ny, reach: int = 5) -> float:
    """Độ dày nét (px) = tổng độ đậm (0..1) của ảnh xám dọc pháp tuyến qua từng điểm, lấy trung vị.

    Chính xác dưới 1 px và không bị lượng tử hoá: ảnh scan 200 dpi phóng lên 300 dpi thì nét 2 px
    thành 3 hoặc 4 px nhị phân xen kẽ, nhưng tổng độ đậm luôn ≈ 3.0; nét 3 px -> ≈ 4.5."""
    xs, ys, nx, ny = (np.asarray(a, float).reshape(-1, 1) for a in (xs, ys, nx, ny))
    t = np.arange(-reach, reach + 1, dtype=float)[None, :]
    X = np.clip(np.round(xs + nx * t).astype(int), 0, gray.shape[1] - 1)
    Y = np.clip(np.round(ys + ny * t).astype(int), 0, gray.shape[0] - 1)
    dark = (255.0 - gray[Y, X]) / 255.0
    return float(np.median(dark.sum(axis=1))) if dark.size else 0.0


def _axis_lines(bw: np.ndarray, px_per_mm: float, min_mm: float = 2.5, gray: np.ndarray | None = None):
    """Tách đường ngang/dọc bằng phép mở hình thái (kernel min_mm).

    Trả về (mask các đường, [(x1, y1, x2, y2, độ dày px, ngang?)]). Mỗi mảng liền = 1 đoạn đúng trục,
    toạ độ theo đường tâm. Độ dày dùng để phân loại nét đậm (>= thick_mm) / mảnh.
    """
    import cv2
    L = max(3, int(min_mm * px_per_mm))
    hmask = cv2.morphologyEx(bw, cv2.MORPH_OPEN, np.ones((1, L), np.uint8))
    vmask = cv2.morphologyEx(bw, cv2.MORPH_OPEN, np.ones((L, 1), np.uint8))
    segs = []
    dist = stroke_dist(bw) if gray is None else None
    for mask, horiz in ((hmask, True), (vmask, False)):
        n, lab, stats, cents = cv2.connectedComponentsWithStats(mask, connectivity=8)
        for i in range(1, n):
            x, y, w, h, area = stats[i]
            # độ dày lấy trung vị dọc đường tâm (không lệch bởi chỗ giao nhau / đầu nét):
            # có ảnh xám -> tổng độ đậm theo pháp tuyến (chính xác dưới 1 px), không thì distance transform
            k = np.linspace(0.0, 1.0, int(min(max(w, h), 40)))
            if horiz:
                xs, ys = x + k * (w - 1), np.full(k.shape, cents[i][1])
            else:
                xs, ys = np.full(k.shape, cents[i][0]), y + k * (h - 1)
            if gray is not None:
                width = gray_width(gray, xs, ys, 0.0 if horiz else 1.0, 1.0 if horiz else 0.0)
            else:
                v = dist[np.round(ys).astype(int), np.round(xs).astype(int)]
                v = v[v > 0]
                width = float(2 * np.median(v) - 1) if v.size else area / max(w if horiz else h, 1)
            if horiz:
                segs.append((float(x), float(cents[i][1]), float(x + w - 1), float(cents[i][1]), width, True))
            else:
                segs.append((float(cents[i][0]), float(y), float(cents[i][0]), float(y + h - 1), width, False))
    return hmask | vmask, segs


def _thick_threshold(segs, px_per_mm: float, default_mm: float = 0.42, min_len_mm: float = 4.0) -> float:
    """Ngưỡng nét đậm / mảnh (px) cho từng trang: chia 2 nhóm độ dày (theo chiều dài nét).

    Bản scan 200 dpi phóng lên 300 dpi: nét mảnh ~3 px (0.25 mm), nét đậm ~4.3-5 px (0.36-0.42 mm)
    -> ngưỡng cố định 0.42 mm xếp gần hết đường bao vào nét mảnh. Không tách được 2 nhóm rõ ràng
    (trang chỉ có 1 loại nét) thì dùng ngưỡng mặc định.
    """
    default = default_mm * px_per_mm
    rows = [(s[4], (s[2] - s[0]) if s[5] else (s[3] - s[1])) for s in segs]
    # bỏ mảng tô / chữ đậm (dày > 0.8 mm): không phải nét vẽ, kéo lệch ngưỡng
    rows = [(w, length) for w, length in rows
            if length >= min_len_mm * px_per_mm and w <= 0.8 * px_per_mm]
    if len(rows) < 6:
        return default
    w = np.array([r[0] for r in rows])
    L = np.array([r[1] for r in rows])

    def wmedian(v, wt):
        o = np.argsort(v)
        c = np.cumsum(wt[o])
        return float(v[o][np.searchsorted(c, c[-1] / 2)])

    # nét mảnh (khung bảng, kích thước) chiếm phần lớn chiều dài -> trung vị theo chiều dài
    thin = wmedian(w, L)
    # nét đậm: dày hơn rõ rệt nhưng không quá 2.2 lần nét mảnh (bỏ mảng tô, góc khung); bản vẽ chi
    # tiết nhỏ có thể chỉ có vài đường bao giữa rất nhiều khung bảng -> chỉ cần >= 2 nét
    hi = (w >= thin + 0.8) & (w <= 2.2 * thin)
    if hi.sum() < 2:
        return default
    thick = wmedian(w[hi], L[hi])
    if thick / max(thin, 1e-6) < 1.25:
        return default
    return (thin + thick) / 2


def _cv(a) -> float:
    a = np.asarray(a, float)
    return float(a.std() / a.mean()) if a.size and a.mean() > 0 else 0.0


def _classify_run(lens, gaps, px_per_mm: float, hidden_max_mm: float = 8.0):
    """Kiểu nét của 1 dãy gạch thẳng hàng: HIDDEN (gạch đều, ngắn), CENTER (gạch dài đều + chấm /
    gạch ngắn xen giữa, hoặc gạch dài đều với khe rộng do chấm quá nhỏ), None = không phải nét đứt
    (vd đường liền bị chữ cắt ngang vài chỗ: gạch / khe không đều)."""
    lens, gaps = np.asarray(lens, float), np.asarray(gaps, float)
    inner = lens[1:-1] if len(lens) >= 7 else lens            # 2 gạch đầu / cuối hay bị cắt ngắn
    if _cv(gaps) > 0.6:
        return None
    if inner.max() <= hidden_max_mm * px_per_mm and _cv(inner) <= 0.45:
        return _STYLE_HIDDEN
    longs = inner[inner >= 0.5 * inner.max()]
    shorts = inner[inner < 0.5 * inner.max()]
    if len(longs) >= 2 and _cv(longs) <= 0.35:
        if len(shorts) >= 1 or (gaps.mean() >= 1.5 * px_per_mm and longs.mean() >= 6 * px_per_mm):
            return _STYLE_CENTER
    return None


def _group_dashes(segs, px_per_mm: float, thick_px: float | None = None, short_segs=(),
                  dash_max_mm: float = 30.0, gap_max_mm: float = 4.0, align_mm: float = 0.35,
                  thick_mm: float = 0.42):
    """Gom đoạn thẳng hàng, cách đều thành nét đứt (>= 3 gạch).

    segs: đoạn ngang/dọc >= 2.5 mm; short_segs: đoạn ngắn hơn (chấm / gạch ngắn của nét đứt, chỉ dùng
    khi nằm trong 1 dãy nét đứt). Gạch đều nhau -> HIDDEN; gạch dài xen chấm / gạch ngắn -> CENTER.
    thick_px: ngưỡng nét đậm (px), None = thick_mm.
    Trả về (đoạn liền [(xyxy, style)], nét đứt [(xyxy, style)], chỉ số short_segs đã dùng).
    """
    thick = thick_px if thick_px is not None else thick_mm * px_per_mm
    n_long = len(segs)
    allsegs = list(segs) + list(short_segs)
    solid, dashed, used = [], [], set()
    dash_max, gap_max, align = dash_max_mm * px_per_mm, gap_max_mm * px_per_mm, align_mm * px_per_mm
    seg_len = lambda s: (s[2] - s[0]) if s[5] else (s[3] - s[1])
    S = allsegs
    for horiz in (True, False):
        idx = [i for i, s in enumerate(S) if s[5] == horiz and seg_len(s) <= dash_max]
        # theo toạ độ ngang trục (y cho đường ngang), rồi theo vị trí dọc trục
        key = (lambda i: (S[i][1], S[i][0])) if horiz else (lambda i: (S[i][0], S[i][1]))
        idx.sort(key=key)
        rows: list[list[int]] = []
        cpos = (lambda j: S[j][1]) if horiz else (lambda j: S[j][0])
        for i in idx:
            c = cpos(i)
            for row in rows:
                # so với phần tử vừa thêm (ảnh còn lệch vài phần chục độ: nét đứt dài trôi dần 0.3-0.4 mm),
                # nhưng cả dãy không lệch quá 1 mm
                if abs(cpos(row[-1]) - c) <= align and abs(cpos(row[0]) - c) <= 1.0 * px_per_mm:
                    row.append(i)
                    break
            else:
                rows.append([i])
        for row in rows:
            row.sort(key=lambda i: S[i][0] if horiz else S[i][1])
            run = [row[0]]
            for i in row[1:] + [None]:
                prev = S[run[-1]]
                if i is not None:
                    s = S[i]
                    gap = (s[0] - prev[2]) if horiz else (s[1] - prev[3])
                    if 0 < gap <= gap_max:
                        run.append(i)
                        continue
                gap_of = (lambda a, b: S[b][0] - S[a][2]) if horiz else (lambda a, b: S[b][1] - S[a][3])
                # cắt 2 đầu dãy: gạch nối vào bằng khe lớn bất thường (nét chỉ dẫn, chữ) không thuộc nét đứt
                while len(run) >= 4:
                    gs = [gap_of(a, b) for a, b in zip(run, run[1:])]
                    med = float(np.median(gs))
                    lim = max(2.0 * med, med + 1.0 * px_per_mm)
                    ls = [seg_len(S[j]) for j in run]
                    lmax = 2.2 * float(np.median(ls))
                    # đầu dãy: khe / gạch bất thường (dài hơn hẳn mọi gạch còn lại, không chỉ 2 gạch kề:
                    # đường tâm gạch dài - chấm thì gạch dài đầu tiên vẫn là gạch thật)
                    if gs[0] > lim or ls[0] > max(lmax, 1.2 * max(ls[1:])):
                        run = run[1:]
                    elif gs[-1] > lim or ls[-1] > max(lmax, 1.2 * max(ls[:-1])):
                        run = run[:-1]
                    else:
                        break
                # dãy >= 3 gạch, trong đó >= 2 gạch dài; hoặc toàn gạch ngắn (nét khuất gạch 2 mm) thì
                # >= 4 gạch và dài tổng >= 8 mm (không nhận vài chấm vụn)
                extent = ((S[run[-1]][2] - S[run[0]][0]) if horiz else (S[run[-1]][3] - S[run[0]][1])) \
                    if run else 0
                if len(run) >= 3 and (sum(j < n_long for j in run) >= 2
                                      or (len(run) >= 4 and extent >= 8 * px_per_mm)):
                    lens = [seg_len(S[j]) for j in run]
                    gaps = [gap_of(a, b) for a, b in zip(run, run[1:])]
                    style = _classify_run(lens, gaps, px_per_mm)
                    if style is not None:
                        a, b = S[run[0]], S[run[-1]]
                        c = float(np.mean([S[j][1] if horiz else S[j][0] for j in run]))
                        xyxy = (a[0], c, b[2], c) if horiz else (c, a[1], c, b[3])
                        dashed.append((xyxy, style))
                        used.update(run)
                run = [i] if i is not None else []
    for i, s in enumerate(segs):
        if i not in used:
            solid.append((s[:4], _STYLE_THICK if s[4] >= thick else _STYLE_THIN))
    used_short = {i - n_long for i in used if i >= n_long}
    return solid, dashed, used_short


def _snap_axis(x1: float, y1: float, x2: float, y2: float, tol_deg: float = 1.5):
    """Đoạn lệch trục < tol_deg -> ép về đúng ngang/dọc (giữ trung điểm)."""
    ang = math.degrees(math.atan2(y2 - y1, x2 - x1))
    dev = (ang + 45.0) % 90.0 - 45.0
    if abs(dev) >= tol_deg:
        return x1, y1, x2, y2
    if abs(math.sin(math.radians(ang))) < 0.5:       # gần ngang
        ym = (y1 + y2) / 2
        return x1, ym, x2, ym
    xm = (x1 + x2) / 2
    return xm, y1, xm, y2


def _ink_ratio(bw: np.ndarray, cx: float, cy: float, r: float, samples: int = 180) -> float:
    """Tỷ lệ điểm trên chu vi có mực (cho phép lệch ±2px)."""
    h, w = bw.shape
    ang = np.linspace(0, 2 * np.pi, samples, endpoint=False)
    hit = np.zeros(samples, bool)
    for dr in (-2, -1, 0, 1, 2):
        xs = np.clip((cx + (r + dr) * np.cos(ang)).astype(int), 0, w - 1)
        ys = np.clip((cy + (r + dr) * np.sin(ang)).astype(int), 0, h - 1)
        hit |= bw[ys, xs] > 0
    return float(hit.mean())


def _ink_text(P: np.ndarray, d: np.ndarray, up: np.ndarray, text: str, h_px: int,
              px_to_pt: float, layer: str, quad: np.ndarray | None = None) -> Text:
    """Text đặt khít lên mực chữ: P = toạ độ px các điểm mực, d = hướng đọc, up = hướng lên."""
    from .ocr import _text_width, to_cad_text
    if len(P) >= 4:
        pd_, pu = P @ d, P @ up
        lo, hi = np.percentile(pu, [2, 98])
        start, length = float(np.percentile(pd_, 1)), float(np.ptp(np.percentile(pd_, [1, 99])))
        base, height = float(lo), float(hi - lo)
    else:
        qd, qu = quad @ d, quad @ up
        start, length = float(qd.min()), float(np.ptp(qd))
        height = float(np.ptp(qu)) * 0.7
        base = float(qu.min()) + height * 0.2
    ins = d * start + up * base                      # điểm chân trái chữ (px)
    rot = math.degrees(math.atan2(-d[1], d[0])) % 360.0
    q = round(rot / 90.0) * 90.0
    if abs(rot - q) <= 6.0:                          # bản scan hay lệch vài độ: làm thẳng
        rot = q % 360.0
    cad = to_cad_text(text)
    h_pt = max(height * px_to_pt, 0.8)
    tw = _text_width(cad, h_pt)
    wf = min(max(length * px_to_pt / tw, 0.4), 1.5) if tw else 1.0
    return Text((float(ins[0]) * px_to_pt, (h_px - float(ins[1])) * px_to_pt), cad,
                h_pt, round(rot, 2), Style(layer=layer), round(wf, 3))


def _revision_marks(img: np.ndarray, px_to_pt: float, min_mm: float = 3.0, max_mm: float = 9.0):
    """Ký hiệu sửa đổi: tam giác đều cỡ 3–9 mm có số 1–2 chữ số bên trong.

    Trả về (ảnh đã xoá các ký hiệu, [Polyline tam giác], [Text số]) — layer REVISION.
    Tam giác không có số (ký hiệu nhám ▽, ⚠...) giữ nguyên để các bước sau xử lý.
    """
    import cv2

    from .ocr import _engine, _rec

    px_mm = 72.0 / 25.4 / px_to_pt
    h_px = img.shape[0]
    _, ink = cv2.threshold(img, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    # lượt 2 trên ảnh đã bỏ đường kẻ dài: tam giác dính vào ô bảng (bảng sửa đổi) mới tách ra được
    Ll = int(6.5 * px_mm)
    straight = cv2.morphologyEx(ink, cv2.MORPH_OPEN, np.ones((1, Ll), np.uint8)) | \
        cv2.morphologyEx(ink, cv2.MORPH_OPEN, np.ones((Ll, 1), np.uint8))
    cnts = []
    no_lines = cv2.subtract(ink, straight)
    for src in (ink, no_lines):
        cnts += list(cv2.findContours(src, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE)[0])
    cands = []
    # tam giác kẹp trong ô bảng: bỏ đường kẻ thì đáy mất, còn "Λ" hở -> dựng lại tam giác
    n_cc, lab, stats, _ = cv2.connectedComponentsWithStats(no_lines, connectivity=8)
    for i in range(1, n_cc):
        x, y, w, h, area = stats[i]
        if not (min_mm <= max(w, h) / px_mm <= max_mm) or not (0.9 <= w / max(h, 1) <= 1.5):
            continue
        comp = lab[y:y + h, x:x + w] == i
        top = np.nonzero(comp[: max(2, h // 8)].any(axis=0))[0]
        bot = np.nonzero(comp[h - max(2, h // 6):].any(axis=0))[0]
        if len(top) == 0 or len(bot) == 0:
            continue
        apex_x = top.mean()
        if abs(apex_x - w / 2) > 0.2 * w or bot.min() > 0.2 * w or bot.max() < 0.8 * w:
            continue
        P = np.array([[x, y + h - 1], [x + w - 1, y + h - 1], [x + apex_x, y]], float)
        cands.append((0.5 * w * h, P))
    for c in cnts:
        x, y, w, h = cv2.boundingRect(c)
        if not (min_mm <= max(w, h) / px_mm <= max_mm):
            continue
        ap = cv2.approxPolyDP(c, 0.06 * cv2.arcLength(c, True), True)
        if len(ap) != 3:
            continue
        P = ap.reshape(-1, 2).astype(float)
        sides = [np.linalg.norm(P[i] - P[(i + 1) % 3]) for i in range(3)]
        if max(sides) / min(sides) > 1.35 or cv2.contourArea(ap) < 0.3 * w * h:
            continue
        cands.append((cv2.contourArea(ap), P))
    cands.sort(key=lambda t: -t[0])                  # viền ngoài trước, bỏ viền trong trùng
    out, ents, texts, done = img.copy(), [], [], []
    eng = None
    for _, P in cands:
        cen = P.mean(axis=0)
        if any(np.linalg.norm(cen - c2) < 0.3 * r2 for c2, r2 in done):
            continue
        # đáy = cạnh gần thẳng trục nhất; hướng "lên" = từ giữa đáy tới đỉnh, làm tròn về trục
        best = min(range(3), key=lambda i: min(abs(math.sin(math.atan2(*(P[(i + 1) % 3] - P[i])[::-1]))),
                                               abs(math.cos(math.atan2(*(P[(i + 1) % 3] - P[i])[::-1])))))
        apex = P[(best + 2) % 3]
        upv = apex - (P[best] + P[(best + 1) % 3]) / 2
        up = np.array([0.0, -1.0]) if abs(upv[1]) >= abs(upv[0]) and upv[1] < 0 else \
            np.array([0.0, 1.0]) if abs(upv[1]) >= abs(upv[0]) else \
            np.array([float(np.sign(upv[0])), 0.0])
        d = np.array([-up[1], up[0]])                # hướng đọc
        # vùng trong tam giác (co 18% về tâm) -> mực của chữ số
        m = np.zeros_like(ink)
        cv2.fillPoly(m, [P.astype(np.int32)], 255)
        side = min(np.linalg.norm(P[i] - P[(i + 1) % 3]) for i in range(3))
        cv2.polylines(m, [P.astype(np.int32)], True, 0, thickness=max(5, int(0.16 * side)))
        ys, xs = np.nonzero((m > 0) & (ink > 0))
        if len(xs) < 8:
            continue
        x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
        crop = np.full((y1 - y0 + 1, x1 - x0 + 1), 255, np.uint8)
        crop[ys - y0, xs - x0] = 0
        crop = cv2.copyMakeBorder(cv2.resize(crop, None, fx=3, fy=3, interpolation=cv2.INTER_NEAREST),
                                  12, 12, 12, 12, cv2.BORDER_CONSTANT, value=255)
        # xoay để "lên" của tam giác thành lên ảnh: rot90 k lần (ngược kim đồng hồ)
        k = {(0, -1): 0, (1, 0): 1, (0, 1): 2, (-1, 0): 3}[(int(up[0]), int(up[1]))]
        eng = eng or _engine()
        txt, sc = _rec(eng, cv2.cvtColor(np.ascontiguousarray(np.rot90(crop, k)), cv2.COLOR_GRAY2BGR))
        txt = txt.strip().translate(str.maketrans({"O": "0", "o": "0", "I": "1", "l": "1", "|": "1"}))
        if not re.fullmatch(r"\d{1,2}", txt) or sc < 0.6:
            continue
        if txt == "1":                               # "⚠": dấu ! = vạch + chấm (2 mảng mực rời)
            n_cc, _, stats, _ = cv2.connectedComponentsWithStats(255 - crop)
            if sum(1 for st in stats[1:] if st[4] > 4) >= 2:
                continue
        done.append((cen, max(np.linalg.norm(P - cen, axis=1))))
        ents.append(Polyline([(float(px) * px_to_pt, (h_px - float(py)) * px_to_pt) for px, py in P],
                             True, _STYLE_REV))
        texts.append(_ink_text(np.c_[xs, ys].astype(float), d, up, txt, h_px, px_to_pt, "REVISION"))
        grow = (cen + (P - cen) * 1.3).astype(np.int32)
        cv2.fillPoly(out, [grow], 255)
        cv2.polylines(out, [grow], True, 255, thickness=4)
    return out, ents, texts


def _find_stamps(img: np.ndarray, words, px_to_pt: float,
                 r_min_mm: float = 5.0, r_max_mm: float = 30.0) -> list[tuple[float, float, float]]:
    """Dấu mộc = vòng tròn 5–30 mm, chu vi phần lớn có mực (không tính đường kẻ thẳng), bên trong
    có chữ đặc trưng của mộc (SPC, MassPro, PE hoặc tên tháng như APR). Vòng tròn thật của bản vẽ
    không có các chữ này nên không bị xoá."""
    import cv2
    dpi = 72.0 / px_to_pt
    f = min(1.0, 150.0 / dpi)                       # dò trên ảnh ~150 dpi: nhanh hơn ~4-10 lần
    small = cv2.resize(img, None, fx=f, fy=f, interpolation=cv2.INTER_AREA) if f < 1 else img
    px_mm = dpi * f / 25.4
    _, bw = cv2.threshold(small, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    Ll = max(10, int(4 * px_mm))
    straight = cv2.morphologyEx(bw, cv2.MORPH_OPEN, np.ones((1, Ll), np.uint8)) | \
        cv2.morphologyEx(bw, cv2.MORPH_OPEN, np.ones((Ll, 1), np.uint8))
    arcs = cv2.subtract(bw, straight)               # bỏ đường kẻ bảng khỏi phép đo "tròn"
    rmin, rmax = int(r_min_mm * px_mm), int(r_max_mm * px_mm)
    found = []
    from . import progress
    est = small.shape[0] * small.shape[1] / 1e6 * 1.6       # ~giây/Hough (CPU)
    progress.creep(0, 0.5, est)
    c1 = cv2.HoughCircles(cv2.medianBlur(small, 3), cv2.HOUGH_GRADIENT_ALT, dp=1.5,
                          minDist=rmin, param1=300, param2=0.5, minRadius=rmin, maxRadius=rmax)
    progress.creep(0.5, 1.0, est)
    # Hough cổ điển: bắt được vòng mộc đứt nét / bị đường khung cắt (nhiều ứng viên giả -> lọc dưới)
    c2 = cv2.HoughCircles(cv2.GaussianBlur(small, (5, 5), 1.2), cv2.HOUGH_GRADIENT, dp=1.2,
                          minDist=int(4 * px_mm), param1=120, param2=30, minRadius=int(6 * px_mm),
                          maxRadius=rmax)
    for cs in (c1, c2):
        if cs is not None:
            found += [tuple(map(float, c)) for c in cs.reshape(-1, 3)]
    centers = [(np.asarray(w.quad, float).mean(axis=0) * f, w.text) for w in words]
    cands = []
    for x, y, r in found:
        ratio = _ink_ratio(arcs, x, y, r)
        if ratio < 0.45:
            continue
        inside = [t for c, t in centers if np.hypot(c[0] - x, c[1] - y) < r * 0.95]
        strong = sum(1 for t in inside if STAMP_STRONG.search(t.strip()))
        month = sum(1 for t in inside if STAMP_MONTH.search(t))
        if strong or month:
            cands.append((ratio + 0.1 * min(strong + month, 3), x, y, r))
    stamps = []                                      # mỗi mộc giữ 1 vòng khớp nhất
    for score, x, y, r in sorted(cands, reverse=True):
        if any(np.hypot(x - a, y - b) < max(r, rr) for a, b, rr in stamps):
            continue
        stamps.append((x, y, r))
    # mộc chữ ký đứng thành hàng (ô Check / Approved): vòng rất tròn, cùng cỡ, ngay cạnh 1 mộc đã
    # xác nhận cũng là mộc — dù OCR không đọc được chữ bên trong (mộc mờ, đè lên chữ khác)
    for x, y, r in sorted(found, key=lambda c: -_ink_ratio(arcs, *c)):
        if _ink_ratio(arcs, x, y, r) < 0.7:
            break
        if any(np.hypot(x - a, y - b) < max(r, rr) for a, b, rr in stamps):
            continue
        if any(abs(r - rr) < 0.25 * rr and abs(y - b) < rr and np.hypot(x - a, y - b) < 4 * rr
               for a, b, rr in stamps):
            stamps.append((x, y, r))
    return [(x / f, y / f, r / f) for x, y, r in stamps]


def _erase_stamps(img: np.ndarray, stamps) -> np.ndarray:
    """Xoá toàn bộ dấu mộc; giữ lại đường kẻ dài hơn đường kính mộc đi xuyên qua (khung bảng)."""
    import cv2
    if not stamps:
        return img
    _, ink = cv2.threshold(img, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    out = img.copy()
    for x, y, r in stamps:
        L = int(2.2 * r)
        keep = cv2.morphologyEx(ink, cv2.MORPH_OPEN, np.ones((1, L), np.uint8)) | \
            cv2.morphologyEx(ink, cv2.MORPH_OPEN, np.ones((L, 1), np.uint8))
        disk = np.zeros_like(ink)
        cv2.circle(disk, (int(x), int(y)), int(r * 1.08) + 3, 255, -1)
        out[(disk > 0) & (keep == 0)] = 255
    return out


def _table_cells(grid: np.ndarray, px_mm: float, min_mm: float = 2.5, max_h_mm: float = 40.0,
                 max_w_mm: float = 150.0, min_neighbors: int = 2) -> list[tuple[int, int, int, int]]:
    """Ô bảng = vùng trống khép kín bởi đường kẻ ngang/dọc, gần chữ nhật, nằm trong lưới (có ít nhất
    min_neighbors ô kề chung cạnh). grid: mask đường kẻ. Trả về [(x, y, w, h)] px (phần trong ô)."""
    import cv2
    free = (cv2.dilate(grid, np.ones((3, 3), np.uint8)) == 0).astype(np.uint8)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(free, connectivity=4)
    H, W = grid.shape
    cand = []
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if x == 0 or y == 0 or x + w >= W or y + h >= H:
            continue
        if not (min_mm * px_mm <= h <= max_h_mm * px_mm and min_mm * px_mm <= w <= max_w_mm * px_mm):
            continue
        cnts = cv2.findContours((lab[y:y + h, x:x + w] == i).astype(np.uint8), cv2.RETR_EXTERNAL,
                                cv2.CHAIN_APPROX_SIMPLE)[0]
        if cnts and sum(cv2.contourArea(c) for c in cnts) >= 0.85 * w * h:   # gần chữ nhật (bỏ lỗ chữ)
            cand.append((int(x), int(y), int(w), int(h)))
    tol = max(4, int(0.8 * px_mm))                   # bề rộng đường kẻ giữa 2 ô kề nhau

    def shares_edge(a, b):
        ax, ay, aw, ah = a
        bx, by, bw, bh = b
        ov_y = min(ay + ah, by + bh) - max(ay, by)
        ov_x = min(ax + aw, bx + bw) - max(ax, bx)
        side = (abs(bx - (ax + aw)) <= tol or abs(ax - (bx + bw)) <= tol) and ov_y >= 0.5 * min(ah, bh)
        updown = (abs(by - (ay + ah)) <= tol or abs(ay - (by + bh)) <= tol) and ov_x >= 0.5 * min(aw, bw)
        return side or updown

    cand.sort(key=lambda c: c[0])
    nb = [0] * len(cand)
    for i, a in enumerate(cand):                     # sắp theo x: chỉ so với ô gần theo chiều ngang
        for j in range(i + 1, len(cand)):
            b = cand[j]
            if b[0] > a[0] + a[2] + tol:
                break
            if shares_edge(a, b):
                nb[i] += 1
                nb[j] += 1
    # ô nằm sát bên phải ô khác nhưng bị cắt khỏi vòng lặp trên: duyệt thêm 1 lượt kiểu ngây thơ
    # chỉ cho các ô còn thiếu láng giềng (ít, nên không tốn thời gian)
    for i, a in enumerate(cand):
        if nb[i] < min_neighbors:
            nb[i] = sum(1 for j, b in enumerate(cand) if j != i and shares_edge(a, b))
    return [c for c, k in zip(cand, nb) if k >= min_neighbors]


def _reocr_cells(img: np.ndarray, words: list, cells, ink_free: np.ndarray, grid: np.ndarray,
                 px_mm: float, min_score: float) -> list:
    """OCR lại theo từng ô bảng:
    - chữ OCR trải qua >= 2 ô (hoặc nửa trong nửa ngoài bảng) => bỏ, đọc lại từng ô + phần ngoài bảng;
    - ô có mực nhưng không có chữ nào => đọc lại ô đó.
    ink_free: mực đã bỏ đường kẻ (để biết ô có chữ hay không). Trả về danh sách chữ mới."""
    import cv2

    from .ocr import OcrWord, _engine, _rec, ocr_image
    if not cells:
        return words
    eng = None
    C = np.array(cells, float)                       # x, y, w, h
    cx0, cy0, cx1, cy1 = C[:, 0], C[:, 1], C[:, 0] + C[:, 2], C[:, 1] + C[:, 3]

    def overlaps(q):
        x0, y0 = q.min(axis=0)
        x1, y1 = q.max(axis=0)
        iw = np.clip(np.minimum(x1, cx1) - np.maximum(x0, cx0), 0, None)
        ih = np.clip(np.minimum(y1, cy1) - np.maximum(y0, cy0), 0, None)
        return iw * ih / max((x1 - x0) * (y1 - y0), 1.0)          # phần của chữ nằm trong từng ô

    def read(crop_gray, ox, oy):
        """OCR 1 vùng -> các OcrWord toạ độ ảnh gốc.

        1 dòng chữ (thường gặp trong ô bảng): cắt sát vùng mực rồi nhận dạng thẳng, không chạy bước
        dò vùng chữ (nhanh ~20 lần). Nhiều dòng: dò + nhận dạng (phóng 2 lần nếu nhỏ)."""
        nonlocal eng
        _, m = cv2.threshold(crop_gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
        ys, xs = np.nonzero(m)
        if len(xs) < 4:
            return []
        if np.ptp(ys) < 4.0 * px_mm:                  # 1 dòng
            x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
            im = cv2.copyMakeBorder(crop_gray[y0:y1, x0:x1], 8, 8, 8, 8, cv2.BORDER_CONSTANT, value=255)
            if im.shape[0] < 48:                     # model nhận dạng làm việc tốt ở cao ~48 px
                f = 48 / im.shape[0]
                im = cv2.resize(im, None, fx=f, fy=f, interpolation=cv2.INTER_CUBIC)
            eng = eng or _engine()
            txt, sc = _rec(eng, cv2.cvtColor(im, cv2.COLOR_GRAY2BGR))
            if not txt.strip() or sc < min_score:
                return []
            q = np.array([[x0, y0], [x1, y0], [x1, y1], [x0, y1]], float) + (ox, oy)
            return [OcrWord(txt.strip(), sc, q, np.array([1.0, 0.0]))]
        pad = 12
        s = 2.0 if crop_gray.shape[0] < 3 * px_mm else 1.0
        im = cv2.resize(crop_gray, None, fx=s, fy=s, interpolation=cv2.INTER_CUBIC) if s != 1 else crop_gray
        im = cv2.copyMakeBorder(im, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=255)
        out = []
        for w in ocr_image(cv2.cvtColor(im, cv2.COLOR_GRAY2BGR), min_score):
            q = (np.asarray(w.quad, float) - pad) / s + (ox, oy)
            out.append(OcrWord(w.text, w.score, q, np.asarray(w.d, float)))
        return out

    redo, drop = set(), set()
    outside = []                                     # vùng chữ nằm ngoài bảng của chữ bị dính
    for k, w in enumerate(words):
        q = np.asarray(w.quad, float)
        ov = overlaps(q)
        hit = np.nonzero(ov >= 0.10)[0]
        inside = float(ov.sum())
        if len(hit) >= 2 or (len(hit) == 1 and 0.10 <= inside <= 0.80):
            drop.add(k)
            redo.update(int(i) for i in hit)
            if inside <= 0.80:
                outside.append(q)
    # ô có mực mà không có chữ nào đọc được (chữ bị bỏ sót). Ô này hay chứa ký hiệu / dấu mộc nên
    # chỉ nhận chữ đọc được rất chắc (>= 0.85)
    centers = [np.asarray(w.quad, float).mean(axis=0) for k, w in enumerate(words) if k not in drop]
    missed = set()
    for i, (x, y, w, h) in enumerate(cells):
        if i in redo:
            continue
        if (ink_free[y:y + h, x:x + w] > 0).sum() < 0.2 * px_mm * px_mm:
            continue
        if not any(x <= c[0] <= x + w and y <= c[1] <= y + h for c in centers):
            missed.add(i)
    redo |= missed
    if not redo and not outside:
        return words
    # ô đọc lại thì bỏ mọi chữ cũ có tâm trong ô (tránh lặp chữ)
    for k, w in enumerate(words):
        c = np.asarray(w.quad, float).mean(axis=0)
        if any(cells[i][0] <= c[0] <= cells[i][0] + cells[i][2] and
               cells[i][1] <= c[1] <= cells[i][1] + cells[i][3] for i in redo):
            drop.add(k)
    new = [w for k, w in enumerate(words) if k not in drop]
    for i in sorted(redo):
        x, y, w, h = cells[i]
        got = read(img[y:y + h, x:x + w], x, y)
        new += [g for g in got if g.score >= 0.85] if i in missed else got
    if outside:                                      # phần ngoài bảng: che các ô rồi đọc lại
        masked = img.copy()
        masked[cv2.dilate(grid, np.ones((3, 3), np.uint8)) > 0] = 255   # bỏ đường kẻ bảng
        for x, y, w, h in cells:
            masked[max(0, y - 3):y + h + 3, max(0, x - 3):x + w + 3] = 255
        for q in outside:
            x0, y0 = np.maximum(np.floor(q.min(axis=0)).astype(int) - 2, 0)
            x1, y1 = np.ceil(q.max(axis=0)).astype(int) + 2
            new += read(masked[y0:y1, x0:x1], x0, y0)
    return new


_EDGE_JUNK = set("[]|!Il1")


def _trimmed_junk(old: str, new: str) -> bool:
    """new = old bỏ bớt ký tự rác ở 2 đầu (vạch kẻ bảng đọc thành "[", "]", "|", "1", "I")."""
    if not new or new == old or len(new) >= len(old):
        return False
    i = old.find(new)
    return i >= 0 and set(old[:i] + old[i + len(new):]) <= _EDGE_JUNK


def _reocr_lines(img: np.ndarray, words: list, long_lines: np.ndarray, min_score: float) -> list:
    """Chữ chạm đường kẻ dài (>= ~8 mm: khung bảng, gạch chân, đường kích thước): nhận dạng lại trên
    ảnh đã xoá các đường kẻ đó. Nhận kết quả mới khi điểm tin cậy cao hơn, hoặc khi kết quả mới chỉ là
    bản cũ bỏ ký tự rác ở 2 đầu ("[. FUKASAWA]" -> "M. FUKASAWA", "…~1501" -> "…~150")."""
    import cv2

    from .ocr import OcrWord, _crop, _engine, _rec

    if not words or not long_lines.any():
        return words
    clean = None
    eng = None
    out = []
    for w in words:
        q = np.asarray(w.quad, float)
        x0, y0 = np.maximum(np.floor(q.min(axis=0)).astype(int) - 1, 0)
        x1, y1 = np.ceil(q.max(axis=0)).astype(int) + 2
        sub = long_lines[y0:y1, x0:x1]
        mask = np.zeros_like(sub)
        cv2.fillPoly(mask, [np.round(q - (x0, y0)).astype(np.int32)], 255)
        if not ((sub > 0) & (mask > 0)).any():
            out.append(w)
            continue
        if clean is None:
            clean = img.copy()
            clean[cv2.dilate(long_lines, np.ones((3, 3), np.uint8)) > 0] = 255
            clean = cv2.cvtColor(clean, cv2.COLOR_GRAY2BGR)
            eng = _engine()
        crop, u, v = _crop(clean, q)
        lu, lv = np.linalg.norm(u), np.linalg.norm(v)
        if lu < 2 or lv < 2:
            out.append(w)
            continue
        dirs = [u / lu, v / lv, -u / lu, -v / lv]          # np.rot90(crop, k) đọc theo dirs[k]
        k = int(np.argmax([float(np.asarray(w.d, float) @ dk) for dk in dirs]))
        txt, sc = _rec(eng, np.rot90(crop, k))
        old, new = w.text.strip(), txt.strip()
        # Chỉ nhận khi bỏ được rác do đường kẻ (ngoặc / vạch ở 2 đầu, "[" "]" "|" ở giữa) – không nhận
        # chỉ vì điểm cao hơn chút ít (vd "L (1Omm" -> "L(IOMm": điểm cao hơn nhưng sai hơn)
        junk_gone = bool(re.search(r"[\[\]|]", old)) and not re.search(r"[\[\]|]", new)
        if new and sc >= min_score and new != old and (
                (_trimmed_junk(old, new) and sc >= w.score - 0.05)
                or (junk_gone and sc >= w.score - 0.05)
                or sc >= w.score + 0.15):
            out.append(OcrWord(new, sc, w.quad, w.d))
        else:
            out.append(w)
    return out


def _ocr_and_erase(img: np.ndarray, px_to_pt: float, min_score: float = 0.6,
                   check_score: float = 0.85, remove_stamps: bool = True):
    """OCR ảnh scan -> danh sách Text (toạ độ PDF pt, Y lên) + ảnh đã xoá vùng chữ (và dấu mộc)."""
    import cv2

    from .ocr import LAYER_CHECK, LAYER_OCR, _accept, harmonize, ocr_image, postfix_text

    h_px = img.shape[0]
    px_mm = 72.0 / 25.4 / px_to_pt
    _, ink = cv2.threshold(img, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    # bỏ đường kẻ dài (khung bảng, đường kích thước) khỏi phép đo chiều cao chữ
    L = max(15, int(round(4.0 * px_mm)))                         # ~4 mm
    hv = lambda n: cv2.morphologyEx(ink, cv2.MORPH_OPEN, np.ones((1, n), np.uint8)) | \
        cv2.morphologyEx(ink, cv2.MORPH_OPEN, np.ones((n, 1), np.uint8))
    lines = hv(L)
    # đường kẻ cần GIỮ khi xoá chữ (khung bảng, đường kích thước đi qua vùng chữ): chữ nhỏ dùng
    # ngưỡng 3 mm, chữ to (nét dọc của chữ có thể dài 3-5 mm) dùng ngưỡng 8 mm
    keep_s, keep_l = hv(max(9, int(3.0 * px_mm))), hv(max(20, int(8.0 * px_mm)))
    ink = cv2.subtract(ink, cv2.dilate(lines, np.ones((3, 3), np.uint8)))
    from . import progress
    progress.stage(0.08, "Nhận dạng chữ (OCR)", 0.62)
    words = ocr_image(cv2.cvtColor(img, cv2.COLOR_GRAY2BGR), min_score)
    # đọc lại theo từng ô bảng: tách chữ bị dính qua 2 ô, nhặt chữ bị sót trong ô
    progress.stage(0.58, "Đọc lại từng ô bảng", 0.62)
    words = _reocr_cells(img, words, _table_cells(keep_s, px_mm), ink, keep_s, px_mm, min_score)
    # chữ dính đường kẻ dài (khung bảng, gạch chân): xoá đường kẻ rồi đọc lại -> bỏ "[", "]", "1" thừa
    words = _reocr_lines(img, words, keep_l, min_score)
    if remove_stamps:
        progress.stage(0.62, "Tìm và xoá dấu mộc", 0.9)
    stamps = _find_stamps(img, words, px_to_pt) if remove_stamps else []
    progress.stage(0.9, "Tạo chữ sửa được")
    out = _erase_stamps(img, stamps)

    def in_stamp(q):
        c = np.asarray(q, float).mean(axis=0)
        return any(np.hypot(c[0] - x, c[1] - y) < r * 1.02 for x, y, r in stamps)

    texts: list[Text] = []
    erase_s, erase_l = np.zeros_like(ink), np.zeros_like(ink)   # vùng chữ đã xoá (chữ nhỏ / to)
    for w in words:
        if in_stamp(w.quad):                         # chữ của dấu mộc: bỏ
            continue
        d = np.asarray(w.d, float)
        d /= np.linalg.norm(d) or 1.0
        up = np.array([d[1], -d[0]])                 # "lên" của chữ (ảnh: Y hướng xuống)
        quad = np.asarray(w.quad, np.float32)
        x0, y0 = np.maximum(np.floor(quad.min(axis=0)).astype(int), 0)
        x1, y1 = np.ceil(quad.max(axis=0)).astype(int) + 1
        sub_ink = ink[y0:y1, x0:x1]
        mask = np.zeros_like(sub_ink)
        cv2.fillPoly(mask, [np.round(quad - (x0, y0)).astype(np.int32)], 255)
        ys, xs = np.nonzero((mask > 0) & (sub_ink > 0))
        xs, ys = xs + x0, ys + y0
        P = np.c_[xs, ys].astype(float)
        txt = w.text.strip()
        # model OCR (có tiếng Trung) đọc "11" (2 vạch đứng) thành "二": mực cao hơn rộng => vạch đứng
        # là chữ số 1 (dấu "=" thì rộng hơn cao). Đặt lại hướng đọc ngang cho đúng.
        if txt in _CJK_ONES and len(xs) >= 4 and np.ptp(ys) >= 1.2 * max(np.ptp(xs), 1):
            txt = "1" * _CJK_ONES[txt]
            d, up = np.array([1.0, 0.0]), np.array([0.0, -1.0])
        aspect = float(np.ptp(P @ up) / max(np.ptp(P @ d), 1e-6)) if len(xs) >= 4 else None
        if not _accept(txt, w.score, min_score, aspect):
            continue
        # xoá chữ khỏi ảnh (nới 2px) để dò nét không nhặt nét chữ
        c = quad.mean(axis=0)
        grow = quad + (quad - c) / (np.linalg.norm(quad - c, axis=1, keepdims=True) + 1e-6) * 2
        big = float(np.ptp(quad @ up)) > 2.3 * px_mm
        cv2.fillPoly(erase_l if big else erase_s, [np.round(grow).astype(np.int32)], 255)
        text, suspicious = postfix_text(txt)         # "| |" -> "11", "l5O" -> "150"...
        layer = LAYER_OCR if w.score >= check_score and not suspicious else LAYER_CHECK
        texts.append(_ink_text(P, d, up, text, h_px, px_to_pt, layer, quad))
    harmonize(texts)                                 # SPLB-8 -> SPLB-B khi trang có SPLB-B đọc đúng nhiều lần
    if out is img:                                   # _erase_stamps trả lại chính ảnh khi không có mộc
        out = img.copy()
    out[(erase_s > 0) | (erase_l > 0)] = 255
    # trả lại các đường kẻ đi xuyên qua vùng chữ -> khung bảng không còn bị đứt
    restore = ((erase_s > 0) & (keep_s > 0)) | ((erase_l > 0) & (keep_l > 0))
    out[restore] = img[restore]
    return out, texts
