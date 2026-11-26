"""Tự học bản vẽ mới: đọc khung tên + bảng tham số (ô gộp, ô trống) -> mẫu nháp cho bot."""
import ezdxf
import pytest

from pdf2cad.bot import Bot, Session
from pdf2cad.learn import learn_from_dxf
from pdf2cad.template import Template, generate, resolve_params


def _drawing(path):
    doc = ezdxf.new("R2018")
    m = doc.modelspace()
    T = lambda s, x, y, h=2.0: m.add_text(s, height=h, dxfattribs={"insert": (x, y)})
    # khung tên
    T("Parts Name", 200, 60); T("SP8,SHB/A-SPB", 195, 54, 2.6)
    T("Drawing Title", 200, 45); T("PUNCH BLANK", 195, 39, 3); T("LATHE JNC", 196, 33, 2.6)
    T("DR-TE-12345-01", 240, 15, 3.5)
    T("MATERIAL:SKD11", 40, 160, 2.6)
    # bảng D | H | T | L ; hàng y = 145, 140, 135, 130 ; T và L gộp 2 hàng cuối
    xs = [30, 40, 50, 60, 100]
    ys = [152, 147, 142, 137, 132, 127]
    for x in xs:
        m.add_line((x, ys[-1]), (x, ys[0]))
    for y in ys:
        m.add_line((xs[0], y), (xs[2], y))                       # D, H: mọi hàng
    for y in (ys[0], ys[1], ys[2], ys[3], ys[5]):                # T, L: 2 hàng cuối gộp
        m.add_line((xs[2], y), (xs[4], y))
    for name, x in zip("DHTL", (34, 44, 54, 75)):
        T(name, x, 148)
    rows = [(3, 5, 143), (4, None, 138), (5, 8, 133), (6, 9, 128)]
    for d, h, y in rows:
        T(f"{d}", 33, y)
        if h is not None:
            T(f"{h}", 43, y)
    T("3", 54, 143); T("30/40/50", 65, 143)             # ô riêng hàng D=3
    T("3", 54, 138); T("30/40/50", 65, 138)             # ô riêng hàng D=4
    T("5", 54, 130.5); T("40/50/60~80", 65, 130.5)     # ô gộp 2 hàng D=5, D=6
    doc.saveas(path)


def test_learn_table(tmp_path):
    p = tmp_path / "x.dxf"
    _drawing(p)
    res = learn_from_dxf(p, tmp_path / "tpl")
    assert len(res) == 1 and res[0]["columns"] == ["D", "H", "T", "L"]
    t = Template.load(res[0]["dir"])
    assert t.spec["draft"] and "DR-TE-12345-01" in t.title and "SKD11" in t.title
    rows = {r["D"]: r for r in t.spec["table"]}
    assert rows[3] == {"D": 3, "H": 5, "T": 3, "L": "30/40/50"}
    assert "H" not in rows[4]                                    # ô trống: không đoán
    assert rows[5]["T"] == 5 and rows[6]["T"] == 5               # ô gộp áp cho cả 2 hàng
    assert rows[5]["L"] == rows[6]["L"] == "40/50/60~80"
    assert t.spec["code"] == "SPB"                               # "SP8" -> "SPB"
    v, _, err = resolve_params(t, {"D": 4, "L": 50})
    assert err and "Thiếu H" in err[0]                           # bot sẽ hỏi H
    v, _, err = resolve_params(t, {"D": 5, "L": 60})
    g = generate(t, v, tmp_path / "o.dxf")
    assert all(abs(w - x) < 1e-6 for _, w, x in g["check"])     # trụ có mũ: L, ΦH, ΦD đúng
    b, s = Bot(t, lambda v: {"job": "j", "output": "o.dxf", "check": []}), Session(t.name)
    assert "tự học" in b.respond(s, "").text
    r = b.respond(s, "D=4 L=50")
    assert r.output is None and "H" in r.text
    r = b.respond(s, "H=7")
    assert r.output and r.params["H"] == 7
