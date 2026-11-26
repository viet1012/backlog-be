"""Đổi đơn vị và nhận diện tỷ lệ bản vẽ từ khung tên."""
from __future__ import annotations

import re

PT_TO_MM = 25.4 / 72.0

# Hỗ trợ: "SCALE 1:2", "SCALE: 1/5", "TỶ LỆ 1:10", "TY LE 2:1", "尺度 1:2", "S=1:2"
_SCALE_RE = re.compile(
    r"(?:SCALE|T[ỶY]\s*L[ỆE]|尺度|縮尺|S\s*=)\s*[:=：]?\s*"
    r"(\d+(?:[.,]\d+)?)\s*[:/：]\s*(\d+(?:[.,]\d+)?)",
    re.IGNORECASE,
)


_LABEL_RE = re.compile(r"^\s*(?:SCALE|T[ỶY]\s*L[ỆE]|尺度|縮尺)\s*[:：]?\s*$", re.IGNORECASE)
_VALUE_RE = re.compile(r"^\s*(\d+(?:[.,]\d+)?)\s*[:/：]\s*(\d+(?:[.,]\d+)?)\s*$")
_FREE_RE = re.compile(r"^\s*(FREE|NTS|N\.T\.S\.?|NONE|NO\s*SCALE)\s*$", re.IGNORECASE)


def detect_scale_label(texts) -> tuple[float | None, str | None]:
    """Khung tên dạng ô: nhãn "Scale" và giá trị nằm ở ô bên dưới hoặc bên phải.

    texts: các Text (insert=(x, y) Y hướng lên, height). Trả về (hệ số, nguồn):
      (2.0, "label")  khi ô ghi "1:2";   (None, "free") khi ghi FREE / NTS (vẽ không theo tỷ lệ);
      (None, None)    khi không tìm thấy.
    """
    for lab in texts:
        if not _LABEL_RE.match(lab.text or ""):
            continue
        h = max(lab.height, 1.0)
        lx, ly = lab.insert
        cands = []
        for t in texts:
            if t is lab:
                continue
            dx, dy = t.insert[0] - lx, t.insert[1] - ly
            below = -5 * h < dy < -0.3 * h and abs(dx) < 4 * h      # ô bên dưới
            right = abs(dy) < 0.6 * h and 0 < dx < 12 * h           # bên phải cùng dòng
            if below or right:
                cands.append((abs(dx) + abs(dy), t.text))
        for _, txt in sorted(cands):
            m = _VALUE_RE.match(txt)
            if m:
                paper, real = _num(m.group(1)), _num(m.group(2))
                if paper > 0 and real > 0:
                    return real / paper, "label"
            if _FREE_RE.match(txt):
                return None, "free"
    return None, None


def _num(s: str) -> float:
    return float(s.replace(",", "."))


def detect_scale(texts: list[str]) -> float | None:
    """Trả về hệ số "kích thước thật / kích thước trên giấy".

    Tỷ lệ 1:2 (thu nhỏ) => 2.0 ; tỷ lệ 2:1 (phóng to) => 0.5.
    Ghép toàn bộ text lại vì "SCALE" và "1:2" thường nằm ở 2 span khác nhau.
    """
    joined = " ".join(texts)
    m = _SCALE_RE.search(joined)
    if not m:
        return None
    paper, real = _num(m.group(1)), _num(m.group(2))
    if paper <= 0 or real <= 0:
        return None
    return real / paper
