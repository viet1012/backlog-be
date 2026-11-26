"""Bot dùng LLM cục bộ (LM Studio / OpenAI API): kiểm tra bằng server LLM giả lập."""
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import pytest

from pdf2cad import llm
from pdf2cad import template as T
from pdf2cad.bot import Bot, Session


class _Fake(BaseHTTPRequestHandler):
    models = ["text-embedding-nomic", "google/gemma-3-27b", "qwen/qwen3.5-9b"]
    received: list = []

    def log_message(self, *a):
        pass

    def _send(self, obj):
        b = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        self._send({"data": [{"id": m} for m in self.models]})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        _Fake.received.append(body)
        user = body["messages"][-1]["content"]

        def call(name, args):
            return {"role": "assistant", "content": None, "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": name, "arguments": json.dumps(args)}}]}
        if "dài 80" in user:                       # LLM hiểu "số 16 dài 80"
            msg = call("set_params", {"values": {"No": 16, "L": 80}})
        elif "ngắn bớt" in user:
            msg = call("set_params", {"changes": {"L": -10}})
        elif "bảng" in user:
            msg = call("show_sizes", {})
        else:
            msg = {"role": "assistant", "content": "<think>...</think>Mình chỉ hỗ trợ vẽ lại bản vẽ."}
        self._send({"choices": [{"message": msg}]})


@pytest.fixture
def fake_llm(monkeypatch):
    srv = HTTPServer(("127.0.0.1", 0), _Fake)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    _Fake.received = []
    monkeypatch.setenv("PDF2CAD_LLM_URL", f"http://127.0.0.1:{srv.server_port}/v1")
    monkeypatch.delenv("PDF2CAD_LLM_MODEL", raising=False)
    monkeypatch.setenv("PDF2CAD_LLM", "auto")
    yield srv
    srv.shutdown()


@pytest.fixture
def bot(tmp_path):
    t = {x.name: x for x in T.list_templates()}["dsjc-b"]

    def draw(vals):
        g = T.generate(t, vals, Path(tmp_path) / f"{T.file_stem(t, vals)}.dxf")
        return {"job": "j", "output": Path(g["output"]).name, "scale": g.get("scale"), "check": g["check"]}
    return Bot(t, draw)


def test_llm_tool_calls_drive_the_bot(fake_llm, bot):
    lb = llm.LlmBot(bot, llm.client())
    sess = Session("dsjc-b")
    r, model = lb.respond(sess, "vẽ cho mình số 16 dài 80")
    assert model == "qwen/qwen3.5-9b"                         # tự chọn model có tool calling, bỏ embedding
    assert r.output == "dsjc-b_No16_L80.dxf" and "No.16 · L=80" in r.text and "No.16 · L=80" in r.text.en
    body = _Fake.received[-1]
    assert body["model"] == "qwen/qwen3.5-9b" and {t["function"]["name"] for t in body["tools"]} >= {"set_params"}
    assert body["messages"][-1]["content"].endswith("/no_think")
    r, _ = lb.respond(sess, "ngắn bớt 10mm")                  # thay đổi tương đối, nhớ ngữ cảnh
    assert sess.vals["L"] == 70 and "L 80 → 70" in r.text
    assert len(_Fake.received[-1]["messages"]) > 2            # có lịch sử hội thoại
    r, _ = lb.respond(sess, "cho xem bảng")
    assert "No.25" in r.text
    r, _ = lb.respond(sess, "thời tiết hôm nay?")             # ngoài phạm vi: không hiện chữ LLM tự viết
    assert "Mình chỉ hỗ trợ" not in r.text and "chưa hiểu" in r.text


def test_llm_down_raises(monkeypatch, bot):
    monkeypatch.setenv("PDF2CAD_LLM_URL", "http://127.0.0.1:9/v1")   # cổng không có server
    with pytest.raises(llm.LlmError):
        llm.LlmBot(bot, llm.client()).respond(Session("dsjc-b"), "số 16 dài 80")
    assert llm.status()["ok"] is False


def test_llm_off(monkeypatch):
    monkeypatch.setenv("PDF2CAD_LLM", "off")
    assert llm.client() is None and llm.status() == {"enabled": False, "ok": False, "mode": "off"}


def test_api_bot_uses_llm_and_falls_back(fake_llm, tmp_path, monkeypatch):
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from pdf2cad.web.app import Settings, create_app
    app = create_app(Settings(data_dir=tmp_path))
    with TestClient(app) as c:
        r = c.post("/api/bot", json={"template": "dsjc-b", "message": "số 16 dài 80"}).json()
        assert r["engine"] == "llm:qwen/qwen3.5-9b" and r["output"] == "dsjc-b_No16_L80.dxf"
        assert c.get("/api/health").json()["llm"]["model"] == "qwen/qwen3.5-9b"
        monkeypatch.setenv("PDF2CAD_LLM_URL", "http://127.0.0.1:9/v1")      # LM Studio tắt
        r = c.post("/api/bot", json={"template": "dsjc-b", "message": "No.13 L=60"}).json()
        assert r["engine"] == "rules" and r["output"] == "dsjc-b_No13_L60.dxf"
