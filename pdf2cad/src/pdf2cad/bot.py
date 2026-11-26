"""Bot hội thoại vẽ lại bản vẽ từ mẫu tham số (chạy cục bộ, không gọi AI bên ngoài).

- Nhớ ngữ cảnh: các kích thước đã nói được giữ lại cho các câu sau.
- Hỏi lại khi thiếu (khoá bảng như No / D, d; chiều dài L) và gợi ý giá trị hợp lệ;
  trả lời ngắn "10" được hiểu theo câu hỏi trước.
- Hiểu sửa đổi (Việt / Anh): "tăng L thêm 10", "giảm H 2", "đổi P thành 10", "L+5", "dài hơn 10",
  "increase L by 10", "set P to 10", "longer by 10".
- Trả lời: "P là gì / what is P", "có những size nào / what sizes", "hướng dẫn / help",
  "tải file / download", "bản vẽ mới / new drawing".
- Mọi câu trả lời và nút gợi ý là `Bi` (tiếng Việt + .en): web hiển thị theo ngôn ngữ đang chọn
  và đổi được cả các câu đã trả lời.
"""
from __future__ import annotations

import re
import time
import unicodedata
from dataclasses import dataclass, field
from typing import Callable

from .i18n import Bi, bi, bjoin, desc_bi, en_of, ja_of
from .template import (Template, _fmt, candidates, cell_info, cell_allows, keys_of,
                       matching_rows, parse_request, resolve_params)


def _noacc(s: str) -> str:
    s = unicodedata.normalize("NFD", s.lower()).replace("đ", "d")
    return "".join(c for c in s if unicodedata.category(c) != "Mn")


_NUM = r"(\d+(?:[.,]\d+)?)"
_num = lambda x: float(x.replace(",", "."))

# từ -> tham số (dùng cho câu sửa đổi)
_WORDS = {"chieu dai": "L", "dai": "L", "duong kinh dau": "H", "dau": "H",
          "duong kinh than": "P", "than": "P", "ren": "M", "duong kinh ngoai": "D",
          "duong kinh trong": "d", "lo": "d"}
_WORDS_EN = {"overall length": "L", "length": "L", "head diameter": "H", "head": "H",
             "shank diameter": "P", "shank": "P", "thread": "M", "outer diameter": "D",
             "inner diameter": "d", "hole": "d"}
_WORDS_JA = {"全長": "L", "長さ": "L", "頭部径": "H", "頭部": "H", "軸径": "P", "ねじ": "M",
             "外径": "D", "穴径": "d", "内径": "d"}

# nút gợi ý dùng chung
C_SIZES = bi("Có những size nào?", "What sizes are available?", "サイズ一覧は？")
C_HELP = bi("Hướng dẫn", "Help", "ヘルプ")
C_NEW = bi("Bản vẽ mới", "New drawing", "新しい図面")


@dataclass
class Session:
    template: str
    req: dict = field(default_factory=dict)        # tham số người dùng đã chỉ định
    vals: dict | None = None                       # bộ tham số của bản vẽ gần nhất
    pending: str | None = None                     # tham số đang hỏi
    last: dict | None = None                       # {job, output}
    updated: float = field(default_factory=time.time)
    history: list = field(default_factory=list)    # hội thoại gần đây (cho LLM)


@dataclass
class Reply:
    text: Bi
    chips: list = field(default_factory=list)
    params: dict | None = None
    output: str | None = None
    job: str | None = None
    check: list | None = None

    def as_dict(self) -> dict:
        """text/chips = tiếng Việt (tương thích API cũ); *_en = tiếng Anh; *_ja = tiếng Nhật."""
        return {"text": str(self.text), "text_en": en_of(self.text), "text_ja": ja_of(self.text),
                "chips": [str(c) for c in self.chips], "chips_en": [en_of(c) for c in self.chips],
                "chips_ja": [ja_of(c) for c in self.chips],
                "params": self.params, "output": self.output, "job": self.job, "check": self.check}


def _kv(k: str, v) -> str:
    """'No' 16 -> 'No.16'; 'D' 30 -> 'D=30'"""
    v = _fmt(float(v)) if isinstance(v, (int, float)) else str(v)
    return f"No.{v}" if k == "No" else f"{k}={v}"


