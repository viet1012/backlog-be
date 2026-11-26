"""Tab 3D: mặt cắt chi tiết tròn xoay từ tham số mẫu + chỉnh kích thước rồi vẽ lại."""
import pytest

from pdf2cad import solid3d
from pdf2cad import template as T


@pytest.fixture(scope="module")
def tps():
    return {t.name: t for t in T.list_templates()}


def _vals(t, req):
    vals, _, errs = T.resolve_params(t, req)
    assert not errs
    return vals


def test_dsjc_profile(tps):
    p = solid3d.profile(tps["dsjc-b"], _vals(tps["dsjc-b"], {"No": 16, "L": 80}))
    assert p["outer"] == [[0, 9.5], [5, 9.5], [5, 7], [80, 7]]           # mũ ΦH19 dày 5, thân ΦP14
    assert p["inner"][0] == [0, 2.5] and p["inner"][-1] == [80, 1.4]       # ren M5 ở mũ, Φd1 2.8 ở mũi
    assert [0 + 80 - 36, 2.15] in p["inner"]                              # Φd2 tới cách mũi S=36
    assert p["cross"] == [{"x": 43.0, "d": 1.5}] and p["L"] == 80 and p["Dmax"] == 19
    assert p["warnings"] == []


def test_generator_profiles(tps):
    p = solid3d.profile(tps["dsplb-b"], _vals(tps["dsplb-b"], {"D": 30, "d": 9, "L": 120}))
    assert p["outer"][1] == [0.5, 15] and p["inner"][1] == [0.5, 4.5]     # vát C0.5, lỗ suốt Φ9
    p = solid3d.profile(tps["splb-b"], _vals(tps["splb-b"], {"D": 30, "L": 120}))
    assert p["outer"] == [[0, 15], [120, 15]] and p["inner"] == []


def test_api_model3d_and_apply(tmp_path, monkeypatch):
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from pdf2cad.web.app import Settings, create_app
    monkeypatch.setenv("PDF2CAD_LLM", "off")
    monkeypatch.setenv("PDF2CAD_ACCESS_LOG", "off")
    with TestClient(create_app(Settings(data_dir=tmp_path))) as c:
        r = c.post("/api/bot", json={"template": "dsjc-b", "message": "No.16 L=80"}).json()
        m = c.post("/api/model3d", json={"template": "dsjc-b", "params": r["params"]}).json()
        assert m["available"] and m["profile"]["L"] == 80
        names = {p["name"]: p for p in m["params"]}
        assert names["No"]["key"] and 16 in names["No"]["choices"] and "S1" not in names
        a = c.post("/api/bot/apply", json={"template": "dsjc-b", "session": r["session"],
                                           "values": {"L": 100, "H": "20"}}).json()
        assert a["output"] == "dsjc-b_No16_L100.dxf" and a["params"]["H"] == 20 and "L 80 → 100" in a["text"]
        m = c.post("/api/model3d", json={"template": "dsjc-b", "params": a["params"]}).json()
        assert m["profile"]["outer"][0] == [0, 10] and m["profile"]["L"] == 100
        d = c.post("/api/model3d", json={"template": "dsjc-b", "params": {}}).json()   # chưa vẽ: cỡ đầu bảng
        assert d["available"] and d["values"]["No"] == 8 and d["values"]["L"] == 50


def test_turned_from_drawn_dxf(tmp_path):
    """Vẽ chi tiết từ các đoạn -> đọc lại biên dạng từ chính bản DXF đó (vòng kín)."""
    from pdf2cad import turned
    steps = [{"len": 5, "D": 19, "d": 5}, {"len": 75, "D": 14, "d": 2.8}]
    out = turned.draw_turned(steps, tmp_path / "p.dxf", title="TEST")
    p = turned.extract_profile(out)
    assert p is not None and abs(p["L"] - 80) < 0.05 and abs(p["Dmax"] - 19) < 0.05
    got = [(round(s["len"]), round(s["D"]), round(s["d"], 1)) for s in p["steps"]]
    assert got == [(5, 19, 5.0), (75, 14, 2.8)], got
    prof = turned.steps_profile([{"len": 10, "D": 8, "d": 9}])          # lỗ > thân: kẹp lại
    assert prof["steps"][0]["d"] < 8


def test_api_job_model3d_and_redraw(tmp_path, monkeypatch):
    pytest.importorskip("httpx")
    from pathlib import Path

    from fastapi.testclient import TestClient

    from pdf2cad import turned
    from pdf2cad.web.app import Job, Settings, create_app
    from pdf2cad.pipeline import ConvertOptions
    monkeypatch.setenv("PDF2CAD_ACCESS_LOG", "off")
    app = create_app(Settings(data_dir=tmp_path))
    mgr = app.state.manager
    jd = tmp_path / "jobs" / ("a" * 32)
    turned.draw_turned([{"len": 30, "D": 20, "d": 8}], jd / "out" / "part_p001.dxf")
    mgr.jobs["a" * 32] = Job("a" * 32, jd, [{"name": "part.pdf", "path": "", "pages": 1}], ConvertOptions(),
                             status="done", reports=[{"file": "part.pdf", "page": 1, "output": "part_p001.dxf",
                                                      "scale_source": "title-block", "scale": 1.0}])
    with TestClient(app) as c:
        m = c.post(f"/api/jobs/{'a' * 32}/model3d/part_p001.dxf").json()
        assert m["available"] and round(m["profile"]["steps"][0]["D"]) == 20
        r = c.post(f"/api/jobs/{'a' * 32}/redraw3d/part_p001.dxf",
                   json={"steps": [{"len": 40, "D": 25, "d": 10}]}).json()
        assert r["output"] == "part_p001_3d1.dxf" and Path(jd / "out" / "part_p001_3d1.dxf").exists()
        assert r["profile"]["L"] == 40
        st = c.get(f"/api/jobs/{'a' * 32}").json()
        assert any(x["output"] == "part_p001_3d1.dxf" and x["kind"] == "3d-edit" for x in st["reports"])
        again = c.post(f"/api/jobs/{'a' * 32}/model3d/part_p001_3d1.dxf").json()   # đọc lại bản vừa vẽ
        assert round(again["profile"]["steps"][0]["D"]) == 25 and round(again["profile"]["L"]) == 40
