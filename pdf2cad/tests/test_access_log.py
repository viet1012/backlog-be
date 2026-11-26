"""Nhật ký truy cập: giờ, IP, tên máy, mã trình duyệt, thao tác; lưu lịch sử theo ngày (jsonl)."""
import json
import re
import time
from pathlib import Path

import pytest

pytest.importorskip("httpx")
from fastapi.testclient import TestClient  # noqa: E402

from pdf2cad.web import app as A  # noqa: E402


def _wait_lines(capsys, n, timeout=5.0):
    out, t0 = "", time.time()
    while time.time() - t0 < timeout:
        time.sleep(0.2)
        out += capsys.readouterr().out
        lines = [ln for ln in out.splitlines() if ln.startswith("[")]
        if len(lines) >= n:
            return lines
    return [ln for ln in out.splitlines() if ln.startswith("[")]


@pytest.fixture(autouse=True)
def _fresh(monkeypatch):
    monkeypatch.setenv("PDF2CAD_LLM", "off")
    monkeypatch.setitem(A._HOSTNAMES, "testclient", "PC-THIET-KE")      # IP giả của TestClient
    A._LAST_EVENT.clear()


def test_access_log_lines_and_history_file(tmp_path, capsys):
    with TestClient(A.create_app(A.Settings(data_dir=tmp_path))) as c:
        c.get("/")                                                    # lần đầu: cấp mã trình duyệt (cookie)
        assert c.cookies.get(A.CID_COOKIE)
        c.get("/api/health")                                          # không ghi
        r = c.post("/api/bot", json={"template": "dsjc-b", "message": "No.16 L=80"}).json()
        c.get(f"/api/jobs/{r['job']}/files/{r['output']}")
        c.post("/api/log", json={"event": "lang", "lang": "ja"})
        c.post("/api/log", json={"event": "close", "secs": 125})
        c.post("/api/log", json={"event": "hack", "x": "y"})           # sự kiện lạ: bỏ qua
        cid = c.cookies.get(A.CID_COOKIE)
    lines = _wait_lines(capsys, 5)
    assert len(lines) == 5, lines
    assert all(re.match(rf"\[\d{{4}}-\d\d-\d\d \d\d:\d\d:\d\d\] testclient\s+PC-THIET-KE\s+#{cid[:4]}\s+", ln)
               for ln in lines)
    assert lines[0].endswith("mở trang web (lần đầu trên trình duyệt này)")
    assert "bot [dsjc-b] “No.16 L=80” -> dsjc-b_No16_L80.dxf" in lines[1]
    assert lines[2].endswith("tải về dsjc-b_No16_L80.dxf")
    assert lines[3].endswith("đổi ngôn ngữ: ja")
    assert lines[4].endswith("đóng / tải lại tab (đã mở 2 phút 5 giây)")
    recs = [json.loads(x) for f in Path(tmp_path, "logs").glob("access-*.jsonl")
            for x in f.read_text(encoding="utf-8").splitlines()]
    assert len(recs) == 5 and {r["client"] for r in recs} == {cid} and recs[0]["host"] == "PC-THIET-KE"


def test_conversion_done_is_logged(tmp_path, capsys):
    pdf = Path(__file__).resolve().parents[1] / "samples" / "sample_drawing.pdf"
    if not pdf.exists():
        pytest.skip("thiếu samples/sample_drawing.pdf")
    with TestClient(A.create_app(A.Settings(data_dir=tmp_path))) as c:
        c.get("/")
        j = c.post("/api/jobs", files={"files": (pdf.name, pdf.read_bytes(), "application/pdf")},
                   data={"pages": "1"}).json()
        for _ in range(120):
            if c.get(f"/api/jobs/{j['id']}").json()["status"] in ("done", "error", "cancelled"):
                break
            time.sleep(0.5)
    lines = _wait_lines(capsys, 3)
    assert any("tải lên và chuyển 1 file" in ln and pdf.name in ln for ln in lines)
    assert any("chuyển xong sample_drawing.pdf: 1/1 trang ra DXF" in ln for ln in lines), lines


def test_access_log_off(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("PDF2CAD_ACCESS_LOG", "off")
    with TestClient(A.create_app(A.Settings(data_dir=tmp_path))) as c:
        c.get("/")
    time.sleep(0.3)
    assert "mở trang web" not in capsys.readouterr().out
    assert not list(Path(tmp_path, "logs").glob("access-*.jsonl"))


def test_client_name_localhost():
    import socket
    A._HOSTNAMES.pop("127.0.0.1", None)
    assert A._client_name("127.0.0.1") == socket.gethostname().split(".")[0]
