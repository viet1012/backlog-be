"""OCR cho bản vẽ PDF mà chữ đã bị "outline" (chữ là hình tô đặc, không có lớp text).

Thường gặp khi in PDF bằng "Microsoft Print to PDF" hoặc xuất từ Solid Edge / Inventor... với
tuỳ chọn chuyển chữ thành hình học. Khi đó DXF chỉ có HATCH, không sửa được chữ.

Quy trình:
1. Render trang (ảnh xám) -> RapidOCR phát hiện khung chữ (mô hình kèm sẵn, chạy offline).
2. Mỗi khung: thử nhận dạng theo các hướng 0/90/180/270°, giữ hướng điểm cao nhất
   => biết chữ đọc theo chiều nào (chữ dọc trên bản vẽ vẫn ra TEXT đúng góc xoay).
3. Các hình tô đặc nằm gọn trong khung chữ = nét chữ gốc -> layer TEXT_GLYPH (tắt sẵn).
4. Điểm chèn / chiều cao TEXT lấy theo bao của nét chữ gốc => chồng khít lên bản vẽ.

Cần: pip install "pdf2cad[ocr]"  (rapidocr + onnxruntime)
"""
from __future__ import annotations

import math
import os
import re
import unicodedata
from dataclasses import dataclass

import numpy as np
import pymupdf

from .geometry import Line, PageGeometry, Polyline, Style, Text

LAYER_OCR = "TEXT_OCR"          # chữ OCR tin cậy
LAYER_CHECK = "TEXT_CHECK"      # chữ OCR độ tin cậy thấp -> cần soát lại
LAYER_GLYPH = "TEXT_GLYPH"      # nét chữ gốc (hình tô đặc) đã được thay bằng TEXT

_ENGINE = None


def available() -> bool:
    try:
        import rapidocr  # noqa: F401
        return True
    except ImportError:
        return False


def ocr_device() -> str:
    """Thiết bị chạy OCR, chọn bằng biến môi trường PDF2CAD_OCR_DEVICE:
    auto (mặc định: ưu tiên GPU khi máy có card đồ hoạ rời + DirectML, lỗi thì CPU) | gpu (luôn thử
    GPU, kể cả card tích hợp) | cpu. GPU = DirectML trên Windows, cần gói onnxruntime-directml."""
    return os.environ.get("PDF2CAD_OCR_DEVICE", "auto").strip().lower() or "auto"


# card rời: NVIDIA, AMD Radeon RX / Pro, Intel Arc A/B. Card tích hợp (Intel Graphics, AMD Radeon
# Graphics) dùng chung RAM: đo thực tế OCR chậm hơn CPU ~3 lần => auto không dùng.
_DISCRETE_RE = re.compile(r"NVIDIA|GeForce|Quadro|RTX|Radeon.*\b(RX|Pro)\b|Arc\(TM\)\s*[AB]\d", re.I)


def gpu_names() -> list[str]:
    """Tên các card đồ hoạ (Windows: đọc registry, nhanh, không chạy lệnh ngoài)."""
    names = []
    try:
        import winreg
        base = r"SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}"
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, base) as k:
            for i in range(64):
                try:
                    sub = winreg.EnumKey(k, i)
                except OSError:
                    break
                try:
                    with winreg.OpenKey(k, sub) as s:
                        names.append(str(winreg.QueryValueEx(s, "DriverDesc")[0]))
                except OSError:
                    continue
    except Exception:
        pass
    return names


def has_discrete_gpu() -> bool:
    return any(_DISCRETE_RE.search(n) for n in gpu_names())


def _dml_available() -> bool:
    try:
        import onnxruntime as ort
        return "DmlExecutionProvider" in ort.get_available_providers()
    except Exception:
        return False


def _engine_params(device: str) -> dict:
    # 5000 px: không thu nhỏ trang A4/A3 300 dpi. Giảm (vd 3000) nhanh hơn nhưng dung sai chữ nhỏ dễ vỡ
    p = {"Global.max_side_len": int(os.environ.get("PDF2CAD_OCR_MAX_SIDE", 5000)),
         "Global.log_level": "warning"}
    if device in ("gpu", "dml", "directml"):
        p["EngineConfig.onnxruntime.use_dml"] = True
    return p


ENGINE_INFO = {"device": "cpu"}


def _engine():
    global _ENGINE
    if _ENGINE is None:
        import logging

        from rapidocr import RapidOCR
        logging.getLogger("RapidOCR").setLevel(logging.WARNING)
        dev = ocr_device()
        if dev in ("gpu", "dml", "directml") or (dev == "auto" and _dml_available() and has_discrete_gpu()):
            try:
                eng = RapidOCR(params=_engine_params("gpu"))
                # chạy thử 1 lần: driver/GPU lỗi thì phát hiện ngay, không làm hỏng cả lô
                test = np.full((64, 256, 3), 255, np.uint8)
                test[24:40, 20:200] = 0
                eng(test, use_det=True, use_cls=False, use_rec=True)
                sess = getattr(eng.text_det.session, "session", None)
                prov = sess.get_providers() if sess is not None else []
                ENGINE_INFO.update(device="gpu" if any("Dml" in x for x in prov) else "cpu",
                                   providers=prov)
                if ENGINE_INFO["device"] == "cpu":
                    logging.getLogger(__name__).warning(
                        "Không có DirectML (cần: pip install onnxruntime-directml) -> OCR chạy CPU")
                _ENGINE = eng
            except Exception as e:                 # thiếu driver/gói GPU => quay về CPU
                logging.getLogger(__name__).warning("OCR trên GPU lỗi (%s) -> dùng CPU", e)
        if _ENGINE is None:
            _ENGINE = RapidOCR(params=_engine_params("cpu"))
            ENGINE_INFO["device"] = "cpu"
    return _ENGINE


