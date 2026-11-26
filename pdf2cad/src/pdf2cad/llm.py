"""Bot vẽ lại dùng LLM chạy cục bộ (LM Studio hoặc server bất kỳ tương thích OpenAI API).

LLM chỉ HIỂU câu nói và gọi công cụ của bot (đặt kích thước, xem bảng, giải thích tham số, bản vẽ mới,
lấy file). Tra bảng, quy tắc NOTE, vẽ DXF, đo lại và câu trả lời (song ngữ) vẫn do bot.py làm => LLM
không tự bịa số liệu. LM Studio không chạy / lỗi => web tự quay về bot theo luật.

Cấu hình (biến môi trường):
  PDF2CAD_LLM          auto (mặc định: dùng khi server LLM trả lời) | on | off
  PDF2CAD_LLM_URL      http://127.0.0.1:1234/v1   (LM Studio: Developer -> Start Server)
  PDF2CAD_LLM_MODEL    tên model; bỏ trống = tự chọn model hỗ trợ tool calling theo thứ tự ưu tiên
  PDF2CAD_LLM_TIMEOUT  giây chờ mỗi câu trả lời (mặc định 90)
Chỉ gửi tới server LLM trong mạng nội bộ: nội dung là câu chat + thông số mẫu bản vẽ.
"""
from __future__ import annotations

import json
import os
import re
import threading
import time
import urllib.error
import urllib.request

from .bot import C_HELP, C_SIZES, Bot, Reply, Session, _WORDS, _WORDS_EN, _WORDS_JA
from .i18n import Bi, desc_bi, en_of, pick
from .template import _fmt, candidates, cell_info, keys_of

# model có tool calling, nhanh trước (ưu tiên khớp tên đầy đủ, sau đó khớp một phần)
PREFERRED = ("qwen/qwen3.5-9b", "qwen3.5-9b", "openai/gpt-oss-20b", "qwen/qwen3.6-27b", "qwen3.6-27b",
             "qwen3", "qwen", "gpt-oss", "llama-3.3")


def _cfg():
    return {"mode": os.environ.get("PDF2CAD_LLM", "auto").strip().lower() or "auto",
            "url": os.environ.get("PDF2CAD_LLM_URL", "http://127.0.0.1:1234/v1").rstrip("/"),
            "model": os.environ.get("PDF2CAD_LLM_MODEL", "").strip(),
            "timeout": float(os.environ.get("PDF2CAD_LLM_TIMEOUT", 90))}


class LlmError(RuntimeError):
    pass


