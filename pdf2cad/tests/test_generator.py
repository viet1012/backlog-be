"""Mẫu vẽ mới (generator) cho SPLB-B / DSPLB-B: bảng nhiều khoá, dung sai theo quy tắc, bot."""
import itertools

import ezdxf
import pytest

from pdf2cad.bot import Bot, Session
from pdf2cad.template import (TEMPLATES_DIR, Template, expand_values, file_stem, generate,
                              parse_request, resolve_params)

need = pytest.mark.skipif(not (TEMPLATES_DIR / "dsplb-b" / "template.json").exists(),
                          reason="chưa có mẫu")


def test_expand_values():
    assert expand_values("20~50/55/60~80") == [20, 30, 40, 50, 55, 60, 70, 80]
    assert expand_values("15~60") == [15, 20, 30, 40, 50, 60]
    assert expand_values("45/50~70/300") == [45, 50, 60, 70, 300]


def _dim_texts(path):
    doc = ezdxf.readfile(path)
    out = []
    for dm in doc.modelspace().query("DIMENSION"):
        blk = doc.blocks.get(dm.dxf.geometry)
        out += [e.text if e.dxftype() == "MTEXT" else e.dxf.text for e in blk
                if e.dxftype() in ("MTEXT", "TEXT")]
    return out, doc


@need
def test_dsplb_generate(tmp_path):
    t = Template.load(TEMPLATES_DIR / "dsplb-b")
    v, notes, err = resolve_params(t, {"D": 65, "d": 18, "L": 250})
    assert not err and v["C"] == 0.8 and v["Y"] == 0.65
    g = generate(t, v, tmp_path / f"{file_stem(t, v)}.dxf")
    assert g["scale"] == "1:2"
    assert all(abs(w - x) < 1e-6 for _, w, x in g["check"])
    texts, doc = _dim_texts(g["output"])
    assert any(s.startswith("\\A0;250") and "+0.3" in s for s in texts)
    assert any("%%c18" in s and "±0.2" in s for s in texts)          # d ≥ 14 => ±0.2
    assert not doc.audit().has_errors
    v, _, err = resolve_params(t, {"D": 30, "d": 13, "L": 100})
    assert err                                                        # d=13 không có với D=30


@need
def test_splb_tolerance_rule(tmp_path):
    t = Template.load(TEMPLATES_DIR / "splb-b")
    v, _, _ = resolve_params(t, {"D": 50, "L": 350})
    assert v["MID"] == "M0011573"
    texts, _ = _dim_texts(generate(t, v, tmp_path / "a.dxf")["output"])
    assert any("350" in s and "+1.2" in s for s in texts)             # L>200 => +1.2/+0.6
    v, notes, _ = resolve_params(t, {"D": 12, "L": 250})
    assert any("ngoài bảng" in n for n in notes)


@need
def test_parse_case():
    t = Template.load(TEMPLATES_DIR / "dsplb-b")
    assert parse_request(t, "d=14") == {"d": 14}
    assert parse_request(t, "Φ30 d 9 L120") == {"D": 30, "d": 9, "L": 120}


@need
def test_bot_multikey():
    t = Template.load(TEMPLATES_DIR / "dsplb-b")
    n = itertools.count()
    b, s = Bot(t, lambda v: {"job": "j", "output": f"f{next(n)}.dxf", "check": []}), Session("dsplb-b")
    r = b.respond(s, "vẽ D=30 L=120")
    assert s.pending == "d" and "d=9" in r.chips
    r = b.respond(s, "9")
    assert r.output and r.params["d"] == 9 and r.params["C"] == 0.5
    r = b.respond(s, "D=20")                      # d=9 vẫn hợp lệ với D=20
    assert r.output and r.params["d"] == 9
    r = b.respond(s, "D=15")                      # d=9 không có với D=15 -> bỏ, hỏi lại
    assert r.output is None and s.pending == "d" and "d=4.5" in r.chips
    r = b.respond(s, "L=135")
    r = b.respond(s, "4.5")
    assert r.output and any("ngoài bảng" in x for x in r.text.split("\n"))
    r = b.respond(s, "60")                        # trả lời ngắn sau cảnh báo L => L=60
    assert r.params["L"] == 60