def _best_turn(eng, im: np.ndarray, align: list[float], bonus: float = 0.12,
               stop: float = 0.93) -> tuple[str, float, int]:
    """Nhận dạng ảnh chữ theo các hướng xoay k=0..3; thử hướng chữ chủ đạo trước,
    đủ chắc (>= stop) thì dừng — giảm ~3 lần số lần nhận dạng cho chữ ngắn."""
    order = sorted(range(4), key=lambda k: -align[k])
    best = None
    for k in order:
        txt, sc = _rec(eng, np.rot90(im, k))
        cand = (sc + (bonus if align[k] > 0.7 else 0.0), txt, sc, k)
        best = cand if best is None or cand > best else best
        if align[k] > 0.7 and sc >= stop and txt.strip():
            break
    return best[1], best[2], best[3]


@dataclass
class OcrWord:
    text: str
    score: float
    quad: np.ndarray        # 4 điểm khung (toạ độ ảnh, px)
    d: np.ndarray           # vector đơn vị hướng đọc (toạ độ ảnh, Y xuống)


# ---------------------------------------------------------------- chuẩn hoá text cho CAD
_CAD_MAP = {"Φ": "%%c", "φ": "%%c", "Ø": "%%c", "ø": "%%c", "⌀": "%%c", "±": "%%p", "°": "%%d",
            "≦": "≤", "≧": "≥", "×": "x", "¥": "\\",
            "〜": "~", "∼": "~", "～": "~", "|": "1"}


_ONE_RE = re.compile(r"(?<=[\d.,~±])[Il](?![A-Za-z])|(?<![A-Za-z])[Il](?=[\d.,~])")


_NUMSP_RE = re.compile(r"(?<=\d[.,]) +(?=\d)")
_DL_RE = re.compile(r"(?<=%%c[A-Za-z])[lI](?![A-Za-z])")


def to_cad_text(s: str) -> str:
    """Chuẩn hoá: ký tự full-width -> thường (NFKC: ～ -> ~, （ -> ( ...), Φ -> %%c, ± -> %%p."""
    s = "".join(_CAD_MAP.get(ch, ch) for ch in s)
    s = unicodedata.normalize("NFKC", s)
    # font CAD vẽ số 1 giống chữ I / l: sửa khi đứng cạnh chữ số / dấu chấm
    s = _ONE_RE.sub("1", s)
    s = _DL_RE.sub("1", s)             # Ødl -> Ød1
    s = _NUMSP_RE.sub("", s)           # "0. 04" -> "0.04"
    if s in ("dl", "dI"):              # tiêu đề cột d1 trong bảng kích thước
        s = "d1"
    return " ".join(s.split())


# ---------------------------------------------------------------- OCR trên ảnh
def _crop(img: np.ndarray, quad: np.ndarray):
    import cv2
    u, v = quad[1] - quad[0], quad[3] - quad[0]
    w, h = max(int(round(np.linalg.norm(u))), 1), max(int(round(np.linalg.norm(v))), 1)
    dst = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    M = cv2.getPerspectiveTransform(quad.astype(np.float32), dst)
    return cv2.warpPerspective(img, M, (w, h), borderMode=cv2.BORDER_REPLICATE,
                               flags=cv2.INTER_CUBIC), u, v


def _rec(eng, im) -> tuple[str, float]:
    r = eng(np.ascontiguousarray(im), use_det=False, use_cls=False, use_rec=True)
    if not r or not r.txts:
        return "", 0.0
    return str(r.txts[0]), float(r.scores[0])


