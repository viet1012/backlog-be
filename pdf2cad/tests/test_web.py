import time

import ezdxf
import pytest

pytest.importorskip("fastapi")
pytest.importorskip("httpx")
from fastapi.testclient import TestClient  # noqa: E402

from pdf2cad.web.app import Settings, create_app  # noqa: E402


@pytest.fixture()
def client(tmp_path):
    app = create_app(Settings(data_dir=tmp_path / "data", page_workers=1, max_upload_mb=20))
    with TestClient(app) as c:
        yield c


def _wait(c, jid, timeout=120):
    t0 = time.time()
    while time.time() - t0 < timeout:
        j = c.get(f"/api/jobs/{jid}").json()
        if j["status"] in ("done", "error", "cancelled"):
            return j
        time.sleep(0.2)
    raise TimeoutError


def test_index_and_health(client):
    assert "pdf2cad" in client.get("/").text
    h = client.get("/api/health").json()
    assert h["ok"] and "version" in h


def test_convert_roundtrip(client, sample_pdf, tmp_path):
    with open(sample_pdf, "rb") as f:
        r = client.post("/api/jobs", files=[("files", ("bản vẽ.pdf", f, "application/pdf"))],
                        data={"scale": "2", "ocr": "off"})
    assert r.status_code == 201, r.text
    j = _wait(client, r.json()["id"])
    assert j["status"] == "done" and j["total_pages"] == 2 == j["done_pages"]
    outs = [x["output"] for x in j["reports"]]
    assert all(outs)
    # tải 1 file DXF
    d = client.get(f"/api/jobs/{j['id']}/files/{outs[0]}")
    assert d.status_code == 200
    p = tmp_path / "x.dxf"
    p.write_bytes(d.content)
    assert ezdxf.readfile(p).modelspace().query("LINE")
    # zip, preview, ảnh PDF
    z = client.get(f"/api/jobs/{j['id']}/zip")
    assert z.status_code == 200 and z.content[:2] == b"PK"
    pv = client.get(f"/api/jobs/{j['id']}/preview/{outs[0]}").json()
    assert pv["count"] > 0
    png = client.get(f"/api/jobs/{j['id']}/pdf/0/1.png")
    assert png.content[:4] == b"\x89PNG"
    # xoá job
    assert client.delete(f"/api/jobs/{j['id']}").status_code == 200
    assert client.get(f"/api/jobs/{j['id']}").status_code == 404


def test_rejects_bad_input(client):
    r = client.post("/api/jobs", files=[("files", ("a.pdf", b"hello", "application/pdf"))])
    assert r.status_code == 400
    r = client.post("/api/jobs", files=[("files", ("a.txt", b"%PDF-1.4", "text/plain"))])
    assert r.status_code == 400
    assert client.get("/api/jobs/../../etc").status_code == 404
    assert client.get("/api/jobs/" + "0" * 32).status_code == 404


def test_path_traversal(client, sample_pdf):
    with open(sample_pdf, "rb") as f:
        r = client.post("/api/jobs", files=[("files", ("../../evil.pdf", f, "application/pdf"))],
                        data={"ocr": "off", "pages": "1"})
    j = _wait(client, r.json()["id"])
    assert j["files"][0]["name"] == "evil.pdf"
    assert client.get(f"/api/jobs/{j['id']}/files/..%2F..%2Fin%2Fevil.pdf").status_code == 404


def test_template_chat(client):
    lst = client.get("/api/templates").json()
    if not any(t["name"] == "dsjc-b" for t in lst):
        pytest.skip("chưa có mẫu dsjc-b")
    r = client.post("/api/templates/dsjc-b/chat", json={"message": "No.10 L=60"}).json()
    assert r["output"] and not r["errors"]
    assert client.get(f"/api/jobs/{r['job']}/files/{r['output']}").status_code == 200
    assert client.get(f"/api/jobs/{r['job']}/preview/{r['output']}").json()["count"] > 0
    r = client.post("/api/templates/dsjc-b/chat", json={"message": "No.10"}).json()
    assert r["errors"] and not r["output"]


def test_bot_api(client):
    if not any(t["name"] == "dsjc-b" for t in client.get("/api/templates").json()):
        pytest.skip("chưa có mẫu dsjc-b")
    r = client.post("/api/bot", json={"template": "dsjc-b", "message": ""}).json()
    sid = r["session"]
    r = client.post("/api/bot", json={"template": "dsjc-b", "session": sid, "message": "L=60"}).json()
    assert r["session"] == sid and not r["output"]
    r = client.post("/api/bot", json={"template": "dsjc-b", "session": sid, "message": "13"}).json()
    assert r["output"] and r["params"]["L"] == 60
    assert all(abs(w - g) < 1e-3 for _, w, g in r["check"])
    assert client.get(f"/api/jobs/{r['job']}/files/{r['output']}").status_code == 200


def test_upload_tiff(client, sample_pdf, tmp_path):
    PIL = pytest.importorskip("PIL.Image")
    import pymupdf
    with pymupdf.open(sample_pdf) as d:
        pix = d[0].get_pixmap(dpi=150, colorspace=pymupdf.csGRAY)
    p = tmp_path / "scan.tif"
    PIL.frombytes("L", (pix.w, pix.h), pix.samples).save(p, dpi=(150, 150))
    with open(p, "rb") as f:
        r = client.post("/api/jobs", files=[("files", ("scan.tif", f, "image/tiff"))],
                        data={"ocr": "off"})
    assert r.status_code == 201, r.text
    j = _wait(client, r.json()["id"])
    assert j["status"] == "done" and j["reports"][0]["output"] == "scan_p001.dxf"
    # đuôi .tif nhưng nội dung không phải TIFF -> từ chối
    r = client.post("/api/jobs", files=[("files", ("x.tif", b"%PDF-1.4 fake", "image/tiff"))])
    assert r.status_code == 400
