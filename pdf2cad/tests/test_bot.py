"""Bot hội thoại: nhớ ngữ cảnh, hỏi lại khi thiếu, hiểu câu sửa đổi."""
import itertools

import pytest

from pdf2cad.bot import Bot, Session
from pdf2cad.template import TEMPLATES_DIR, Template

TPL = TEMPLATES_DIR / "dsjc-b"
pytestmark = pytest.mark.skipif(not (TPL / "template.json").exists(), reason="chưa có mẫu dsjc-b")


@pytest.fixture()
def bot():
    t = Template.load(TPL)
    n = itertools.count()
    return Bot(t, lambda v: {"job": "j", "output": f"f{next(n)}.dxf", "check": []}), Session("dsjc-b")


def test_dialogue(bot):
    b, s = bot
    assert "Xin chào" in b.respond(s, "").text
    r = b.respond(s, "vẽ lại bản vẽ này với L = 60")
    assert r.output is None and "No" in r.text and s.pending == "No"
    r = b.respond(s, "10")                                  # trả lời ngắn
    assert r.output and r.params["No"] == 10 and r.params["L"] == 60
    r = b.respond(s, "tăng L thêm 10")
    assert r.params["L"] == 70 and "60 → 70" in r.text
    r = b.respond(s, "đổi P thành 8.5")
    assert r.params["P"] == 8.5 and "ngoài bảng" in r.text
    r = b.respond(s, "giảm chiều dài xuống 55")
    assert r.params["L"] == 55
    r = b.respond(s, "dài hơn 5")
    assert r.params["L"] == 60
    r = b.respond(s, "H+2")
    assert r.params["H"] == 15
    r = b.respond(s, "No.16")                               # đổi cỡ: bỏ giá trị riêng của cỡ cũ
    assert r.params["P"] == 14 and r.params["H"] == 19 and r.params["L"] == 60
    r = b.respond(s, "d1=9")                                # vi phạm hình học: giữ bản cũ
    assert r.output is None and s.vals["d1"] == 2.8
    assert "Đường kính thân" in b.respond(s, "P là gì?").text
    assert "No.25" in b.respond(s, "có những size nào?").text
    assert b.respond(s, "tải file").output
    r = b.respond(s, "bản vẽ mới")
    assert s.req == {} and s.vals is None


def test_ask_L(bot):
    b, s = bot
    r = b.respond(s, "No 8")
    assert "L" in r.text and s.pending == "L" and r.chips
    r = b.respond(s, "40")
    assert r.params["S"] == 15                              # NOTE: No≤13 & L=40 ⇒ S=15