def ocr_image(img_bgr: np.ndarray, min_score: float = 0.5) -> list[OcrWord]:
    from . import progress
    eng = _engine()
    mpx = img_bgr.shape[0] * img_bgr.shape[1] / 1e6
    progress.creep(0, 0.5, max(1.0, (0.4 if ENGINE_INFO.get("device") == "gpu" else 1.2) * mpx), "OCR: tìm vùng chữ")
    det = eng(img_bgr, use_det=True, use_cls=False, use_rec=False)
    progress.sub(1, 2)
    boxes = [] if det is None or det.boxes is None else det.boxes
    items = []
    for quad in boxes:
        quad = np.asarray(quad, dtype=np.float64)
        crop, u, v = _crop(img_bgr, quad)
        lu, lv = np.linalg.norm(u), np.linalg.norm(v)
        if lu < 2 or lv < 2:
            continue
        # np.rot90(crop, k) (ngược kim đồng hồ) => hướng đọc tương ứng trong ảnh gốc
        dirs = {0: u / lu, 1: v / lv, 2: -u / lu, 3: -v / lv}
        items.append((quad, crop, dirs, lu / lv))

    words: list[OcrWord] = []
    votes = np.zeros(2)                      # tổng vector hướng đọc (theo số ký tự)

    # 1) khung dài: hướng đọc rõ (dọc theo cạnh dài). Thử hướng hay gặp trước, đủ tốt thì dừng.
    wins = {0: 0, 1: 0, 2: 0, 3: 0}
    squares = []
    n_all, n_done = max(1, len(items)), 0
    for quad, crop, dirs, ratio in items:
        if 1 / 2.2 < ratio < 2.2:      # 1-2 ký tự: cạnh dài chưa chắc là hướng đọc
            squares.append((quad, crop, dirs))
            continue
        n_done += 1
        progress.sub(0.5 + 0.5 * n_done / n_all, 1, f"OCR: đọc chữ {n_done}/{len(items)}")
        ks = sorted((0, 2) if ratio >= 1 else (1, 3), key=lambda k: -wins[k])
        best = ("", 0.0, ks[0])
        for k in ks:
            txt, sc = _rec(eng, np.rot90(crop, k))
            if sc > best[1]:
                best = (txt, sc, k)
            if sc >= 0.9:
                break
        txt, sc, k = best
        if txt.strip() and sc >= min_score:
            wins[k] += 1
            votes += dirs[k] * len(txt)
            words.append(OcrWord(txt.strip(), sc, quad, dirs[k]))

    # 2) khung ngắn (1-2 ký tự): hướng mơ hồ => ưu tiên hướng chủ đạo của trang
    dom = votes / (np.linalg.norm(votes) or 1.0)
    for quad, crop, dirs in squares:
        n_done += 1
        progress.sub(0.5 + 0.5 * n_done / n_all, 1, f"OCR: đọc chữ {n_done}/{len(items)}")
        txt, sc, k = _best_turn(eng, crop, [float(dirs[k] @ dom) for k in range(4)], bonus=0.15)
        if txt.strip() and sc >= min_score:
            words.append(OcrWord(txt.strip(), sc, quad, dirs[k]))
    return words


def _text_width(text: str, cap_height: float) -> float | None:
    """Ước lượng chiều dài chữ khi hiển thị bằng Arial (đơn vị như cap_height)."""
    try:
        from ezdxf.fonts import fonts
        f = fonts.make_font("arial.ttf", cap_height)
        t = text.replace("%%c", "Ø").replace("%%p", "±").replace("%%d", "°")
        return float(f.text_width(t))
    except Exception:
        return None


# ---------------------------------------------------------------- gắn vào hình học trang
def _inside_ratio(pts: np.ndarray, quad: np.ndarray) -> float:
    """Tỷ lệ điểm của pts nằm trong tứ giác quad (lồi)."""
    inside = np.ones(len(pts), dtype=bool)
    for i in range(4):
        a, b = quad[i], quad[(i + 1) % 4]
        cross = (b[0] - a[0]) * (pts[:, 1] - a[1]) - (b[1] - a[1]) * (pts[:, 0] - a[0])
        inside &= cross >= -1e-9
    if not inside.any():  # thử chiều ngược lại (thứ tự đỉnh CW/CCW)
        inside = np.ones(len(pts), dtype=bool)
        for i in range(4):
            a, b = quad[i], quad[(i + 1) % 4]
            cross = (b[0] - a[0]) * (pts[:, 1] - a[1]) - (b[1] - a[1]) * (pts[:, 0] - a[0])
            inside &= cross <= 1e-9
    return float(inside.mean())


def _expand(quad: np.ndarray, pad: float) -> np.ndarray:
    c = quad.mean(axis=0)
    out = []
    for p in quad:
        d = p - c
        n = np.linalg.norm(d)
        out.append(p + d / n * pad if n else p)
    return np.array(out)


def _make_text(glyph_pts: list[np.ndarray], d: np.ndarray, text: str, score: float,
               check_score: float, quad: np.ndarray | None = None) -> Text:
    """Tạo TEXT chồng khít lên nét chữ gốc: điểm chèn, chiều cao, hệ số rộng, góc xoay."""
    text, suspicious = postfix_text(text)
    n = np.array([-d[1], d[0]])                       # pháp tuyến "lên" của chữ
    if glyph_pts:
        ref = np.vstack(glyph_pts)
        # chiều cao chữ = phân vị 75% chiều cao từng nét chữ (bỏ qua ngoặc, dấu chấm...)
        hs = [float(np.ptp(g @ n)) for g in glyph_pts]
        bottoms = [float((g @ n).min()) for g in glyph_pts]
        height = float(np.percentile(hs, 75))
        base = float(np.median(bottoms))
        start, length = float((ref @ d).min()), float(np.ptp(ref @ d))
    else:
        height = float(np.ptp(quad @ n)) * 0.7
        base = float((quad @ n).min()) + height * 0.2
        start, length = float((quad @ d).min()), float(np.ptp(quad @ d))
    insert = d * start + n * base
    cad = to_cad_text(text)
    tw = _text_width(cad, height)
    wf = min(max(length / tw, 0.4), 1.5) if tw else 1.0
    rot = math.degrees(math.atan2(d[1], d[0])) % 360.0
    q = round(rot / 90.0) * 90.0
    if abs(rot - q) <= 3.0:            # chữ CAD gần như luôn thẳng trục: bỏ lệch nhỏ của OCR
        rot = q % 360.0
    layer = LAYER_OCR if score >= check_score and not suspicious else LAYER_CHECK
    return Text((float(insert[0]), float(insert[1])), cad, float(max(height, 0.5)),
                round(rot, 2), Style(layer=layer), round(wf, 3))


