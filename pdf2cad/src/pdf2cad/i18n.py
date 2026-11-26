"""Chuỗi đa ngôn ngữ Việt / Anh / Nhật cho bot và thông báo.

`Bi` là str (giá trị = tiếng Việt, nên mọi code cũ so sánh/startswith vẫn chạy) kèm thuộc tính
`.en` và `.ja` (thiếu tiếng Nhật thì dùng tiếng Anh). Bot trả mọi ngôn ngữ một lúc => web đổi ngôn
ngữ thì các câu đã trả lời cũng đổi theo.

Lưu ý: các phép str thường (f-string, .join, .replace) trả về str tiếng Việt và MẤT bản dịch.
Ghép chuỗi đa ngôn ngữ bằng `+`, `bi(...)` hoặc `bjoin(...)`.
"""
from __future__ import annotations

import re

LANGS = ("vi", "en", "ja")


class Bi(str):
    en: str
    ja: str

    def __new__(cls, vi, en=None, ja=None):
        o = super().__new__(cls, str(vi))
        o.en = en_of(vi) if en is None else str(en)
        o.ja = (ja_of(vi) if isinstance(vi, Bi) and en is None else o.en) if ja is None else str(ja)
        return o

    def __add__(self, other):
        return Bi(str(self) + str(other), self.en + en_of(other), self.ja + ja_of(other))

    def __radd__(self, other):
        return Bi(str(other) + str(self), en_of(other) + self.en, ja_of(other) + self.ja)

    def __reduce__(self):                      # pickle (process con) giữ đủ ngôn ngữ
        return (Bi, (str(self), self.en, self.ja))


def en_of(x) -> str:
    return x.en if isinstance(x, Bi) else str(x)


def ja_of(x) -> str:
    return x.ja if isinstance(x, Bi) else str(x)


def bi(vi, en, ja=None) -> Bi:
    return Bi(vi, en, ja)


def bjoin(sep, items, sep_en=None, sep_ja=None) -> Bi:
    items = list(items)
    s_en = en_of(sep) if sep_en is None else sep_en
    s_ja = (ja_of(sep) if sep_en is None else sep_en) if sep_ja is None else sep_ja
    return Bi(str(sep).join(str(i) for i in items),
              s_en.join(en_of(i) for i in items),
              s_ja.join(ja_of(i) for i in items))


def pick(x, lang: str) -> str:
    return ja_of(x) if lang == "ja" else en_of(x) if lang == "en" else str(x)


# mô tả tham số: template.json có thể ghi "desc_en" / "desc_ja"; mẫu tự học cũ chưa có -> dịch theo khuôn
_DESC_PATTERNS = [
    (r"Cột khoá (\S+) của bảng", r"Key column \1 of the table", r"表のキー列 \1"),
    (r"Cột (\S+) của bảng", r"Column \1 of the table", r"表の列 \1"),
    (r"Chiều dài tổng L", "Overall length L", "全長 L"),
    (r"Chiều dài tổng", "Overall length", "全長"),
]


def desc_bi(pdef: dict) -> Bi:
    vi = pdef.get("desc", "")
    en, ja = pdef.get("desc_en"), pdef.get("desc_ja")
    for pat, rep_en, rep_ja in _DESC_PATTERNS:
        if re.fullmatch(pat, vi):
            en = en if en is not None else re.sub(pat, rep_en, vi)
            ja = ja if ja is not None else re.sub(pat, rep_ja, vi)
            break
    en = vi if en is None else en
    return Bi(vi, en, en if ja is None else ja)