class LlmClient:
    """Client tối giản cho /v1/models và /v1/chat/completions (không cần thư viện ngoài)."""

    def __init__(self, url: str, model: str = "", timeout: float = 90.0):
        self.url, self.model, self.timeout = url.rstrip("/"), model, timeout
        self._models: tuple[float, list[str]] | None = None
        self._down_until = 0.0                       # server lỗi -> tạm không gọi trong 30 s
        self._lock = threading.Lock()
        self._bad = {}
        self.last_model = ""

    def _req(self, path: str, body: dict | None = None, timeout: float | None = None) -> dict:
        if time.time() < self._down_until:
            raise LlmError("LLM server tạm không phản hồi")
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.url + path, data=data, method="POST" if data else "GET",
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:          # server vẫn chạy, chỉ từ chối yêu cầu: không tạm nghỉ
            try:
                detail = e.read().decode("utf-8", "replace")[:300]
            except Exception:
                detail = ""
            raise LlmError(f"HTTP {e.code}: {detail or e.reason}") from e
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
            self._down_until = time.time() + 30
            raise LlmError(f"{type(e).__name__}: {e}") from e

    def _info(self) -> list[dict]:
        """[{id, loaded, tools}] — LM Studio có /api/v0/models (trạng thái nạp + khả năng tool calling);
        server OpenAI khác chỉ có /v1/models."""
        with self._lock:
            if self._models and time.time() - self._models[0] < 30:
                return self._models[1]
        info = []
        root = self.url[:-3] if self.url.endswith("/v1") else None
        if root:
            try:
                req = urllib.request.Request(root + "/api/v0/models")
                with urllib.request.urlopen(req, timeout=4) as r:
                    for m in json.loads(r.read().decode("utf-8")).get("data", []):
                        if m.get("type") in ("llm", "vlm"):
                            info.append({"id": m["id"], "loaded": m.get("state") == "loaded",
                                         "tools": "tool_use" in (m.get("capabilities") or [])})
            except (urllib.error.URLError, TimeoutError, OSError, ValueError, KeyError):
                info = []
        if not info:
            info = [{"id": m.get("id", ""), "loaded": None, "tools": None}
                    for m in self._req("/models", timeout=4).get("data", [])]
            info = [m for m in info if m["id"] and "embed" not in m["id"].lower()]
        with self._lock:
            self._models = (time.time(), info)
        return info

    def models(self) -> list[str]:
        return [m["id"] for m in self._info()]

    def pick_model(self) -> str:                     # _bad: model nạp lỗi (thiếu VRAM...) -> bỏ qua 10 phút
        if self.model:
            return self.model
        info = [m for m in self._info() if self._bad.get(m["id"], 0) < time.time()]
        if not info:
            raise LlmError("LLM server chưa có model dùng được")

        def rank(m):
            low = m["id"].lower()
            pref = next((i for i, p in enumerate(PREFERRED) if low == p), None)
            if pref is None:
                pref = next((len(PREFERRED) + i for i, p in enumerate(PREFERRED) if p in low), 99)
            # model đã nạp sẵn trước (nạp thêm model lớn dễ lỗi thiếu VRAM), rồi có tool calling
            return (m["loaded"] is False, m["tools"] is False, pref)
        return min(info, key=rank)["id"]

    def chat(self, messages: list[dict], tools: list[dict] | None = None) -> dict:
        for _ in range(3):
            model = self.pick_model()
            body = {"model": model, "messages": messages, "temperature": 0.1, "max_tokens": 800}
            if tools:
                body["tools"], body["tool_choice"] = tools, "auto"
            try:
                r = self._req("/chat/completions", body)
            except LlmError as e:
                if not self.model and "load model" in str(e).lower():     # LM Studio nạp model lỗi
                    self._bad[model] = time.time() + 600
                    continue
                raise
            self.last_model = model
            try:
                return r["choices"][0]["message"]
            except (KeyError, IndexError, TypeError) as e:
                raise LlmError(f"Trả lời LLM không hợp lệ: {str(r)[:200]}") from e
        raise LlmError("Không nạp được model nào trên LLM server")


_CLIENT: LlmClient | None = None
_CLIENT_KEY = None


def client() -> LlmClient | None:
    """Client theo cấu hình hiện tại; None nếu tắt LLM."""
    global _CLIENT, _CLIENT_KEY
    c = _cfg()
    if c["mode"] == "off":
        return None
    key = (c["url"], c["model"], c["timeout"])
    if _CLIENT is None or _CLIENT_KEY != key:
        _CLIENT, _CLIENT_KEY = LlmClient(c["url"], c["model"], c["timeout"]), key
    return _CLIENT


def status() -> dict:
    """Cho /api/health: LLM có dùng được không, model nào."""
    c = _cfg()
    cl = client()
    if cl is None:
        return {"enabled": False, "ok": False, "mode": "off"}
    try:
        return {"enabled": True, "ok": True, "mode": c["mode"], "model": cl.pick_model()}
    except LlmError as e:
        return {"enabled": True, "ok": False, "mode": c["mode"], "error": str(e)[:200]}