# ---------------------------------------------------------------- sửa chữ OCR theo ngữ cảnh bản vẽ
# ký tự hay bị đọc nhầm với số (font CAD: số 1 chỉ là 1 vạch, số 0 tròn như chữ O)
_LIKE_1 = "lI|!"
_LIKE_0 = "Oo"
_NUMERIC = re.compile(r"[\dΦø%~/.,:±+\-()×xX* ]+")      # ô số: 20~50/55, ±0.2, 1:2, 2-C0.5...
_BARS = re.compile(r"[|lI!](?:\s*[|lI!])*")              # chỉ toàn vạch: "|", "| |", "ll"
# thuật ngữ hay gặp trên bản vẽ: OCR dính rác ở đầu (vd "ISQUARENESS" từ "※1)SQUARENESS")
_TERMS = ("SQUARENESS", "MATERIAL", "HARDNESS", "ROUGHNESS", "TOLERANCE", "CHAMFER", "SURFACE",
          "PARALLELISM", "FLATNESS", "CONCENTRICITY", "CYLINDRICITY", "ROUNDNESS")


def postfix_text(text: str) -> tuple[str, bool]:
    """Sửa lỗi OCR hay gặp trên bản vẽ CAD. Trả về (chữ đã sửa, cần người soát?).

    - Chuỗi chỉ toàn vạch "|", "| |" (thường là số 1 / 11 trong bảng) -> "1" / "11", nhưng đánh dấu
      cần soát vì cũng có thể là đường kẻ khung GD&T.
    - Trong chuỗi số (20~5O/l50): l I | ! -> 1, O o -> 0.
    - Ngoặc vuông / vạch đứng lẻ, thuật ngữ dính rác ở đầu -> giữ nguyên chữ nhưng đánh dấu cần soát.
    """
    t = text.strip()
    if not t:
        return text, False
    if _BARS.fullmatch(t):
        return "".join("1" for c in t if c in _LIKE_1), True
    has_digit = any(c.isdigit() for c in t)
    if has_digit:
        cand = "".join("1" if c in _LIKE_1 else c for c in t)
        # O/o chỉ đổi khi đứng cạnh chữ số (tránh "MO", "No")
        cand = re.sub(rf"(?<=\d)[{_LIKE_0}]|[{_LIKE_0}](?=\d)", "0", cand)
        cand = re.sub(rf"(?<=\d)[{_LIKE_0}]", "0", cand)
        if cand != t and _NUMERIC.fullmatch(cand):
            return cand, False
    suspicious = bool(re.search(r"[\[\]|]", t))
    up = t.upper()
    for term in _TERMS:
        i = up.find(term)
        if i > 0 and re.fullmatch(r"[^A-Z]*[IL1|][^A-Z]*", up[:i]):   # vd "ISQUARENESS", "I)SQUARENESS"
            suspicious = True
    return fix_terms(t), suspicious


# ---------------------------------------------------------------- từ điển bản vẽ
# Mã vật liệu hay gặp (font CAD: số 1 như chữ I, số 0 như chữ O -> "SKDII" là SKD11)
_MATERIALS = ("SKD11", "SKD61", "SKH51", "SKH55", "SKH57", "SKH59", "HAP40", "HAP72", "SUJ2", "S45C",
              "S50C", "S55C", "SCM415", "SCM435", "SCM440", "SS400", "SUS303", "SUS304", "SUS420J2",
              "SUS440C", "DC53", "DC11", "SKS3", "SK3", "SK5", "NAK80", "YXR3", "ASP23", "ASP60",
              "SLD", "SKT4", "STAVAX", "A2017", "A5052", "A6061", "A7075", "C1100", "C3604")
# Từ chuyên ngành (khung tên, ghi chú). Chỉ sửa chữ IN HOA >= 6 ký tự sai đúng 1 ký tự
# (thay thế, không thêm / bớt -> "TOLERANCES" không bị đổi thành "TOLERANCE")
_WORDS = frozenset(_TERMS + (
    "APPROVED", "CHECKED", "DESIGNED", "CORRECTIONS", "CORRECTION", "REQUEST", "QUANTITY", "REMARKS",
    "TREATMENT", "HARDENING", "QUENCHING", "TEMPERING", "INCREMENTS", "INCREMENT", "GENERAL",
    "DIMENSIONAL", "REGULATED", "WITHOUT", "PROJECTION", "DRAWING", "NUMBER", "ALTERATION",
    "ALTERATIONS", "CHANGED", "INSPECTION", "SCRATCHES", "VISIBLE", "THICKNESS", "DIAMETER",
    "LENGTH", "DETAIL", "SECTION", "SPECIAL", "PRECISION", "STRAIGHTNESS", "PERPENDICULARITY",
    "POSITION", "SYMMETRY", "RUNOUT", "PROFILE", "COATING", "PLATING", "NITRIDING", "POLISHING",
    "GRINDING", "THREAD", "COUNTERBORE", "SURFACES", "EDGES", "SHARP", "NOTES", "FINISH", "BLANK",
    "CUTTING", "SUPPORT", "PILLAR", "PILLARS", "PUNCH", "GUIDE", "BUSHING", "INCLUDING", "UNLESS",
    "OTHERWISE", "SPECIFIED", "MACHINING", "TOLERANCES", "DIMENSIONS",
))
# nhóm ký tự OCR hay nhầm với nhau
_CONFUSE_GROUPS = ("1Il|!", "0ODQo", "8B", "5S", "2Z", "6G")
_CONFUSE = {c: i for i, g in enumerate(_CONFUSE_GROUPS) for c in g}


