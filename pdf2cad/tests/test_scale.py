import pytest

from pdf2cad.scale import detect_scale


@pytest.mark.parametrize("texts,expected", [
    (["SCALE", "1:2"], 2.0),
    (["Scale: 1/5"], 5.0),
    (["TỶ LỆ 1:10"], 10.0),
    (["TY LE", "2:1"], 0.5),
    (["尺度 1:2"], 2.0),
    (["S=1:20"], 20.0),
    (["DWG-0001", "MATERIAL SS400"], None),
])
def test_detect_scale(texts, expected):
    assert detect_scale(texts) == expected
