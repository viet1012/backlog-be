"""Báo tiến độ trong 1 trang (chạy ở process con) qua file JSON nhỏ.

Process con không gọi ngược được về server, nên mỗi trang ghi {"p": 0..1, "stage": "..."}
vào 1 file; server đọc lại khi trình duyệt hỏi tiến độ. Không bật (start chưa gọi) => không làm gì.
"""
from __future__ import annotations

import json
import math
import os
import threading
import time

_path: str | None = None
_last = 0.0
_lo, _hi, _label = 0.0, 1.0, ""
_creep_id = 0


def start(path: str | None) -> None:
    global _path, _last, _lo, _hi, _label
    _path, _last, _lo, _hi, _label = path, 0.0, 0.0, 1.0, ""
    if path:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        _write(0.0, "Bắt đầu", force=True)


def stage(lo: float, label: str, hi: float | None = None) -> None:
    """Bước mới chiếm đoạn [lo, hi] của thanh tiến độ trang."""
    global _lo, _hi, _label, _creep_id
    _creep_id += 1
    _lo, _hi, _label = lo, (hi if hi is not None else lo), label
    _write(lo, label, force=True)


def sub(i: int, n: int, label: str | None = None) -> None:
    """Tiến độ bên trong bước hiện tại: i/n."""
    global _creep_id
    _creep_id += 1
    if n > 0:
        _write(_lo + (_hi - _lo) * min(1.0, i / n), label or _label)


def creep(a: float, b: float, seconds: float, label: str | None = None) -> None:
    """Bước dài không chia nhỏ được (1 lệnh OCR/Hough): cho thanh nhích dần từ a tới gần b
    (phần của bước hiện tại) theo thời gian ước lượng; dừng khi có sub()/stage() kế tiếp."""
    global _creep_id
    if not _path:
        return
    _creep_id += 1
    my, lo, hi, lab, t0 = _creep_id, _lo, _hi, label or _label, time.monotonic()
    _write(lo + (hi - lo) * a, lab, force=True)

    def run():
        while _creep_id == my and _path:
            time.sleep(0.4)
            if _creep_id != my:
                break
            k = 0.95 * (1 - math.exp(-(time.monotonic() - t0) / max(seconds, 0.5)))
            _write(lo + (hi - lo) * (a + (b - a) * k), lab, force=True)
    threading.Thread(target=run, daemon=True).start()


def _write(p: float, label: str, force: bool = False) -> None:
    global _last
    if not _path:
        return
    now = time.monotonic()
    if not force and now - _last < 0.25:
        return
    _last = now
    tmp = f"{_path}.{os.getpid()}.tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"p": round(max(0.0, min(1.0, p)), 4), "stage": label}, f, ensure_ascii=False)
        os.replace(tmp, _path)
    except OSError:
        pass


def read(path) -> dict | None:
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None