def _fold(s: str) -> str:
    """Bỏ dấu (À -> A): vết bẩn / nét kẻ gần chữ hay bị OCR đọc thành dấu tiếng Việt."""
    return "".join(c for c in unicodedata.normalize("NFKD", s) if not unicodedata.combining(c))


def _confusable(a: str, b: str) -> bool:
    """Cùng độ dài, khác ít nhất 1 chỗ, mọi chỗ khác đều là cặp ký tự dễ nhầm."""
    if len(a) != len(b) or a == b:
        return False
    return all(x == y or (x in _CONFUSE and _CONFUSE.get(x) == _CONFUSE.get(y)) for x, y in zip(a, b))


def _hamming1(a: str, b: str) -> bool:
    return len(a) == len(b) and sum(x != y for x, y in zip(a, b)) == 1


def _fix_word(tok: str) -> str:
    up = tok.upper()
    if tok != up or not any(c.isalpha() for c in tok):
        return tok                                  # chỉ sửa token IN HOA
    if up in _WORDS or up in _MATERIALS:
        return tok
    # mã vật liệu: SKDII -> SKD11, HAP4O -> HAP40 (phải có chữ số / ký tự giống số mới đổi)
    if 3 <= len(up) <= 8:
        for m in _MATERIALS:
            if any(c.isdigit() for c in m) and _confusable(up, m):
                return m
    folded = _fold(up)
    if folded in _WORDS:
        return folded                               # CHÀMFER -> CHAMFER
    if len(folded) >= 6 and folded.isalpha():
        cands = [w for w in _WORDS if _hamming1(folded, w)]
        if len(cands) == 1:
            return cands[0]                         # APPROVEQ -> APPROVED, INCREWENTS -> INCREMENTS
    return tok


_RULES = (
    (re.compile(r"(?<=\d)[Oo](?=\d*\s*mm\b)"), "0"),                 # 1Omm -> 10mm
    (re.compile(r"(?<=\d\.)[Il|](?=\s*mm\b)"), "1"),                 # 0.Imm -> 0.1mm
    (re.compile(r"\bCO(?=\.\s*[\dIl]|[~〜∼～])"), "C0"),              # vát mép CO.1 / CO~0.1 -> C0...
    (re.compile(r"(?<![A-Za-z0-9])[Oo](?=\.\d)"), "0"),              # ~O.2 / O.01 -> ~0.2 / 0.01
    (re.compile(r"(?<=JIS B )O(?=\d{3})"), "0"),                     # JIS B O405 -> 0405
    (re.compile(r"(?<=JIS B 0405-)[nN](?=\b)"), "m"),                # cấp dung sai chung: f / m / c / v
)


def fix_terms(text: str) -> str:
    """Sửa chữ OCR theo ngữ cảnh bản vẽ: đơn vị (10mm), vát mép (C0.5), JIS B 0405-m,
    mã vật liệu (SKD11), từ chuyên ngành sai 1 ký tự (APPROVED, INCREMENTS...)."""
    s = re.sub(r"[A-Za-zÀ-ỹ0-9|!]+", lambda m: _fix_word(m.group(0)), text)
    for rx, rep in _RULES:
        s = rx.sub(rep, s)
    return s


_CODE_RE = re.compile(r"[A-Z0-9][A-Z0-9-]+[A-Z0-9]")            # mã >= 3 ký tự: SPB, SPLB-B, DR2572


def harmonize(texts: list) -> int:
    """Nhất quán trong 1 trang: mã xuất hiện >= 2 lần (vd SPLB-B) mà 1 chỗ khác bị đọc lệch đúng các
    ký tự dễ nhầm (SPLB-8) -> sửa theo bản đọc được nhiều lần. Chỉ sửa chữ OCR. Trả về số chữ đã sửa."""
    ocr = [t for t in texts if t.style.layer in (LAYER_OCR, LAYER_CHECK)]
    counts: dict[str, int] = {}
    for t in ocr:
        for tok in set(_CODE_RE.findall(t.text)):
            counts[tok] = counts.get(tok, 0) + 1
    frequent = [k for k, n in counts.items() if n >= 2 and any(c.isalpha() for c in k)]
    if not frequent:
        return 0

    def repl(m):
        tok = m.group(0)
        if counts.get(tok, 0) >= 2:
            return tok
        cands = [k for k in frequent if _confusable(tok, k)]
        return cands[0] if len(cands) == 1 else tok

    changed = 0
    for t in ocr:
        new = _CODE_RE.sub(repl, t.text)
        if new != t.text:
            t.text = new
            changed += 1
    return changed


# Chữ đơn dễ là ký hiệu GD&T (◎ đồng tâm, ⌭ độ trụ, ○ độ tròn...) => giữ nét gốc
_SYMBOL_LIKE = set("OoQ0○◎●◯⌭⊥∥/")