# ----------------------------------------------------------------- công cụ cho LLM
TOOLS = [
    {"type": "function", "function": {
        "name": "set_params",
        "description": "Set dimension values and/or relative changes, then (re)draw the DXF. Use the exact "
                       "case-sensitive parameter names of the template. Missing values are taken from the "
                       "standard table; the tool asks the user if something required is missing. "
                       "Call with empty objects to redraw the current drawing.",
        "parameters": {"type": "object", "properties": {
            "values": {"type": "object", "description": "Absolute values, e.g. {\"No\": 16, \"L\": 80}",
                       "additionalProperties": {"type": "number"}},
            "changes": {"type": "object", "description": "Relative changes, e.g. {\"L\": 10} = longer by "
                                                          "10 mm, {\"H\": -2} = H smaller by 2",
                        "additionalProperties": {"type": "number"}}},
            "required": []}}},
    {"type": "function", "function": {
        "name": "show_sizes", "description": "Show the standard size table and NOTE rules of the template.",
        "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {
        "name": "explain_param", "description": "Explain what a parameter means and its value for the "
                                                "current size.",
        "parameters": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}},
    {"type": "function", "function": {
        "name": "new_drawing", "description": "Forget everything and start a new drawing.",
        "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {
        "name": "change_size", "description": "User wants a different size/key (e.g. another No) but did "
                                              "not say which one: ask them.",
        "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {
        "name": "get_file", "description": "Give the user the latest drawn DXF file (download).",
        "parameters": {"type": "object", "properties": {}}}},
]


def _system_prompt(bot: Bot, sess: Session, lang: str) -> str:
    t = bot.t
    keys = keys_of(t)
    k0 = keys[0]
    params = []
    for p, d in t.params.items():
        if d.get("derived"):
            continue
        db = desc_bi(d)
        params.append(f"- {p}: {en_of(db) or p}" + (f" ({db})" if str(db) and str(db) != en_of(db) else "")
                      + (" [key]" if p in keys else "") + (" [required]" if d.get("required") else ""))
    rows = []
    for r in t.spec["table"][:40]:
        ci = cell_info(r.get("L"), float(t.spec.get("values_step", 10)))
        rows.append(" ".join(f"{k}={_fmt(float(r[k]))}" for k in keys)
                    + (f" L={en_of(ci.get('text', ''))}" if ci else ""))
    rules = "; ".join(x["text"] for x in t.spec.get("rules", []))
    words = ", ".join(f"'{w}'={p}" for w, p in {**_WORDS, **_WORDS_EN, **_WORDS_JA}.items() if p in t.params)
    state = (f"requested so far: {json.dumps(sess.req)}; last drawing: "
             f"{json.dumps(sess.vals) if sess.vals else 'none'}; waiting for answer about: {sess.pending or '-'}")
    reply_lang = {"en": "English", "ja": "Japanese"}.get(lang, "Vietnamese")
    return (
        f"You are the drawing assistant of pdf2cad for the part family \"{t.title}\". You redraw a 2D DXF "
        f"drawing from dimension parameters by calling tools. The user writes Vietnamese, English or "
        f"Japanese.\n"
        f"Parameters (names are case-sensitive, 'L' and 'l' differ):\n" + "\n".join(params) + "\n"
        f"Valid {k0} values: {', '.join(_fmt(x) for x in candidates(t, {}, k0))}.\n"
        f"Table rows (key and allowed L): " + " | ".join(rows) + "\n"
        + (f"NOTE rules: {rules}\n" if rules else "")
        + (f"Word hints: {words}. 'số'/'size'/'cỡ'/'No.'/'サイズ'/'呼び径' = {k0}.\n" if words else "")
        + f"Current state: {state}\n"
        "Rules:\n"
        "- Anything about dimensions, sizes, drawing or files MUST go through a tool. Never invent dimension "
        "values, table data or file names; the tool output is shown to the user directly.\n"
        "- A bare number (e.g. '60') answers the parameter we are waiting for.\n"
        "- 'tăng/thêm/dài hơn/longer/increase/増やす/長く X by N' -> changes {X: N}; "
        "'giảm/bớt/ngắn hơn/shorter/decrease/減らす/短く' -> changes {X: -N}; "
        "'đổi X thành N / X = N / set X to N / XをNにする' -> values {X: N}.\n"
        "- Only if the message has nothing to do with drawings, answer in one or two short sentences in "
        f"{reply_lang} without tools."
    )