class Bot:
    def __init__(self, t: Template, draw: Callable[[dict], dict]):
        self.t, self.draw = t, draw
        self.keys = keys_of(t)
        self.rows = t.spec["table"]
        self.step = float(t.spec.get("values_step", 10))
        self.names = [p for p, d in t.params.items() if not d.get("derived") and not d.get("info")]
        self.info = [p for p, d in t.params.items() if d.get("info")]

    # ------------------------------------------------------------- tiện ích
    def _ci(self, row, p):
        return cell_info(row.get(p), self.step)

    def _cands(self, req, k, L=None):
        c = candidates(self.t, req, k)
        if L is not None and k == self.keys[0]:
            ok = {float(r[k]) for r in self.rows if cell_allows(self._ci(r, "L"), L)}
            c2 = [x for x in c if x in ok]
            return c2 or c
        return c

    def _title(self, vals) -> str:
        return (f"{self.t.spec.get('code', self.t.name.upper())} "
                + " ".join(_kv(k, vals[k]) for k in self.keys) + f" · L={_fmt(vals.get('L', 0))}")

    def _examples(self) -> list[str]:
        ex = self.t.spec.get("examples")
        if ex:
            return ex
        r0, r1 = self.rows[min(1, len(self.rows) - 1)], self.rows[-1]
        return [" ".join(_kv(k, r[k]) for k in self.keys) + " L=60" for r in (r0, r1)]

    def _desc(self, p: str) -> Bi:
        return desc_bi(self.t.params.get(p, {}))

    # ------------------------------------------------------------- hiểu câu
    def _parse(self, text: str, sess: Session) -> tuple[dict, dict]:
        """-> (giá trị tuyệt đối, thay đổi tương đối)"""
        absolute = parse_request(self.t, text)
        rel: dict[str, float] = {}
        names = sorted(self.names, key=len, reverse=True)
        pat = "|".join(re.escape(x) for x in names)
        # "đổi P thành 10", "cho H bằng 20", "L là 60", "set P to 10", "H equals 20"
        for m in re.finditer(rf"(?<![A-Za-z0-9])({pat})\s*(?:thành|bằng|là|thanh|bang|la|to|equals?)"
                             rf"\s*{_NUM}", text):
            absolute[m.group(1)] = _num(m.group(2))
        # "L+10", "H -2", "L += 5"
        for m in re.finditer(rf"(?<![A-Za-z0-9])({pat})\s*([+\-])=?\s*{_NUM}", text):
            absolute.pop(m.group(1), None)
            rel[m.group(1)] = (1 if m.group(2) == "+" else -1) * _num(m.group(3))
        na = _noacc(text)

        def verbs(vpat, wmap, hpat, to_words, plus):
            words = "|".join(sorted((re.escape(w) for w in wmap), key=len, reverse=True))
            for m in re.finditer(rf"\b({vpat})\s+({pat}|{words})\s*({hpat})?\s*{_NUM}", na, re.I):
                verb, who, how, n = m.group(1), m.group(2), m.group(3), _num(m.group(4))
                raw = text[m.start(2):m.end(2)]               # L khác l: lấy từ câu gốc
                p = raw if raw in self.names else wmap.get(who.lower())
                if p is None or p not in self.t.params:
                    continue
                absolute.pop(p, None)
                if how in to_words:
                    absolute[p] = n
                else:
                    rel[p] = n if verb in plus else -n

        # "tăng L thêm 10", "giảm H 2", "tăng L lên 70", "giảm chiều dài xuống 60"
        verbs("tang|giam|them|bot|cong|tru", _WORDS, "len|xuong|them|bot|di|toi|den|thanh",
              ("len", "xuong", "toi", "den", "thanh"), ("tang", "them", "cong"))
        # "increase L by 10", "decrease L 10", "increase L to 70", "reduce length to 60"
        verbs("increase|decrease|reduce|raise|lower|add|subtract", _WORDS_EN, "by|to",
              ("to",), ("increase", "raise", "add"))
        # tiếng Nhật: "Lを10増やす", "長さを5短く", "Pを16にする", "長さ80", "サイズ16"
        jw = "|".join(sorted((re.escape(w) for w in _WORDS_JA), key=len, reverse=True))
        for m in re.finditer(rf"(?<![A-Za-z0-9])({pat}|{jw})\s*を?\s*{_NUM}(?![\d.,])\s*(?:mm)?\s*"
                             rf"(増やす|増やして|伸ばす|長く|大きく|減らす|減らして|縮める|短く|小さく)", text):
            p = m.group(1) if m.group(1) in self.names else _WORDS_JA.get(m.group(1))
            if p in self.t.params:
                absolute.pop(p, None)
                rel[p] = _num(m.group(2)) * (1 if m.group(3) in ("増やす", "増やして", "伸ばす", "長く", "大きく")
                                              else -1)
        for m in re.finditer(rf"(?<![A-Za-z0-9])({pat}|{jw})\s*(?:を|は|:|=)?\s*{_NUM}(?![\d.,])"
                             rf"(?!\s*(?:mm)?\s*(?:増|伸|長く|大きく|減|縮|短く|小さく))", text):
            p = m.group(1) if m.group(1) in self.names else _WORDS_JA.get(m.group(1))
            if p in self.t.params and p not in rel:
                absolute[p] = _num(m.group(2))
        m = re.search(rf"(?:サイズ|呼び径?)\s*(?:は|を)?\s*{_NUM}", text)
        if m:
            absolute[self.keys[0]] = _num(m.group(1))
        m = re.search(rf"\b(dai|ngan)\s+hon\s*(?:them)?\s*{_NUM}", na)   # "dài hơn 10"
        if m:
            absolute.pop("L", None)
            rel["L"] = _num(m.group(2)) * (1 if m.group(1) == "dai" else -1)
        m = (re.search(rf"\b(longer|shorter)\s*(?:by)?\s*{_NUM}", na)     # "longer by 10"
             or re.search(rf"{_NUM}\s*(?:mm)?\s*(longer|shorter)\b", na))  # "10 mm shorter"
        if m:
            g = m.groups()
            word, n = (g[0], g[1]) if g[0] in ("longer", "shorter") else (g[1], g[0])
            absolute.pop("L", None)
            rel["L"] = _num(n) * (1 if word == "longer" else -1)
        # trả lời ngắn cho câu hỏi trước: "10", "60mm"
        if not absolute and not rel and sess.pending:
            m = re.fullmatch(rf"\s*(?:{re.escape(sess.pending)}\s*[.=:]?\s*)?{_NUM}\s*(mm)?\s*\.?\s*",
                             text, re.I)
            if m:
                absolute[sess.pending] = _num(m.group(1))
        return absolute, rel

    # ------------------------------------------------------------- trả lời
    def greet(self) -> Reply:
        k0 = self.keys[0]
        ks = ", ".join(f"**{k}**" for k in self.keys)
        cs = ", ".join(_fmt(x) for x in candidates(self.t, {}, k0))
        title = self.t.title
        text = bi(
            f"Xin chào! Mình là bot vẽ lại bản vẽ **{title}**.\n"
            f"Bạn cho mình biết {ks} ({k0}: {cs}) và **chiều dài L** là mình vẽ ngay bản DXF. "
            f"Các kích thước khác mình lấy theo bảng tiêu chuẩn, muốn khác thì cứ nói.\n"
            f"Sau khi vẽ, bạn có thể sửa tiếp: “tăng L thêm 10”, “dài hơn 20”, “có những size nào?”.",
            f"Hello! I'm the redraw bot for **{title}**.\n"
            f"Tell me {ks} ({k0}: {cs}) and the **length L**, and I'll draw the DXF right away. "
            f"Other dimensions come from the standard table; just say so if you want different ones.\n"
            f"After drawing you can keep editing: “increase L by 10”, “longer by 20”, "
            f"“what sizes are available?”.",
            f"こんにちは！**{title}** の図面を作図するボットです。\n"
            f"{ks}（{k0}: {cs}）と **長さ L** を教えてください。すぐに DXF を作図します。"
            f"その他の寸法は標準表から取ります。変えたい場合はお知らせください。\n"
            f"作図後も「Lを10増やす」「長さを20長く」「サイズ一覧は？」のように修正できます。")
        if self.t.spec.get("draft"):
            src, n = self.t.spec.get("source", "scan"), len(self.rows)
            text += bi(f"\n⚠ Mẫu này **tự học** từ {src}: bảng {n} hàng đọc bằng OCR, có thể sai/thiếu — "
                       f"xem “Có những size nào?” để kiểm tra trước khi dùng.",
                       f"\n⚠ This template was **self-learned** from {src}: the {n}-row table was read "
                       f"by OCR and may be wrong or incomplete — check “What sizes are available?” "
                       f"before using it.",
                       f"\n⚠ このテンプレートは {src} から**自動学習**しました: {n} 行の表は OCR で読み取ったため、"
                       f"誤りや欠落の可能性があります — 使用前に「サイズ一覧は？」で確認してください。")
        return Reply(text, self._examples() + [C_SIZES, C_HELP])

    def _sizes(self) -> Reply:
        lines = [bi(f"Bảng tiêu chuẩn {self.t.title}:", f"Standard table {self.t.title}:",
                    f"標準表 {self.t.title}:")]
        cols = [p for p in list(self.t.params) if p not in self.keys
                and not self.t.params[p].get("derived")]
        for r in self.rows:
            cells = []
            for p in cols:
                if p not in r:
                    continue
                cells.append(Bi(f"{p} {r[p]}") if self.t.params[p].get("info") else
                             Bi(f"{p} ") + self._ci(r, p).get("text", "-"))
            lines.append(Bi("• **" + " ".join(_kv(k, r[k]) for k in self.keys) + "**: ")
                         + bjoin(", ", cells))
        rules = self.t.spec.get("rules", [])
        if rules:
            rs = "; ".join(x["text"] for x in rules)
            lines.append(bi(f"Quy tắc NOTE: {rs}", f"NOTE rules: {rs}", f"NOTE のルール: {rs}"))
        return Reply(bjoin("\n", lines), self._examples())

    def _explain(self, p: str, sess: Session) -> Reply:
        d = self._desc(p)
        txt = bi(f"**{p}**: {d}.", f"**{p}**: {en_of(d)}.", f"**{p}**: {ja_of(d)}。") if str(d) else \
            bi(f"{p} là tham số của mẫu.", f"{p} is a template parameter.", f"{p} はテンプレートのパラメータです。")
        ctx = sess.vals or sess.req
        if all(k in ctx for k in self.keys):
            rows = matching_rows(self.t, ctx)
            head = " ".join(_kv(k, ctx[k]) for k in self.keys)
            if rows and p in rows[0]:
                v = Bi(rows[0][p]) if self.t.params[p].get("info") else Bi(self._ci(rows[0], p).get("text"))
                txt += bi(f"\nVới {head}: {p} = {v}.", f"\nFor {head}: {p} = {en_of(v)}.",
                          f"\n{head} の場合: {p} = {ja_of(v)}。")
            if sess.vals and p in sess.vals:
                v = sess.vals[p]
                v = _fmt(v) if isinstance(v, float) else v
                txt += bi(f" Bản vẽ hiện tại đang dùng {p}={v}.", f" The current drawing uses {p}={v}.",
                          f" 現在の図面は {p}={v} です。")
        return Reply(txt, [])

    def _ask(self, sess: Session, errors: list) -> Reply | None:
        for k in self.keys:
            if any(e.startswith(f"Thiếu {k} ") for e in errors):
                sess.pending = k
                L = sess.req.get("L")
                c = self._cands(sess.req, k, L)
                known = [x for x in self.keys if x in sess.req]
                kn = " ".join(_kv(x, sess.req[x]) for x in known)
                cs = ", ".join(_fmt(x) for x in c)
                fit = L is not None and k == self.keys[0]
                return Reply(bi(f"Bạn muốn **{k}** nào{f' cho {kn}' if known else ''}? Có: {cs}"
                                f"{f' (phù hợp với L={_fmt(L)})' if fit else ''}.",
                                f"Which **{k}** would you like{f' for {kn}' if known else ''}? Available: {cs}"
                                f"{f' (matching L={_fmt(L)})' if fit else ''}.",
                                f"{f'{kn} の' if known else ''}**{k}** はどれにしますか？ 選択肢: {cs}"
                                f"{f'（L={_fmt(L)} に適合）' if fit else ''}。"),
                             [_kv(k, x) for x in c][:10])
        miss = next((e for e in errors if e.startswith("Thiếu ") and not e.startswith("Thiếu L")
                     and not any(e.startswith(f"Thiếu {k} ") for k in self.keys)), None)
        if miss:                                     # ô bảng trống (OCR không đọc được) -> hỏi giá trị
            p = miss.split()[1]
            sess.pending = p
            head_vi = str(miss).replace("Thiếu ", "Bảng chưa có giá trị ", 1).split(" —")[0]
            head_en = en_of(miss).replace("Missing ", "The table has no value for ", 1).split(" —")[0]
            head_ja = ja_of(miss).split(" —")[0]
            return Reply(bi(f"{head_vi}. Bạn cho mình **{p}** bao nhiêu mm? (vd: {p}=7)",
                            f"{head_en}. What **{p}** in mm? (e.g. {p}=7)",
                            f"{head_ja}。**{p}** は何 mm にしますか？（例: {p}=7）"), [])
        if any(e.startswith("Thiếu L") for e in errors):
            sess.pending = "L"
            row = matching_rows(self.t, sess.req)[0]
            ci = self._ci(row, "L")
            pool = ci.get("values") or [ci["range"][0], ci["range"][1]] + ci.get("also", [])
            pool = sorted(set(pool))
            sugg = sorted({pool[0], pool[len(pool) // 2], pool[-1]} | ({100.0} if 100.0 in pool else set()))
            head = " ".join(_kv(k, sess.req[k]) for k in self.keys)
            return Reply(bi(f"{head} — chiều dài **L** bao nhiêu mm? (bảng: {ci['text']})",
                            f"{head} — what **length L** in mm? (table: {en_of(ci['text'])})",
                            f"{head} — **長さ L** は何 mm にしますか？（表: {ja_of(ci['text'])}）"),
                         [f"L={_fmt(x)}" for x in sugg][:5])
        return None

    def respond(self, sess: Session, text: str) -> Reply:
        sess.updated = time.time()
        text = (text or "").strip()
        na = _noacc(text)
        if not text:
            return self.greet()

        absolute, rel = self._parse(text, sess)
        has_params = bool(absolute or rel)
        k0 = self.keys[0]

        # --- ý định không phải vẽ (tiếng Nhật dò trên câu gốc: bỏ dấu sẽ làm mất dakuten ズ -> ス)
        if not has_params and (na.strip(" ?.!") in ("doi no", "doi so", "doi co", "doi size",
                                                     f"doi {k0.lower()}", "change no", "change size",
                                                     f"change {k0.lower()}")
                               or re.fullmatch(rf"\s*(?:{re.escape(k0)}|サイズ|呼び径?)\s*を?\s*(?:変更|変える)"
                                               r"(?:する|して)?\s*[?？!！。]?\s*", text)):
            return self.change_key(sess)
        if not has_params and (re.search(r"\b(lam lai|ban moi|ban ve moi|reset|xoa het|bat dau lai|ve moi|"
                                         r"new drawing|start over|start again|clear all)\b", na)
                               or re.search(r"新しい図面|新規|リセット|最初から|やり直", text)):
            return self.new_drawing(sess)
        if not has_params and (re.search(
                r"^(hi|hello|hey|chao|xin chao|alo|help|huong dan|giup|tro giup|guide|instructions)\b|"
                r"lam duoc gi|dung the nao|cach dung|what can you do|how (do i|to) use", na)
                or re.search(r"^(こんにちは|こんばんは|おはよう|はじめまして|ヘルプ)|使い方|何ができ", text)):
            return self.greet()
        if not has_params and (re.search(r"size|kich thuoc tieu chuan|bang|danh sach|nhung (no|loai|co)|"
                                         r"co nhung|available|\btable\b|\blist\b", na)
                               or re.search(r"サイズ|一覧|標準表|寸法表", text)):
            return self._sizes()
        m = (re.search(r"(?<![A-Za-z0-9])([A-Za-zℓ][0-9A-Za-z]?)\s*(?:là gì|la gi|nghĩa là gì|nghia la gi|"
                       r"là cái gì|ý nghĩa|y nghia|means?\b|meaning|とは|って何|は何|の意味)", text)
             or re.search(r"(?i:what\s+is|what's|what\s+does|meaning\s+of|explain)\s+"
                          r"([A-Za-zℓ][0-9A-Za-z]?)(?![0-9A-Za-z])", text))
        if m and not has_params:
            p = {"ℓ": "l"}.get(m.group(1), m.group(1))
            if p in self.t.params:
                return self.explain(p, sess)
        if not has_params and (re.search(r"\b(tai|download|file|dxf)\b", na)
                               or re.search(r"ダウンロード|ファイル", text)) and sess.last:
            return self.latest_file(sess)
        if not has_params and (re.search(r"\b(ve lai|ve|redraw|draw)\b", na)
                               or re.search(r"再作図|描き直", text)) and sess.vals:
            absolute = {}                                  # vẽ lại y như cũ
        elif not has_params and not (re.search(r"\b(ve|ban ve|draw|drawing)\b", na)
                                     or re.search(r"作図|図面|描", text)):
            return self.not_understood()
        return self.apply(sess, absolute, rel)

    # ------------------------------------------------------------- hành động (dùng chung bot luật + LLM)
    def not_understood(self) -> Reply:
        ex = self._examples()[:2]
        return Reply(bi("Mình chưa hiểu ý bạn 🙂. Bạn có thể nói kiểu: “" + "”, “".join(ex)
                        + "”, “tăng L thêm 10”, “có những size nào?”.",
                        "Sorry, I didn't get that 🙂. You can say things like: “" + "”, “".join(ex)
                        + "”, “increase L by 10”, “what sizes are available?”.",
                        "すみません、よく分かりませんでした 🙂。例えば「" + "」「".join(ex)
                        + "」「Lを10増やす」「サイズ一覧は？」のように入力してください。"),
                     [C_SIZES, C_HELP] + self._examples()[:1])

    def latest_file(self, sess: Session) -> Reply:
        if not sess.last:
            return Reply(bi("Chưa có bản vẽ nào. Bạn cho mình kích thước để vẽ nhé.",
                            "No drawing yet. Give me the dimensions to draw.",
                            "まだ図面がありません。作図する寸法を教えてください。"), self._examples()[:2])
        return Reply(bi("File bản vẽ gần nhất đây nhé.", "Here is the latest drawing file.",
                        "最新の図面ファイルです。"), [C_NEW],
                     params=sess.vals, output=sess.last["output"], job=sess.last["job"])

    def change_key(self, sess: Session) -> Reply:
        k0 = self.keys[0]
        sess.pending = k0
        for p in list(sess.req):                     # bỏ khoá + giá trị riêng của cỡ cũ (giữ L)
            if p != "L":
                sess.req.pop(p, None)
        c = candidates(self.t, {}, k0)
        cs = ", ".join(_fmt(x) for x in c)
        return Reply(bi(f"Bạn muốn đổi sang {k0} nào? ({cs})", f"Which {k0} do you want to switch to? ({cs})",
                        f"どの {k0} に変更しますか？（{cs}）"),
                     [_kv(k0, x) for x in c][:10])

    def new_drawing(self, sess: Session) -> Reply:
        k0 = self.keys[0]
        sess.req, sess.vals, sess.pending, sess.last = {}, None, k0, None
        c = candidates(self.t, {}, k0)
        cs = ", ".join(_fmt(x) for x in c)
        return Reply(bi(f"OK, bắt đầu bản vẽ mới. Bạn muốn {k0} nào? ({cs})",
                        f"OK, starting a new drawing. Which {k0} would you like? ({cs})",
                        f"新しい図面を始めます。{k0} はどれにしますか？（{cs}）"),
                     [_kv(k0, x) for x in c][:10])

    def sizes(self) -> Reply:
        return self._sizes()

    def explain(self, p: str, sess: Session) -> Reply:
        p = {"ℓ": "l"}.get(p, p)
        if p not in self.t.params:
            names = ", ".join(self.names)
            return Reply(bi(f"Mẫu này không có tham số {p}. Các tham số: {names}.",
                            f"This template has no parameter {p}. Parameters: {names}.",
                            f"このテンプレートにパラメータ {p} はありません。パラメータ: {names}。"), [])
        return self._explain(p, sess)

    def apply(self, sess: Session, absolute: dict, rel: dict) -> Reply:
        """Gộp giá trị tuyệt đối / thay đổi tương đối vào ngữ cảnh, hỏi lại nếu thiếu, rồi vẽ."""
        sess.updated = time.time()
        k0 = self.keys[0]
        absolute = {p: float(v) for p, v in (absolute or {}).items() if p in self.t.params}
        rel = {p: float(v) for p, v in (rel or {}).items() if p in self.t.params}

        # --- gộp vào ngữ cảnh
        old_req, old_vals = dict(sess.req), dict(sess.vals or {})
        dropped = []
        key_changed = any(k in absolute and k in sess.req and absolute[k] != sess.req[k]
                          for k in self.keys)
        if key_changed:
            # đổi cỡ: bỏ các giá trị riêng của cỡ cũ (trừ L) nếu câu này không nhắc lại
            for p in list(sess.req):
                if p not in self.keys and p != "L" and p not in absolute:
                    dropped.append(_kv(p, sess.req.pop(p)))
        base = sess.vals or {}
        for p, dv in rel.items():
            cur = sess.req.get(p, base.get(p))
            if not isinstance(cur, (int, float)):
                return Reply(bi(f"Chưa có giá trị {p} hiện tại để {'tăng' if dv > 0 else 'giảm'}. "
                                f"Bạn cho mình giá trị cụ thể nhé (vd: {p}=60).",
                                f"There is no current {p} value to {'increase' if dv > 0 else 'decrease'}. "
                                f"Please give a specific value (e.g. {p}=60).",
                                f"{'増やす' if dv > 0 else '減らす'}元になる現在の {p} がありません。"
                                f"具体的な値を指定してください（例: {p}=60）。"), [])
            sess.req[p] = round(cur + dv, 4)
        sess.req.update(absolute)
        if key_changed:                                   # khoá phụ (d) không còn hợp lệ -> bỏ
            for k in self.keys:
                if k in sess.req and k not in absolute and \
                        float(sess.req[k]) not in candidates(self.t, sess.req, k):
                    dropped.append(_kv(k, sess.req.pop(k)))
        if sess.pending and sess.pending in sess.req:
            sess.pending = None
        drop_s = ", ".join(dropped)

        vals, notes, errors = resolve_params(self.t, sess.req)
        ask = self._ask(sess, errors)
        if ask:
            got = ", ".join(_kv(k, v) for k, v in sess.req.items())
            pre = []
            if got:
                pre.append(bi(f"Mình đã ghi nhận: {got}.", f"Noted: {got}.", f"了解しました: {got}。"))
            if dropped:
                pre.append(bi(f"Đã bỏ giá trị không còn hợp lệ: {drop_s}.",
                              f"Removed values that are no longer valid: {drop_s}.",
                              f"無効になった値を削除しました: {drop_s}。"))
            if pre:
                ask.text = bjoin("\n", pre + [ask.text])
            return ask
        if errors:
            sess.req = old_req                               # không áp thay đổi lỗi
            bad_key = next((k for k in self.keys if any(e.startswith(f"{k}=") for e in errors)), None)
            chips = ([_kv(bad_key, x) for x in candidates(self.t, sess.req, bad_key)][:8]
                     if bad_key else [C_SIZES])
            text = bi("Chưa vẽ được vì:\n• ", "Could not draw because:\n• ", "作図できませんでした:\n• ") \
                + bjoin("\n• ", errors)
            if sess.vals:
                text += bi("\nMình giữ nguyên bản vẽ trước.", "\nThe previous drawing is kept.",
                           "\n前の図面はそのまま残しています。")
            return Reply(text, chips)

        # --- vẽ
        d = self.draw(vals)
        sess.vals, sess.pending = vals, None
        # vừa cảnh báo ngoài bảng ở tham số nào => câu trả lời ngắn tiếp theo ("50") áp vào đó
        wp = next((re.match(r"⚠ (\w+)=", n).group(1) for n in notes
                   if n.startswith("⚠") and re.match(r"⚠ (\w+)=", n)), None)
        if wp:
            sess.pending = wp
        sess.last = {"job": d["job"], "output": d["output"]}
        bad = [c for c in d.get("check", []) if abs(c[1] - c[2]) > 0.01]
        is_num = lambda v: isinstance(v, (int, float))
        changes = ", ".join(f"{p} {_fmt(old_vals[p])} → {_fmt(vals[p])}" for p in vals
                            if p in old_vals and is_num(vals[p]) and is_num(old_vals[p])
                            and abs(old_vals[p] - vals[p]) > 1e-9
                            and not self.t.params.get(p, {}).get("derived"))
        title = self._title(vals)
        sc = d.get("scale")
        lines = [bi(f"✅ Đã vẽ **{title}**" + (f" (tỷ lệ {sc})." if sc else " (tỷ lệ 1:1)."),
                    f"✅ Drew **{title}**" + (f" (scale {sc})." if sc else " (scale 1:1)."),
                    f"✅ **{title}** を作図しました" + (f"（尺度 {sc}）。" if sc else "（尺度 1:1）。"))]
        if changes:
            lines.append(bi(f"Thay đổi: {changes}.", f"Changes: {changes}.", f"変更: {changes}。"))
        if dropped:
            lines.append(bi(f"Đổi cỡ nên bỏ giá trị riêng của cỡ cũ: {drop_s}.",
                            f"Size changed, so values specific to the old size were removed: {drop_s}.",
                            f"サイズを変更したため、旧サイズ固有の値を削除しました: {drop_s}。"))
        dims = ", ".join(f"{p}={_fmt(v)}" for p, v in vals.items()
                         if p not in self.keys and is_num(v) and not self.t.params.get(p, {}).get("derived"))
        lines.append(bi(f"Kích thước: {dims}.", f"Dimensions: {dims}.", f"寸法: {dims}。"))
        info = [self._desc(p) + f": {vals[p]}" if str(self._desc(p)) else Bi(f"{p}: {vals[p]}")
                for p in self.info if p in vals]
        if info:
            lines.append(bjoin(" · ", info))
        auto = [n for n in notes if not n.startswith("⚠")]
        warn = [n for n in notes if n.startswith("⚠")]
        if auto:
            short = [Bi(str(n).replace(" chưa chỉ định -> dùng ", "="),
                        en_of(n).replace(" not specified -> using ", "="),
                        ja_of(n).replace(" 未指定 -> ", "=").replace(" を使用", "")) for n in auto]
            lines.append(bi("Mình tự chọn: ", "Chosen automatically: ", "自動選択: ") + bjoin("; ", short)
                         + bi(".", ".", "。"))
        lines += warn
        if d.get("check") is not None:
            n_ck = len(d["check"])
            bad_s = ", ".join(c[0] for c in bad)
            lines.append(bi(f"Đo lại {n_ck} kích thước sau khi vẽ: " + ("khớp ✔" if not bad else f"LỆCH ở {bad_s}"),
                            f"Re-measured {n_ck} dimensions after drawing: "
                            + ("match ✔" if not bad else f"MISMATCH at {bad_s}"),
                            f"作図後に {n_ck} 寸法を再計測: " + ("一致 ✔" if not bad else f"不一致: {bad_s}")))
        if self.t.spec.get("draft"):
            lines.append(bi("⚠ Mẫu tự học (nháp): bảng và hình vẽ cần người kiểm tra trước khi dùng.",
                            "⚠ Self-learned template (draft): the table and drawing need a human check "
                            "before use.",
                            "⚠ 自動学習テンプレート（下書き）: 使用前に表と図面を人が確認してください。"))
        lines.append(bi("Muốn chỉnh gì nữa không?", "Anything else to change?", "ほかに変更はありますか？"))
        L = vals.get("L", 0)
        return Reply(bjoin("\n", lines),
                     [bi("tăng L thêm 10", "increase L by 10", "Lを10増やす"),
                      bi("giảm L 10", "decrease L by 10", "Lを10減らす") if L > 50 else "L=60",
                      bi(f"Đổi {k0}", f"Change {k0}", f"{k0}を変更"), C_NEW],
                     params={k: v for k, v in vals.items()}, output=d["output"], job=d["job"],
                     check=d.get("check"))