def _accept(text: str, score: float, min_score: float, aspect: float | None = None) -> bool:
    """aspect = cao / rộng của nét chữ (theo hướng đọc). Số 0 cao ~1.6-2x rộng;
    ký hiệu GD&T (○ ◎ ⌭) gần tròn đều ~1."""
    t = text.strip()
    if not t or score < min_score:
        return False
    if len(t) == 1:
        if not t.isascii():
            return False                      # 1 ký tự lạ (vd. '二' đọc từ dấu '=') => giữ nét gốc
        if t in "0Oo" and aspect is not None and aspect >= 1.35 and score >= 0.8:
            return True                       # số 0 đứng riêng (vd. cận dưới dung sai)
        return t not in _SYMBOL_LIKE and score >= 0.9
    return True


def _aspect(polys: list[np.ndarray], d: np.ndarray) -> float | None:
    if not polys:
        return None
    ref = np.vstack(polys)
    n = np.array([-d[1], d[0]])
    w = float(np.ptp(ref @ d))
    return float(np.ptp(ref @ n)) / w if w > 1e-9 else None


def _bbox(pts: np.ndarray) -> np.ndarray:
    return np.array([pts[:, 0].min(), pts[:, 1].min(), pts[:, 0].max(), pts[:, 1].max()])


def _clusters(items: list[tuple[int, np.ndarray]], gap_ratio: float = 0.45) -> list[list[int]]:
    """Gom nét chữ rời thành cụm (1 cụm ~ 1 từ): 2 nét thuộc 1 cụm nếu khe hở giữa 2 hộp bao
    < gap_ratio × kích thước nét lớn hơn."""
    boxes = {i: _bbox(p) for i, p in items}
    ids = list(boxes)
    parent = {i: i for i in ids}

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for a_i, a in enumerate(ids):
        A = boxes[a]
        sa = max(A[2] - A[0], A[3] - A[1])
        for b in ids[a_i + 1:]:
            B = boxes[b]
            sb = max(B[2] - B[0], B[3] - B[1])
            # nét rất mảnh (số 1, chữ I) có cạnh dài ~ cỡ chữ; dấu chấm thì nhỏ => cho phép gom
            big, small = max(sa, sb), min(sa, sb)
            if not (small < 0.3 * big or big <= 1.6 * small):
                continue                              # khác cỡ chữ (vd. "5" và "+0.04")
            gx = max(0.0, max(A[0], B[0]) - min(A[2], B[2]))
            gy = max(0.0, max(A[1], B[1]) - min(A[3], B[3]))
            if max(gx, gy) < gap_ratio * max(sa, sb) and min(gx, gy) <= 0.0 + 1e-9 + 0.25 * max(sa, sb):
                parent[find(a)] = find(b)
    groups: dict[int, list[int]] = {}
    for i in ids:
        groups.setdefault(find(i), []).append(i)
    return list(groups.values())


def _raster_cluster(polys: list[np.ndarray], target_h: float = 40.0, pad: int = 14):
    """Vẽ riêng các nét chữ (toạ độ geo, Y lên) lên nền trắng => ảnh sạch cho OCR."""
    import cv2
    allp = np.vstack(polys)
    x0, y0 = allp.min(axis=0)
    x1, y1 = allp.max(axis=0)
    size = float(np.median([max(np.ptp(p[:, 0]), np.ptp(p[:, 1])) for p in polys])) or 1.0
    sc = target_h / size
    W = int((x1 - x0) * sc) + 2 * pad + 1
    H = int((y1 - y0) * sc) + 2 * pad + 1
    if W * H > 4_000_000:
        return None
    img = np.full((H, W), 255, np.uint8)
    cnts = [np.round(np.c_[(p[:, 0] - x0) * sc + pad, (y1 - p[:, 1]) * sc + pad]).astype(np.int32)
            for p in polys]
    mask = np.zeros_like(img)
    cv2.fillPoly(mask, cnts, 255)       # even-odd: lỗ của chữ 0, 8, A... giữ đúng
    img[mask > 0] = 0
    return cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)


# hướng đọc (toạ độ geo) khi xoay ảnh cụm np.rot90 k lần: ảnh +x = geo +x, ảnh +y = geo -y
_GEO_DIRS = {0: np.array([1.0, 0.0]), 1: np.array([0.0, -1.0]),
             2: np.array([-1.0, 0.0]), 3: np.array([0.0, 1.0])}


def _metrics(polys: list[np.ndarray], d: np.ndarray) -> tuple[float, float, float, float]:
    """(đầu, cuối theo hướng đọc, đường chân chữ, chiều cao nét cao nhất) của 1 nhóm nét."""
    n = np.array([-d[1], d[0]])
    ref = np.vstack(polys)
    hs = [float(np.ptp(g @ n)) for g in polys]
    return (float((ref @ d).min()), float((ref @ d).max()),
            float(np.median([(g @ n).min() for g in polys])), float(max(hs)))