OUT_OF_SCOPE = Bi("Mình chỉ hỗ trợ vẽ lại bản vẽ theo mẫu: cho mình kích thước (vd “số 16 dài 80”), "
                  "hỏi bảng size hoặc ý nghĩa tham số nhé.",
                  "I can only help redraw drawings from the template: give me dimensions (e.g. “No.16 L=80”), "
                  "ask for the size table or what a parameter means.",
                  "テンプレートからの図面作図のみ対応しています: 寸法（例「サイズ16 長さ80」）を指定するか、"
                  "サイズ一覧やパラメータの意味を聞いてください。")


def _strip_think(s: str | None) -> str:
    return re.sub(r"<think>.*?(</think>|$)", "", s or "", flags=re.S).strip()


def _args(call: dict) -> dict:
    raw = (call.get("function") or {}).get("arguments") or "{}"
    try:
        a = json.loads(raw) if isinstance(raw, str) else dict(raw)
    except ValueError:
        a = {}
    return a if isinstance(a, dict) else {}


def _nums(d) -> dict:
    out = {}
    for k, v in (d or {}).items() if isinstance(d, dict) else []:
        try:
            out[str(k)] = float(str(v).replace(",", "."))
        except ValueError:
            continue
    return out


class LlmBot:
    """Bọc Bot: LLM hiểu câu -> gọi đúng hàm của Bot."""

    def __init__(self, bot: Bot, cl: LlmClient):
        self.bot, self.cl = bot, cl

    def _run(self, name: str, a: dict, sess: Session, text: str = "") -> Reply:
        b = self.bot
        if name == "set_params":
            return b.apply(sess, _nums(a.get("values")), _nums(a.get("changes")))
        if name == "show_sizes":
            return b.sizes()
        if name == "explain_param":
            p = str(a.get("name", "")).strip()
            # model nhỏ đôi khi gọi nhầm cho câu ngoài lề: chỉ giải thích khi câu hỏi có nhắc tên tham số
            if not re.search(rf"(?<![A-Za-z0-9]){re.escape(p)}(?![A-Za-z0-9])", text):
                return Reply(OUT_OF_SCOPE, [C_SIZES, C_HELP])
            return b.explain(p, sess)
        if name == "new_drawing":
            return b.new_drawing(sess)
        if name == "change_size":
            return b.change_key(sess)
        if name == "get_file":
            return b.latest_file(sess)
        return b.not_understood()

    def respond(self, sess: Session, text: str, lang: str = "vi") -> tuple[Reply, str]:
        """-> (câu trả lời, tên model). Lỗi LLM => ném LlmError để gọi bot luật thay thế."""
        text = (text or "").strip()
        if not text:
            return self.bot.greet(), ""
        model = self.cl.pick_model()
        user = text + (" /no_think" if "qwen3" in model.lower() else "")   # Qwen3: tắt suy luận dài
        msgs = [{"role": "system", "content": _system_prompt(self.bot, sess, lang)}]
        msgs += sess.history[-8:]
        msgs.append({"role": "user", "content": user})
        m = self.cl.chat(msgs, TOOLS)
        calls = m.get("tool_calls") or []
        reply: Reply | None = None
        for c in calls[:3]:                          # vd new_drawing rồi set_params
            reply = self._run((c.get("function") or {}).get("name", ""), _args(c), sess, text)
        if reply is None:
            # LLM không gọi công cụ (chào hỏi, câu ngoài lề...): KHÔNG hiện chữ LLM tự viết (đo thực tế:
            # gpt-oss-20b bịa "hôm nay trời mưa nhẹ") -> để bot luật trả lời (chào / hướng dẫn / gợi ý cách nói)
            reply = self.bot.respond(sess, text)
        sess.updated = time.time()
        summary = pick(reply.text, lang)
        sess.history = (sess.history + [{"role": "user", "content": text},
                                        {"role": "assistant", "content": summary[:400]}])[-8:]
        return reply, self.cl.last_model or model
