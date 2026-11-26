"""Mẫu tham số DSJC-B: quy tắc bảng/NOTE và vẽ lại đúng kích thước."""
from pathlib import Path

import ezdxf
import pytest

from pdf2cad.template import TEMPLATES_DIR, Template, chat, parse_request, resolve_params

TPL = TEMPLATES_DIR / "dsjc-b"
pytestmark = pytest.mark.skipif(not (TPL / "template.json").exists(), reason="chưa có mẫu dsjc-b")


@pytest.fixture(scope="module")
def t():
    return Template.load(TPL)


def test_parse(t):
    assert parse_request(t, "vẽ DSJC-B No.16 L=80 P=15") == {"No": 16, "L": 80, "P": 15}
    assert parse_request(t, "số 13 dài 70, d1=1.8") == {"No": 13, "L": 70, "d1": 1.8}


def test_rules(t):
    v, notes, err = resolve_params(t, {"No": 8, "L": 40})
    assert not err and v["S"] == 15 and v["H"] == 11 and v["P"] == 7
    v, notes, err = resolve_params(t, {"No": 16, "L": 50})
    assert v["S"] == 24
    v, notes, err = resolve_params(t, {"No": 20})
    assert any("Thiếu L" in e for e in err)
    v, notes, err = resolve_params(t, {"No": 25, "L": 80, "H": 30})
    assert not err and any("ngoài bảng" in n for n in notes)
    v, notes, err = resolve_params(t, {"No": 8, "L": 60, "d1": 5})
    assert err                                  # d1 > d2: vi phạm hình học


def test_generate_exact(t, tmp_path):
    r = chat(t, "No.16 L=80 P=15", tmp_path)
    assert not r["errors"] and Path(r["output"]).exists()
    for name, want, got in r["check"]:
        assert abs(want - got) < 1e-3, name
    msp = ezdxf.readfile(r["output"]).modelspace()
    texts = {e.dxf.text for e in msp.query("TEXT")}
    assert {"80", "36%%p0.2", "(37)", "%%c19", "%%c15", "M5"} <= texts
    assert "DSJC-B No.16-L80" in texts
    # chiều dài tổng đo trên đường bao ngoài = 80
    xs = [p.x for e in msp.query('LINE[layer=="OUTLINE"]') for p in (e.dxf.start, e.dxf.end)
          if 60 < p.x < 260 and 120 < p.y < 165]
    assert abs((max(xs) - min(xs)) - 80) < 0.01