def apply_ocr(page: pymupdf.Page, geo: PageGeometry, flip, dpi: int = 300,
              min_score: float = 0.5, check_score: float = 0.85) -> int:
    """OCR trang, thêm Text vào geo.texts, chuyển nét chữ gốc sang layer TEXT_GLYPH.

    Lượt 1: phát hiện + nhận dạng trên ảnh cả trang.
    Lượt 2: nét chữ còn sót (chữ ngắn sát đường kích thước/khung) được gom cụm, vẽ lại trên nền
            sạch và nhận dạng lại.
    Lượt 3: nét sót nằm sát đầu/cuối 1 chữ đã đọc (vd. "0." + "1") -> ghép và đọc lại.
    Lượt 4: nối các mảnh cùng dòng, cùng cỡ chữ (vd. "3." + "2a" -> "3.2a").
    flip: hàm đổi Point PDF (chưa xoay) -> toạ độ geo (Y lên). Trả về số chữ thêm vào.
    """
    import cv2

    pix = page.get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY, alpha=False)
    img = np.frombuffer(pix.samples, np.uint8).reshape(pix.h, pix.w)
    img = cv2.cvtColor(np.ascontiguousarray(img), cv2.COLOR_GRAY2BGR)
    words = ocr_image(img, min_score)

    s = 72.0 / dpi
    der = page.derotation_matrix
    off = pymupdf.Point(page.rect.x0, page.rect.y0)

    def to_geo(p) -> np.ndarray:
        q = (pymupdf.Point(float(p[0]) * s, float(p[1]) * s) + off) * der
        return np.array(flip(q))

    # nét chữ ứng viên: hình tô đặc (layer FILL)
    fills = [(e, np.array(e.points)) for e in geo.entities
             if isinstance(e, Polyline) and e.filled and len(e.points) >= 3]
    used: set[int] = set()
    recs: list[dict] = []          # {idx, d, txt, sc, quad}
    votes = np.zeros(2)

    def take(idx):
        for i in idx:
            used.add(i)
            e = fills[i][0]
            e.style = Style(e.style.color, e.style.width, e.style.dashes, LAYER_GLYPH)

    k_of = lambda d: next((k for k, v in _GEO_DIRS.items() if float(v @ d) > 0.98), None)

    # ---- lượt 1
    for w in words:
        quad = np.array([to_geo(p) for p in w.quad])
        a, b = to_geo(w.quad[0]), to_geo(w.quad[0] + w.d * 10)
        d = (b - a) / (np.linalg.norm(b - a) or 1.0)
        k = next((k for k, v in _GEO_DIRS.items() if float(v @ d) > 0.9986), None)
        if k is not None:
            d = _GEO_DIRS[k]                  # lệch <= 3°: coi là thẳng trục (chữ CAD)
        quad_pad = _expand(quad, 0.4)                     # nới 0.4 pt tránh hụt mép
        idx = []
        for i, (e, pts) in enumerate(fills):
            if i in used:
                continue
            c = pts.mean(axis=0)
            if _inside_ratio(c[None, :], quad_pad) < 1:
                continue
            if _inside_ratio(pts, quad_pad) >= 0.9:       # mũi tên kích thước thường lòi ra ngoài
                idx.append(i)
        if not _accept(w.text, w.score, min_score, _aspect([fills[i][1] for i in idx], d)):
            continue                                       # giữ nét gốc (vd. ký hiệu GD&T)
        if len(idx) < 0.6 * len(w.text.replace(" ", "")):
            continue                     # khung OCR lệch, chỉ trùm 1 phần nét => để lượt 2 xử lý
        take(idx)
        votes += d * len(w.text)
        recs.append({"idx": idx, "d": d, "txt": w.text, "sc": w.score, "quad": quad})

    # ---- lượt 2: nét chữ còn sót
    glyph_sizes = [max(np.ptp(fills[i][1][:, 0]), np.ptp(fills[i][1][:, 1])) for i in used]
    typ = float(np.median(glyph_sizes)) if glyph_sizes else None
    left = [(i, pts) for i, (e, pts) in enumerate(fills) if i not in used and len(pts) >= 12]
    if typ:
        left = [(i, p) for i, p in left if max(np.ptp(p[:, 0]), np.ptp(p[:, 1])) <= 2.5 * typ]
    eng = _engine()
    if left:
        dom = votes / (np.linalg.norm(votes) or 1.0)
        for grp in _clusters(left):
            polys = [fills[i][1] for i in grp]
            im = _raster_cluster(polys)
            if im is None:
                continue
            txt, sc, k = _best_turn(eng, im, [float(_GEO_DIRS[k] @ dom) for k in range(4)])
            asp = _aspect(polys, _GEO_DIRS[k])
            t0 = txt.replace(" ", "")
            bars = [np.ptp(p, axis=0) for p in polys]
            if all(b.min() < 0.25 * b.max() and (typ is None or b.max() >= 0.8 * typ)
                   for b in bars) or (t0 and set(t0) <= set("Il|1!") and all(
                    b.min() < 0.25 * b.max() for b in bars)):
                # chỉ toàn vạch thẳng mảnh = các số 1 (font CAD vẽ "1" như chữ I).
                # Hướng đọc vuông góc với vạch; chọn chiều theo hướng chữ chủ đạo.
                long_ax = np.array([0.0, 1.0]) if bars[0][1] > bars[0][0] else np.array([1.0, 0.0])
                ks = [kk for kk in range(4) if abs(float(_GEO_DIRS[kk] @ long_ax)) < 0.1]
                k = max(ks, key=lambda kk: float(_GEO_DIRS[kk] @ dom))
                txt, sc = "1" * len(polys), 0.9
            elif not _accept(txt, sc, max(min_score, 0.8), asp):
                continue
            take(grp)
            recs.append({"idx": list(grp), "d": _GEO_DIRS[k], "txt": txt, "sc": sc, "quad": None})

    # ---- lượt 3: nét sót sát đầu/cuối 1 chữ đã đọc, cùng dòng, cùng cỡ (vd. "0." + "1")
    for r in recs:
        k = k_of(r["d"])
        if k is None or not r["idx"]:
            continue
        d = r["d"]
        n = np.array([-d[1], d[0]])
        ref = np.vstack([fills[i][1] for i in r["idx"]])
        lo, hi = float((ref @ n).min()), float((ref @ n).max())
        a, b = float((ref @ d).min()), float((ref @ d).max())
        hgt = hi - lo
        extra = []
        for i, (e, pts) in enumerate(fills):
            if i in used or len(pts) < 12:
                continue
            pn, pd_ = pts @ n, pts @ d
            ov = min(hi, pn.max()) - max(lo, pn.min())
            if ov < 0.6 * min(hgt, np.ptp(pn)) or not (0.7 * hgt <= np.ptp(pn) <= 1.4 * hgt):
                continue
            if max(pd_.min() - b, a - pd_.max()) < 1.0 * hgt:
                extra.append(i)
        if not extra:
            continue
        polys = [fills[i][1] for i in r["idx"] + extra]
        im = _raster_cluster(polys)
        if im is None:
            continue
        txt, sc = _rec(eng, np.rot90(im, k))
        if len(txt.strip()) > len(r["txt"].strip()) and sc >= max(0.75, r["sc"] - 0.1):
            take(extra)
            r.update(idx=r["idx"] + extra, txt=txt, sc=sc, quad=None)

    # đường kẻ (khung bảng, đường kích thước) để không nối chữ ở 2 ô khác nhau
    segs = []
    for e in geo.entities:
        if isinstance(e, Line):
            segs.append((e.p1, e.p2))
        elif isinstance(e, Polyline) and not e.filled:
            pts = e.points + ([e.points[0]] if e.closed else [])
            segs += list(zip(pts[:-1], pts[1:]))
    segs_a = np.array(segs, dtype=float).reshape(-1, 4) if segs else np.zeros((0, 4))

    def blocked(p: np.ndarray, q: np.ndarray) -> bool:
        """Đoạn p-q có cắt đường kẻ nào không."""
        if not len(segs_a):
            return False
        x1, y1, x2, y2 = segs_a.T
        def orient(ax, ay, bx, by, cx, cy):
            return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        d1 = orient(x1, y1, x2, y2, p[0], p[1])
        d2 = orient(x1, y1, x2, y2, q[0], q[1])
        d3 = orient(p[0], p[1], q[0], q[1], x1, y1)
        d4 = orient(p[0], p[1], q[0], q[1], x2, y2)
        return bool(np.any((d1 * d2 < 0) & (d3 * d4 < 0)))

    # ---- lượt 4: nối mảnh cùng dòng (font CAD giãn chữ rộng => OCR hay tách "3." | "2a")
    changed = True
    while changed:
        changed = False
        live = [r for r in recs if not r.get("dead") and r["idx"] and k_of(r["d"]) is not None]
        for r in live:
            r["m"] = _metrics([fills[i][1] for i in r["idx"]], r["d"])
        live.sort(key=lambda r: (k_of(r["d"]), r["m"][2], r["m"][0]))
        for A in live:
            for B in live:
                if A is B or not A["idx"] or not B["idx"] or k_of(A["d"]) != k_of(B["d"]):
                    continue
                a0, a1, abase, ah = A["m"]
                b0, b1, bbase, bh = B["m"]
                h = max(ah, bh)
                gap = b0 - a1
                ta, tb = A["txt"].strip(), B["txt"].strip()
                if tb[:1] in "+-±%" or ta[-1:] in "+-±":
                    continue                 # dung sai ("L" | "+0.16") luôn là TEXT riêng trong CAD
                decimal = ta[-1:] in ".," or tb[:1] in ".,"
                if not (min(ah, bh) >= 0.8 * h and abs(abase - bbase) <= 0.2 * h
                        and -0.1 * h <= gap <= (1.5 if decimal else 1.0) * h):
                    continue
                dd = A["d"]
                nn = np.array([-dd[1], dd[0]])
                mid = abase + 0.45 * h
                if blocked(dd * a1 + nn * mid, dd * b0 + nn * mid):
                    continue                 # có đường kẻ giữa 2 mảnh (2 ô bảng khác nhau)
                tight = (decimal or ta[-1:] in "~(/" or tb[:1] in "~)/" or gap < 0.45 * h
                         or (ta[-1:].isdigit() and tb[:1].isdigit()))
                A["txt"] = ta + ("" if tight else " ") + tb
                A["idx"] = A["idx"] + B["idx"]
                A["sc"], A["quad"] = min(A["sc"], B["sc"]), None
                B["idx"], B["dead"] = [], True
                changed = True
                break
            if changed:
                break

    added = 0
    for r in recs:
        if r.get("dead") or (not r["idx"] and r["quad"] is None):
            continue
        polys = [fills[i][1] for i in r["idx"]]
        geo.texts.append(_make_text(polys, r["d"], r["txt"], r["sc"], check_score, r["quad"]))
        added += 1
    if added:
        harmonize(geo.texts[-added:])
    return added
